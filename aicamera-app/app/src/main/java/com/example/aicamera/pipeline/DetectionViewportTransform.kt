package com.example.aicamera.pipeline

import com.example.aicamera.guidance.geometry.NormalizedBox
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Exact mapping between the upright preview viewport and its detector letterbox.
 * Rotation and front-camera reflection already happened before this transform.
 */
data class DetectionViewportTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val padLeft: Int,
    val padTop: Int,
    val inputSize: Int = 416
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && resizedWidth > 0 && resizedHeight > 0)
        require(inputSize == 416 && padLeft >= 0 && padTop >= 0)
        require(resizedWidth + padLeft <= inputSize && resizedHeight + padTop <= inputSize)
    }

    fun toViewport(box: NormalizedBox): NormalizedBox? {
        if (!listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() } ||
            box.width <= 0f || box.height <= 0f) return null
        val mapped = NormalizedBox(
            (box.left * inputSize - padLeft) / resizedWidth,
            (box.top * inputSize - padTop) / resizedHeight,
            (box.right * inputSize - padLeft) / resizedWidth,
            (box.bottom * inputSize - padTop) / resizedHeight
        )
        val visible = NormalizedBox(mapped.left.coerceIn(0f, 1f), mapped.top.coerceIn(0f, 1f),
            mapped.right.coerceIn(0f, 1f), mapped.bottom.coerceIn(0f, 1f))
        // Padding is not image evidence. A box mostly in padding cannot become a subject.
        if (visible.width <= 0f || visible.height <= 0f ||
            visible.width * visible.height < .5f * mapped.width * mapped.height) return null
        return visible
    }

    companion object {
        fun forViewport(width: Int, height: Int): DetectionViewportTransform {
            require(width > 0 && height > 0)
            val ratio = 416.0 / max(width, height)
            val w = floor(width * ratio + .5).toInt().coerceIn(1, 416)
            val h = floor(height * ratio + .5).toInt().coerceIn(1, 416)
            return DetectionViewportTransform(width, height, w, h, (416-w)/2, (416-h)/2)
        }
    }
}

/** Pure, deterministic native-resolution sampling shared by preprocessor and JVM tests. */
internal object NativeViewportPixels {
    const val PADDING_PIXEL: Int = -0x8d8d8e // opaque RGB 114,114,114

    fun resize(pixels: IntArray, width: Int, height: Int, transform: DetectionViewportTransform): IntArray {
        require(pixels.size == width * height && width == transform.sourceWidth && height == transform.sourceHeight)
        val outWidth = transform.resizedWidth; val outHeight = transform.resizedHeight
        return IntArray(outWidth * outHeight) { index ->
            val x = index % outWidth; val y = index / outWidth
            val sx = max(0f, (x + .5f) * (width.toFloat()/outWidth) - .5f)
            val sy = max(0f, (y + .5f) * (height.toFloat()/outHeight) - .5f)
            val x0 = sx.toInt().coerceAtMost(width-1); val y0 = sy.toInt().coerceAtMost(height-1)
            val x1 = min(x0+1,width-1); val y1 = min(y0+1,height-1)
            // Python's float32 coordinates minus int32 indices promote the
            // interpolation weights to float64. Match that intermediate
            // precision, then cast the final sample to float32 before rounding.
            // All-float arithmetic changes a pixel at some exact .5 boundaries.
            val wx = sx.toDouble()-x0; val wy = sy.toDouble()-y0
            var pixel = -0x1000000
            for (channel in 0..2) {
                fun value(px: Int, py: Int) = ((pixels[py*width+px] ushr (8*channel)) and 255).toDouble()
                val top = value(x0,y0)*(1-wx)+value(x1,y0)*wx
                val bottom = value(x0,y1)*(1-wx)+value(x1,y1)*wx
                val value = floor((top*(1-wy)+bottom*wy).toFloat()+.5f).toInt().coerceIn(0,255)
                pixel = pixel or (value shl (8*channel))
            }
            pixel
        }
    }

    fun letterbox(resizedPixels: IntArray, transform: DetectionViewportTransform): IntArray {
        require(resizedPixels.size == transform.resizedWidth * transform.resizedHeight)
        return IntArray(transform.inputSize*transform.inputSize) { PADDING_PIXEL }.also { output ->
            for (row in 0 until transform.resizedHeight) {
                resizedPixels.copyInto(output, (row+transform.padTop)*transform.inputSize+transform.padLeft,
                    row*transform.resizedWidth, (row+1)*transform.resizedWidth)
            }
        }
    }
}
