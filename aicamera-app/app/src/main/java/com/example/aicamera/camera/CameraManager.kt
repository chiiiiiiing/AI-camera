package com.example.aicamera.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.aicamera.camera.CameraConfig.CameraFace
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 相机管理器 — 整个相机链路的调度中心。
 *
 * ## 职责
 * - CameraX 初始化（ProcessCameraProvider 异步获取）
 * - 三路 UseCase 绑定（Preview + ImageCapture + ImageAnalysis）
 * - 生命周期协调（onResume 绑定 / onPause 解绑）
 * - 帧分析回调桥接（→ 后续模块的帧处理管线）
 * - 前后摄切换时自动解绑 + 重新绑定
 */
class CameraManager(
    private val context: Context
) {
    // ── 公开状态 ──────────────────────────────────────────────

    /** CameraX 是否已初始化成功 */
    @Volatile
    var isInitialized: Boolean = false
        private set

    /** 当前绑定的 Camera 实例（对焦/变焦/曝光操作需要） */
    @Volatile
    var camera: Camera? = null
        private set

    /** 当前使用的摄像头面 */
    var currentFace: CameraFace = CameraFace.BACK
        private set

    /**
     * Pro 模式手动参数（ISO / 白平衡）。
     * 设置后在下一次 [bindUseCases] 时经 Camera2Interop 应用到 capture session。
     */
    data class ProOptions(
        /** ISO 感光度（null = 自动） */
        val iso: Int? = null,
        /** 白平衡模式 CaptureRequest.CONTROL_AWB_MODE_*（null = 自动） */
        val awbMode: Int? = null
    )

    /** 当前 Pro 参数（为 null 表示全自动） */
    var proOptions: ProOptions? = null

    // ── 内部组件 ──────────────────────────────────────────────

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    /** 推理专用线程池（可重建，避免 release 后不可复用） */
    private var analysisExecutor: ExecutorService = createAnalysisExecutor()

    /** 帧到达回调（→ 后续模块的帧处理管线） */
    private var onFrameAvailable: ((ImageProxy) -> Unit)? = null

    // ── 初始化 ────────────────────────────────────────────────

    /**
     * 初始化 CameraX 并绑定到指定生命周期。
     *
     * @param previewView 相机预览的 View
     * @param lifecycleOwner 生命周期持有者（Fragment 场景传 viewLifecycleOwner）
     * @param onReady 初始化完成回调，true 表示成功
     */
    fun init(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onReady: (success: Boolean) -> Unit = {}
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // ★ 绑定结果驱动状态，失败不谎报成功
                val bound = bindUseCases()
                isInitialized = bound
                if (bound) {
                    logInfo("CameraX 初始化成功")
                } else {
                    logError("CameraX UseCase 绑定失败")
                }
                onReady(bound)
            } catch (e: Exception) {
                logError("CameraX 初始化失败", e)
                isInitialized = false
                onReady(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── UseCase 创建与绑定 ────────────────────────────────────

    /** 构建三个 UseCase 并绑定到生命周期，返回是否成功 */
    private fun bindUseCases(): Boolean {
        val provider = cameraProvider ?: return false
        val lifecycle = lifecycleOwner ?: return false
        val view = previewView ?: return false

        // 解绑旧的 UseCase（切换摄像头时复用此方法）
        provider.unbindAll()

        // ★ 确保 executor 可用（release 后也可重复使用）
        ensureAnalysisExecutor()

        // 1. Preview — 实时取景（应用 Pro 参数）
        preview = Preview.Builder()
            .also { applyProOptions(it) }
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }

        // 2. ImageCapture — 拍照（应用 Pro 参数，保证成片与预览一致）
        imageCapture = ImageCapture.Builder()
            .also { applyProOptions(it) }
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        // 3. ImageAnalysis — 帧分析（给 AI 推理用）
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetResolution(CameraConfig.ANALYSIS_RESOLUTION)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor, FrameAnalyzer())
            }

        // 绑定到生命周期
        return try {
            camera = provider.bindToLifecycle(
                lifecycle,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
            logInfo("UseCase 绑定成功: face=$currentFace")
            true
        } catch (e: Exception) {
            logError("UseCase 绑定失败", e)
            camera = null
            false
        }
    }

    // ── 帧分析回调 ────────────────────────────────────────────

    /**
     * 把 [proOptions] 经 Camera2Interop 写入 UseCase builder。
     * ISO / 白平衡属于 capture session 级参数，重绑后生效。
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyProOptions(builder: Any) {
        val opts = proOptions ?: return
        when (builder) {
            is Preview.Builder -> {
                val ext = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                opts.iso?.let {
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
                    )
                }
                opts.awbMode?.let {
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, it
                    )
                }
            }
            is ImageCapture.Builder -> {
                val ext = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                opts.iso?.let {
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
                    )
                }
                opts.awbMode?.let {
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, it
                    )
                }
            }
        }
    }

    // ── 帧分析回调 ────────────────────────────────────────────

    /**
     * 注册帧到达回调。
     *
     * 帧分析线程中回调——不要在回调里做重活，应快速复制数据后 post 到推理线程。
     *
     * @param callback 每次有新帧时调用。imageProxy 由调用方负责 close。
     */
    fun setOnFrameAvailable(callback: (ImageProxy) -> Unit) {
        onFrameAvailable = callback
    }

    /**
     * ImageAnalysis.Analyzer 实现。
     *
     * ★ 兜底异常保护：无论外部回调是否抛异常，确保 imageProxy.close() 被调用，
     *   防止 CameraX 分析链路阻塞。
     */
    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val callback = onFrameAvailable
            if (callback == null) {
                imageProxy.close()
                return
            }

            try {
                callback.invoke(imageProxy)
            } catch (t: Throwable) {
                logError("帧分析回调异常", Exception(t))
                imageProxy.close()
            }
        }
    }

    // ── 摄像头切换 ────────────────────────────────────────────

    /**
     * 切换到指定摄像头面。
     *
     * 自动解绑旧 UseCase → 重建 CameraSelector → 重新绑定。
     * 已初始化的 PreviewView 和其他配置保持不变。
     *
     * @return 绑定是否成功
     */
    fun switchCamera(face: CameraFace): Boolean {
        if (!isInitialized) {
            logWarning("CameraX 未初始化，无法切换摄像头")
            return false
        }

        val newSelector = when (face) {
            CameraFace.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFace.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        cameraSelector = newSelector
        currentFace = face

        // ★ 检查目标摄像头是否存在
        val provider = cameraProvider ?: return false
        if (!provider.hasCamera(newSelector)) {
            logError("目标摄像头不可用: $face")
            return false
        }

        val bound = bindUseCases()
        if (bound) {
            logInfo("摄像头切换完成: $face")
        } else {
            logError("摄像头切换失败: $face")
        }
        return bound
    }

    /** 切换前后摄像头（自动判断当前面 → 切换到另一面） */
    fun toggleCamera(): CameraFace {
        val newFace = when (currentFace) {
            CameraFace.BACK -> CameraFace.FRONT
            CameraFace.FRONT -> CameraFace.BACK
        }
        val success = switchCamera(newFace)
        return if (success) newFace else currentFace
    }

    // ── 公开访问器 ────────────────────────────────────────────

    /** 获取当前 ImageCapture UseCase（拍照用） */
    fun getImageCapture(): ImageCapture? = imageCapture

    /** 获取当前 ImageAnalysis UseCase（帧分析配置查询） */
    fun getImageAnalysis(): ImageAnalysis? = imageAnalysis

    /** 获取当前 PreviewView（坐标映射参考） */
    fun getPreviewView(): PreviewView? = previewView

    /** 是否为前置摄像头 */
    fun isFrontCamera(): Boolean = currentFace == CameraFace.FRONT

    // ── 资源释放 ──────────────────────────────────────────────

    /**
     * 释放相机资源。
     *
     * 在 Fragment/Activity 的 onPause 或 onDestroy 中调用。
     * ★ 清理 View 引用，防止泄漏。
     */
    fun release() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()

        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        previewView = null
        lifecycleOwner = null
        isInitialized = false
        logInfo("相机资源已释放")
    }

    /** 暂停时解绑相机（可选，切换到后台时释放相机） */
    fun unbind() {
        cameraProvider?.unbindAll()
        camera = null
        logInfo("相机已解绑")
    }

    /** 恢复时重新绑定（从后台回到前台）。@return 是否绑定成功 */
    fun rebind(): Boolean {
        return if (lifecycleOwner != null && previewView != null) {
            val bound = bindUseCases()
            if (bound) logInfo("相机已重新绑定") else logError("相机重绑失败")
            bound
        } else {
            logWarning("rebind: lifecycleOwner 或 previewView 为 null，跳过绑定")
            false
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private fun createAnalysisExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor()

    /** ★ 确保 executor 可用：如果已 shutdown 则重建 */
    private fun ensureAnalysisExecutor() {
        if (analysisExecutor.isShutdown || analysisExecutor.isTerminated) {
            analysisExecutor = createAnalysisExecutor()
        }
    }
}

// ── 内部日志 ──────────────────────────────────────────────────

private fun logInfo(msg: String) = Log.i(CameraConfig.TAG, "[Manager] $msg")
private fun logWarning(msg: String) = Log.w(CameraConfig.TAG, "[Manager] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Manager] $msg", e)
