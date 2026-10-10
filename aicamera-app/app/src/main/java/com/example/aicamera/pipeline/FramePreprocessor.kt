package com.example.aicamera.pipeline

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.aicamera.camera.CameraConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 帧预处理器 — YUV → 转正 → (前摄)镜像 → RGB → Float Tensor。
 *
 * ## 处理流程（与 v9 接入协议严格对齐，见 docs/09、10）
 * ```
 * ImageProxy (YUV_420_888)
 *     │
 *     ├─ imageProxy.toBitmap()       YUV → RGB Bitmap
 *     ├─ rotateBitmap(rotation)      按 rotationDegrees 转正（模型要求正向输入）
 *     ├─ mirrorBitmap()              前摄：镜像到与预览画面一致
 *     ├─ resize → 224×224
 *     └─ fillFloatBuffer             RGB [0,1] float32
 * ```
 *
 * ## Buffer 生命周期约定
 * ★ 返回的 PreprocessedFrame.buffer 仅在当前 IInference.predict() 同步调用期间有效。
 *   不允许缓存到异步线程或跨帧持有。未来接入异步推理时需改用 BufferPool。
 *
 * ★ originalBitmap（224×224 已转正）由调用方在推理完成后回收（FramePipeline 已用
 *   try/finally 保证），供 V9 推理器直接做 NCHW 预处理，避免二次解码。
 */
class FramePreprocessor(
    /** 模型输入边长（正方形，V9 为 224） */
    private var targetSize: Int = 224
) {
    /** 复用 ByteBuffer，避免每帧重新分配 */
    private var reusableBuffer: ByteBuffer = allocateBuffer(targetSize)

    /** ★ 复用像素数组，避免每帧分配 IntArray */
    private var reusablePixels: IntArray = IntArray(targetSize * targetSize)

    /** 最近一次预处理的耗时（ms） */
    var lastPreprocessMs: Long = 0
        private set

    // ── 公开 API ──────────────────────────────────────────────

    /**
     * 将 ImageProxy 帧预处理为模型输入的 PreprocessedFrame。
     *
     * ★ buffer 生命周期：仅在当前 inference.predict() 同步调用期间有效。
     *
     * @param imageProxy CameraX 帧
     * @param rotation 旋转角度（来自 imageProxy.imageInfo.rotationDegrees）
     * @param isFrontCamera 是否前置摄像头（前置需镜像使模型输入与显示画面一致）
     * @return 预处理后的帧数据（ByteBuffer + 元信息）
     */
    fun process(
        imageProxy: ImageProxy,
        rotation: Int = 0,
        isFrontCamera: Boolean = false
    ): PreprocessedFrame {
        val startTime = SystemClock.elapsedRealtime()

        var bitmap: Bitmap? = null
        try {
            // Step 1: YUV → Bitmap
            bitmap = imageProxy.toBitmap()

            // Step 2: 按 rotationDegrees 转正（v9 协议：模型期望正向图）
            if (rotation != 0) bitmap = rotateBitmap(requireNotNull(bitmap), rotation)

            // Step 3: 前摄镜像（协议：模型输入与显示画面保持一致）
            if (isFrontCamera) bitmap = mirrorBitmap(requireNotNull(bitmap))

            // Step 4: Resize → 模型尺寸。current variable owns exactly one bitmap.
            val current = requireNotNull(bitmap)
            if (current.width != targetSize || current.height != targetSize) {
                val resized = Bitmap.createScaledBitmap(current, targetSize, targetSize, true)
                if (resized !== current) current.recycle()
                bitmap = resized
            }

            // Step 5: 像素 → Float ByteBuffer
            fillFloatBuffer(requireNotNull(bitmap))

            lastPreprocessMs = SystemClock.elapsedRealtime() - startTime
            return PreprocessedFrame(
                buffer = reusableBuffer,
                width = targetSize,
                height = targetSize,
                channels = 3,
                // 帧已转正/镜像，下游无需再做旋转变换
                rotation = 0,
                originalWidth = imageProxy.width,
                originalHeight = imageProxy.height,
                // Ownership transfers to FramePipeline, whose finally block recycles it.
                originalBitmap = bitmap
            )
        } catch (e: Exception) {
            // If construction fails, no PreprocessedFrame reaches FramePipeline to own this bitmap.
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            throw e
        }
    }

    // ── 像素填充 ──────────────────────────────────────────────

    /**
     * 将 Bitmap 像素逐行逐列填入 Float ByteBuffer。
     * 归一化至 [0.0, 1.0]，通道顺序 RGB。
     */
    private fun fillFloatBuffer(bitmap: Bitmap) {
        reusableBuffer.rewind()

        // ★ 复用像素数组
        val pixels = reusablePixels
        bitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        for (pixel in pixels) {
            reusableBuffer.putFloat(Color.red(pixel) / 255.0f)
            reusableBuffer.putFloat(Color.green(pixel) / 255.0f)
            reusableBuffer.putFloat(Color.blue(pixel) / 255.0f)
        }

        reusableBuffer.rewind()
    }

    // ── 旋转/镜像 ─────────────────────────────────────────────

    /**
     * 根据旋转角度旋转 Bitmap（回收原图，返回旋转后的图）。
     */
    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * 水平镜像 Bitmap（回收原图，返回镜像后的图）。
     * 前摄 ImageAnalysis 帧不自动镜像，而 PreviewView 显示的是镜像画面，
     * 模型输入需与显示画面一致（v9 协议 front_camera 条目）。
     */
    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        val mirrored = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (mirrored !== bitmap) bitmap.recycle()
        return mirrored
    }

    // ── 配置 ──────────────────────────────────────────────────

    /** ★ 真正更新模型输入尺寸，重新分配 buffer 和像素数组 */
    fun updateTargetSize(newSize: Int) {
        if (newSize == targetSize) return
        logInfo("目标尺寸更新: $targetSize → $newSize")
        targetSize = newSize
        reusableBuffer = allocateBuffer(newSize)
        reusablePixels = IntArray(newSize * newSize)
    }

    private fun allocateBuffer(size: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(4 * size * size * 3)
            .apply { order(ByteOrder.nativeOrder()) }
    }
}

/**
 * 预处理后的帧数据，可直接送入模型推理。
 *
 * ★ buffer 生命周期：仅在当前 inference.predict() 同步调用期间有效。
 */
data class PreprocessedFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val channels: Int,
    /** 预处理阶段已转正/镜像，此处恒为 0（下游无需再做坐标旋转） */
    val rotation: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    /** 已转正/镜像并 resize 到模型尺寸的 Bitmap（未 recycle），供 V9 直接做 NCHW 预处理 */
    val originalBitmap: Bitmap? = null
)

// ── 日志 ──────────────────────────────────────────────────────

private fun logInfo(msg: String) = Log.i(CameraConfig.TAG, "[Preprocess] $msg")
