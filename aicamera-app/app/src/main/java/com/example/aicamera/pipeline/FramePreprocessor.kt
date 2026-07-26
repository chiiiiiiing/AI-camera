package com.example.aicamera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.aicamera.camera.CameraConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 帧预处理器 — YUV → RGB → Float Tensor。
 *
 * ## 处理流程
 * ```
 * ImageProxy (YUV_420_888)
 *     │
 *     ├─ 方案 A: imageProxy.toBitmap()  → RGB Bitmap  → Float ByteBuffer
 *     │   优点: 简单，CameraX 内置
 *     │   缺点: 多一次内存拷贝
 *     │
 *     └─ 方案 B: YUV Plane 直接转 ByteBuffer（优化方向，V1.1 实现）
 *         优点: 少一次 Bitmap 分配
 *         缺点: 需手动处理 YUV 420 的 U/V 子采样
 *
 * MVP 阶段使用方案 A。V1.1 性能优化时切到方案 B。
 * ```
 *
 * ## Buffer 生命周期约定
 * ★ 返回的 PreprocessedFrame.buffer 仅在当前 IInference.predict() 同步调用期间有效。
 *   不允许缓存到异步线程或跨帧持有。未来接入异步推理时需改用 BufferPool。
 */
class FramePreprocessor(
    /** 模型输入边长（正方形，如 320 表示 320×320） */
    private var targetSize: Int = 320
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
     * @return 预处理后的帧数据（ByteBuffer + 元信息）
     */
    fun process(imageProxy: ImageProxy, rotation: Int = 0): PreprocessedFrame {
        val startTime = SystemClock.elapsedRealtime()

        // Step 1: YUV → Bitmap
        val bitmap = imageProxy.toBitmap()

        // Step 2: Resize → 模型尺寸
        val resized = if (bitmap.width != targetSize || bitmap.height != targetSize) {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        } else {
            bitmap
        }

        // Step 3: 像素 → Float ByteBuffer
        fillFloatBuffer(resized)

        // Step 4: 只释放原始 bitmap，保留 resized 供下游推理器使用
        if (resized !== bitmap) bitmap.recycle()

        lastPreprocessMs = SystemClock.elapsedRealtime() - startTime

        return PreprocessedFrame(
            buffer = reusableBuffer,
            width = targetSize,
            height = targetSize,
            channels = 3,
            rotation = rotation,
            originalWidth = imageProxy.width,
            originalHeight = imageProxy.height,
            originalBitmap = resized  // ★ 保留 bitmap 不 recycle，由下游推理器决定何时回收
        )
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

    // ── 旋转处理 ──────────────────────────────────────────────

    /**
     * 根据旋转角度旋转 Bitmap。
     * 等模型输入规格确认后决定二选一：
     * - 模型要求正方向输入 → 预处理阶段旋转
     * - 模型支持 rotation 元信息 → 不旋转，模型输出坐标需声明坐标系
     */
    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix = android.graphics.Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        if (rotated !== bitmap) bitmap.recycle()
        return rotated
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
    val rotation: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    /** ★ 原始尺寸 Bitmap（仅 resize，未 recycle），供 V9 等需要不同尺寸的推理器使用 */
    val originalBitmap: Bitmap? = null
)

// ── 日志 ──────────────────────────────────────────────────────

private fun logInfo(msg: String) = Log.i(CameraConfig.TAG, "[Preprocess] $msg")
