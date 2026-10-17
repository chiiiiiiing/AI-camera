package com.example.aicamera.camera

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.camera.CameraConfig.FlashMode

/**
 * 相机控制：变焦、闪光灯、前后摄像头切换。
 *
 * 不负责对焦（见 [CameraGestureDispatcher]）、不负责拍照（见 [CameraCapture]）。
 *
 * ★ 触摸手势（双指缩放）已统一收归 [CameraGestureDispatcher]，
 *   此类只保留程序化 API（slider/按钮调用），
 *   旧 setOnTouchListener 方案已删除（多个 listener 会互相覆盖）。
 */
class CameraControls(
    private val onCameraFaceChanged: ((CameraFace) -> Unit)? = null,
    private val onZoomChanged: ((zoomRatio: Float) -> Unit)? = null
) {
    /** 当前闪光灯模式 */
    var currentFlashMode: FlashMode = CameraConfig.DEFAULT_FLASH_MODE
        private set

    /** 当前变焦倍数 (1.0–4.0) */
    var currentZoomRatio: Float = CameraConfig.MIN_ZOOM_RATIO
        private set

    // ── 双指变焦 ──────────────────────────────────────────────

    /**
     * 通过滑动条设置变焦。
     */
    fun setZoomRatio(ratio: Float, camera: Camera): Boolean {
        val state = camera.cameraInfo.zoomState.value
        val maxZoom = state?.maxZoomRatio ?: CameraConfig.MAX_ZOOM_RATIO
        // 下限取硬件实际最小倍率（支持超广角 0.5×–0.8× 的机型）；
        // 拿不到 CameraInfo 时才回退到 1×。
        val minZoom = state?.minZoomRatio ?: CameraConfig.MIN_ZOOM_RATIO
        val clamped = ratio.coerceIn(minZoom, maxZoom)

        // 同时对比「本地记录」和「硬件实际值」：
        // 双指捏合是 dispatcher 直接写硬件、不会更新本地 currentZoomRatio，
        // 只比本地值会导致捏合后再点同一个快捷倍率被误判为「无需变化」而不生效。
        val hardwareZoom = state?.zoomRatio ?: currentZoomRatio
        if (kotlin.math.abs(clamped - currentZoomRatio) < 0.005f &&
            kotlin.math.abs(clamped - hardwareZoom) < 0.005f
        ) return true

        return applyZoomRatio(clamped, camera)
    }

    /**
     * 切换摄像头后同步本地变焦基准。
     *
     * 新 Camera 实例的变焦会回到 1×，但旧实现没有更新本地记录，
     * 导致 UI 仍显示切换前的倍率、而实际画面是 1×。
     */
    fun syncZoomBaseline(ratio: Float) {
        currentZoomRatio = ratio
    }

    /**
     * 应用变焦倍数。
     * ★ CameraX setZoomRatio 返回 ListenableFuture，异步完成；
     *   本地乐观更新 currentZoomRatio 用于 UI 即时反馈，
     *   实际变焦完成由 CameraInfo.zoomState 确认。
     */
    private fun applyZoomRatio(clamped: Float, camera: Camera): Boolean {
        return try {
            val future = camera.cameraControl.setZoomRatio(clamped)
            currentZoomRatio = clamped
            onZoomChanged?.invoke(clamped)
            logDebug("变焦请求已发送: ${"%.1f".format(clamped)}x")
            true
        } catch (e: Exception) {
            logError("设置变焦失败", e)
            false
        }
    }

    /**
     * 获取实际变焦倍率（从 CameraX 状态，非乐观本地值）。
     * 用于确认 Zoom 是否真正完成。
     */
    fun actualZoomRatio(camera: Camera): Float {
        return camera.cameraInfo.zoomState.value?.zoomRatio
            ?: currentZoomRatio
    }

    /** 重置变焦为 1.0x */
    fun resetZoom(camera: Camera): Boolean = setZoomRatio(CameraConfig.MIN_ZOOM_RATIO, camera)

    // ── 前后摄像头切换 ────────────────────────────────────────

    /**
     * ★ currentFace 改由 CameraManager 作为唯一状态源，
     * CameraControls 仅负责构造 CameraSelector 和通知回调。
     */
    fun cameraSelectorFor(face: CameraFace): CameraSelector {
        onCameraFaceChanged?.invoke(face)
        return if (face == CameraFace.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // ── 闪光灯 ────────────────────────────────────────────────

    /**
     * ★ 设置闪光灯模式，同时写入 ImageCapture 和 cameraControl。
     *
     * @param mode 闪光灯模式
     * @param camera 当前 Camera（用于 enableTorch）
     * @param imageCapture 当前 ImageCapture（用于设置拍照闪光模式）
     */
    fun setFlashMode(
        mode: FlashMode,
        camera: Camera,
        imageCapture: ImageCapture
    ): Boolean {
        // ★ 检查设备是否支持闪光灯
        if ((mode == FlashMode.ON || mode == FlashMode.TORCH) &&
            !camera.cameraInfo.hasFlashUnit()
        ) {
            currentFlashMode = FlashMode.OFF
            imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
            logWarning("设备无闪光灯，降级为 OFF")
            return false
        }

        return try {
            when (mode) {
                FlashMode.AUTO -> {
                    camera.cameraControl.enableTorch(false)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_AUTO
                }
                FlashMode.ON -> {
                    camera.cameraControl.enableTorch(false)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
                }
                FlashMode.OFF -> {
                    camera.cameraControl.enableTorch(false)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                }
                FlashMode.TORCH -> {
                    camera.cameraControl.enableTorch(true)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                }
            }
            currentFlashMode = mode
            logDebug("闪光灯已设置: $mode")
            true
        } catch (e: Exception) {
            logError("设置闪光灯失败", e)
            false
        }
    }

    /**
     * 获取拍照时对应的 ImageCapture 闪光模式。
     */
    fun getImageCaptureFlashMode(): Int = CameraConfig.flashModeToImageCapture(currentFlashMode)

    /**
     * 循环切换闪光灯模式：AUTO → ON → OFF → TORCH → AUTO
     */
    fun cycleFlashMode(camera: Camera, imageCapture: ImageCapture): FlashMode {
        val next = when (currentFlashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.AUTO
        }
        setFlashMode(next, camera, imageCapture)
        return next
    }

    // ── 查询 ──────────────────────────────────────────────────

    /** 是否处于手电筒模式 */
    fun isTorchOn(): Boolean = currentFlashMode == FlashMode.TORCH
}

// ── 内部日志 ──────────────────────────────────────────────────

private fun logDebug(msg: String) = Log.d(CameraConfig.TAG, "[Controls] $msg")
private fun logWarning(msg: String) = Log.w(CameraConfig.TAG, "[Controls] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Controls] $msg", e)
