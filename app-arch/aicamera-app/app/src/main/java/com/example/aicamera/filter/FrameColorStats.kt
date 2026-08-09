package com.example.aicamera.filter

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FrameColorStats(
    val meanLuminance: Float,
    val luminanceStdDev: Float,
    val meanSaturation: Float,
    /** Normalized red-minus-blue estimate in [-1, 1]. */
    val warmth: Float,
    val shadowClipRatio: Float,
    val highlightClipRatio: Float
) {
    fun sanitized(): FrameColorStats = FrameColorStats(
        meanLuminance = meanLuminance.unitOrZero(),
        luminanceStdDev = luminanceStdDev.unitOrZero(),
        meanSaturation = meanSaturation.unitOrZero(),
        warmth = warmth.finiteOrZero().coerceIn(-1f, 1f),
        shadowClipRatio = shadowClipRatio.unitOrZero(),
        highlightClipRatio = highlightClipRatio.unitOrZero()
    )

    companion object {
        val NEUTRAL = FrameColorStats(
            meanLuminance = 0.5f,
            luminanceStdDev = 0f,
            meanSaturation = 0f,
            warmth = 0f,
            shadowClipRatio = 0f,
            highlightClipRatio = 0f
        )
    }
}

/**
 * Samples the already-oriented 224px inference bitmap. The extractor never retains the bitmap
 * and does not allocate another bitmap or a full-frame pixel array.
 */
class FrameColorStatsExtractor(
    private val sampleStride: Int = 4
) {
    /** One sampled row only; never a full-frame pixel copy. V9 calls this on one inference thread. */
    private var rowBuffer = IntArray(0)

    init {
        require(sampleStride > 0) { "sampleStride must be positive" }
    }

    fun extract(bitmap: Bitmap): FrameColorStats {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
            return FrameColorStats.NEUTRAL
        }
        if (rowBuffer.size < bitmap.width) rowBuffer = IntArray(bitmap.width)
        val accumulator = ColorAccumulator()
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(rowBuffer, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                accumulator.add(rowBuffer[x])
                x += sampleStride
            }
            y += sampleStride
        }
        return accumulator.result()
    }

    companion object {
        /** Pure JVM-testable core. Invalid dimensions and empty input return neutral finite stats. */
        fun extractPixels(
            pixels: IntArray,
            width: Int,
            height: Int,
            sampleStride: Int = 1
        ): FrameColorStats {
            if (width <= 0 || height <= 0 || sampleStride <= 0 || pixels.isEmpty()) {
                return FrameColorStats.NEUTRAL
            }
            val availableHeight = min(height, pixels.size / width)
            if (availableHeight <= 0) return FrameColorStats.NEUTRAL

            val accumulator = ColorAccumulator()
            var y = 0
            while (y < availableHeight) {
                var x = 0
                while (x < width) {
                    val index = y * width + x
                    if (index >= pixels.size) break
                    accumulator.add(pixels[index])
                    x += sampleStride
                }
                y += sampleStride
            }
            return accumulator.result()
        }
    }
}

private class ColorAccumulator {
    private var count = 0
    private var luminanceSum = 0.0
    private var luminanceSquareSum = 0.0
    private var saturationSum = 0.0
    private var redSum = 0.0
    private var blueSum = 0.0
    private var shadowCount = 0
    private var highlightCount = 0

    fun add(pixel: Int) {
        val red = ((pixel ushr 16) and 0xff) / 255.0
        val green = ((pixel ushr 8) and 0xff) / 255.0
        val blue = (pixel and 0xff) / 255.0
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        val saturation = if (maxChannel > 0.0) (maxChannel - minChannel) / maxChannel else 0.0

        count++
        luminanceSum += luminance
        luminanceSquareSum += luminance * luminance
        saturationSum += saturation
        redSum += red
        blueSum += blue
        if (luminance <= SHADOW_CLIP_LIMIT) shadowCount++
        if (luminance >= HIGHLIGHT_CLIP_LIMIT) highlightCount++
    }

    fun result(): FrameColorStats {
        if (count == 0) return FrameColorStats.NEUTRAL
        val divisor = count.toDouble()
        val mean = luminanceSum / divisor
        val variance = max(0.0, luminanceSquareSum / divisor - mean * mean)
        return FrameColorStats(
            meanLuminance = mean.toFloat(),
            luminanceStdDev = sqrt(variance).toFloat(),
            meanSaturation = (saturationSum / divisor).toFloat(),
            warmth = ((redSum - blueSum) / divisor).toFloat(),
            shadowClipRatio = (shadowCount / divisor).toFloat(),
            highlightClipRatio = (highlightCount / divisor).toFloat()
        ).sanitized()
    }

    companion object {
        private const val SHADOW_CLIP_LIMIT = 0.04
        private const val HIGHLIGHT_CLIP_LIMIT = 0.96
    }
}

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f
private fun Float.unitOrZero(): Float = finiteOrZero().coerceIn(0f, 1f)
