package com.example.aicamera.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (filter.vignette > 0f) {
                val strength = (filter.vignette / 100f).coerceIn(0f, 0.75f)
                val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        bitmap.width / 2f, bitmap.height / 2f,
                        kotlin.math.hypot(bitmap.width.toFloat(), bitmap.height.toFloat()) * 0.58f,
                        intArrayOf(0x00000000, 0x00000000, (strength * 255).toInt().shl(24)),
                        floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), vignette)
            }
            out
        }
    }
}
