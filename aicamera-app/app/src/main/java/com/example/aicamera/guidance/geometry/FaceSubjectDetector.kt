package com.example.aicamera.guidance.geometry

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML Kit 人脸主体检测器 — 为构图引导提供真实 subjectBox。
 *
 * ## 设计要点
 * - 使用 bundled 模型 `com.google.mlkit:face-detection`（离线、无需 GMS）；
 * - FAST 模式 + 关地标/分类，480px 上限，单次约 15–40ms；
 * - **限频 + 缓存**：默认每 250ms 最多跑一次，其余帧返回上次结果
 *   （引导指令不需要 30fps，且管线本身 500ms–1s 抽帧）；
 * - **坐标系**：在「已转正 + (前摄)已镜像」的 Bitmap 上检测，
 *   返回的 NormalizedBox 即显示坐标系，渲染层直接用；
 * - 同步阻塞用 `Tasks.await(timeout)`，在推理工作线程调用（禁止主线程）。
 *
 * ## 为什么只检测人脸
 * v9 模板库中 portrait 类模板 97/728，人像是最需要「主体 vs 目标区」引导的
 * 场景；非人像模板没有可靠主体定义，保持 NONE 比乱指更诚实（docs/10 口径）。
 * 后续可扩展 ML Kit 主体分割（play-services-mlkit-subject-segmentation）。
 */
@Singleton
class FaceSubjectDetector @Inject constructor() : Closeable {

    // 检测策略参数（常量集中此处，Hilt 注入要求无参构造）
    private val minIntervalMs: Long = 250L
    private val detectTimeoutMs: Long = 350L
    private val maxImageSide: Int = 480

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        // 0.06：中远距离人像（脸占画面 6%）也能检出 — 自动变焦依赖该信号
        .setMinFaceSize(0.06f)
        .build()

    private val detector: FaceDetector = FaceDetection.getClient(options)

    @Volatile
    private var lastRunAt: Long = 0L

    @Volatile
    private var lastBox: NormalizedBox? = null

    /** 最近一次的检测耗时（ms，性能面板用） */
    @Volatile
    var lastDetectMs: Long = 0
        private set

    /**
     * 按限频策略检测主体框。
     *
     * @param imageProxy 当前帧（在推理工作线程调用；函数内部会自行转正/镜像）
     * @param isFrontCamera 是否前摄（前摄需镜像到显示坐标系）
     * @return 显示坐标系的归一化主体框；未到检测间隔时返回缓存；无脸返回 null
     */
    fun detectIfDue(imageProxy: ImageProxy, isFrontCamera: Boolean): NormalizedBox? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRunAt < minIntervalMs) return lastBox
        lastRunAt = now

        val start = now
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            // 1. 构造显示坐标系 Bitmap（转正 + 前摄镜像，与预处理同口径）
            var bmp = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) bmp = rotate(bmp, rotation)
            if (isFrontCamera) bmp = mirror(bmp)
            bitmap = bmp

            // 2. 缩放到检测分辨率
            scaled = scaleToMaxSide(bmp, maxImageSide)

            // 3. ML Kit 检测（rotation=0：bitmap 已转正，坐标即显示坐标）
            val image = InputImage.fromBitmap(scaled, 0)
            val faces = Tasks.await(detector.process(image), detectTimeoutMs, TimeUnit.MILLISECONDS)

            // 4. 取最大人脸 → 归一化
            val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            lastBox = best?.let { face ->
                val w = scaled.width.toFloat()
                val h = scaled.height.toFloat()
                NormalizedBox(
                    left = (face.boundingBox.left / w).coerceIn(0f, 1f),
                    top = (face.boundingBox.top / h).coerceIn(0f, 1f),
                    right = (face.boundingBox.right / w).coerceIn(0f, 1f),
                    bottom = (face.boundingBox.bottom / h).coerceIn(0f, 1f)
                )
            }
            return lastBox
        } catch (e: Exception) {
            Log.w(TAG, "人脸检测失败: ${e.message}")
            return lastBox // 返回缓存，链路不中断
        } finally {
            lastDetectMs = android.os.SystemClock.elapsedRealtime() - start
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            bitmap?.recycle()
        }
    }

    /** 清空缓存（切换摄像头后调用，避免旧朝向的框残留） */
    fun resetCache() {
        lastBox = null
        lastRunAt = 0L
    }

    override fun close() {
        try { detector.close() } catch (_: Exception) {}
    }

    // ── Bitmap 变换（与 FramePreprocessor 同口径） ─────────────

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    private fun mirror(bitmap: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    private fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxCurrent = maxOf(w, h)
        if (maxCurrent <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxCurrent
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    companion object {
        private const val TAG = "FaceSubjectDetector"
    }
}
