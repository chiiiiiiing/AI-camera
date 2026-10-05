package com.example.aicamera.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 使用 [ColorMatrixColorFilter] 对 Bitmap 应用预设滤镜。
 *
 * - 输出为新的可变 Bitmap，不修改输入
 * - 在 [Dispatchers.Default] 执行，避免阻塞主线程
 */
object BitmapFilterApplier {

    /**
     * 对 [bitmap] 应用 [filter]，返回新的 Bitmap。
     *
     * @param mutable 是否需要可变的输出（默认 true）
     */
    suspend fun apply(bitmap: Bitmap, filter: PresetFilter, mutable: Boolean = true): Bitmap {
        return withContext(Dispatchers.Default) {
            val width = bitmap.width
            val height = bitmap.height
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            val out = Bitmap.createBitmap(width, height, config)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(filter.colorMatrix)
            }

            Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)

            if (mutable) out else out.copy(config, false)
        }
    }

    suspend fun apply(bitmap: Bitmap, filter: CustomFilterConfig): Bitmap {
        return withContext(Dispatchers.Default) {
            val width = bitmap.width
            val height = bitmap.height
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (filter.highlights != 0f || filter.shadows != 0f ||
                filter.sharpness != 0f || filter.vignette != 0f) {
                // Scanline processing uses four width-sized buffers instead of two full-image
                // arrays, so a 12 MP captured photo does not need another ~96 MB allocation.
                var previous = IntArray(width)
                var current = IntArray(width)
                var next = IntArray(width)
                val changed = IntArray(width)
                out.getPixels(current, 0, width, 0, 0, width, 1)
                current.copyInto(previous)
                if (height > 1) out.getPixels(next, 0, width, 0, 1, width, 1)
                else current.copyInto(next)
                val centerX = (width - 1) / 2f
                val centerY = (height - 1) / 2f
                val maxRadius = max(1f, hypot(centerX, centerY))
                val sharpness = (filter.sharpness / 100f).coerceIn(0f, 1f) * 0.35f
                val vignette = (filter.vignette / 100f).coerceIn(0f, 1f) * 0.8f
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = current[x]
                        val left = current[max(0, x - 1)]
                        val right = current[if (x + 1 < width) x + 1 else x]
                        val up = previous[x]
                        val down = next[x]
                        val luminance = (Color.red(pixel) * 0.2126f +
                            Color.green(pixel) * 0.7152f + Color.blue(pixel) * 0.0722f) / 255f
                        val shadows = 1f - luminance
                        val toneDelta = filter.shadows * 0.85f * shadows * shadows +
                            filter.highlights * 0.85f * luminance * luminance
                        val radius = hypot(x - centerX, y - centerY) / maxRadius
                        val edge = ((radius - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        val falloff = 1f - vignette * edge * edge
                        fun channel(value: Int, a: Int, b: Int, c: Int, d: Int): Int =
                            ((value + sharpness * (4 * value - a - b - c - d) + toneDelta) * falloff)
                                .roundToInt().coerceIn(0, 255)
                        changed[x] = Color.argb(
                            Color.alpha(pixel),
                            channel(Color.red(pixel), Color.red(left), Color.red(right), Color.red(up), Color.red(down)),
                            channel(Color.green(pixel), Color.green(left), Color.green(right), Color.green(up), Color.green(down)),
                            channel(Color.blue(pixel), Color.blue(left), Color.blue(right), Color.blue(up), Color.blue(down))
                        )
                    }
                    out.setPixels(changed, 0, width, 0, y, width, 1)
                    val recycled = previous
                    previous = current
                    current = next
                    next = recycled
                    if (y + 2 < height) out.getPixels(next, 0, width, 0, y + 2, width, 1)
                    else current.copyInto(next)
                }
            }
            out
        }
    }
}
