package com.example.aicamera.filter

import android.graphics.ColorMatrix
import android.util.Base64
import org.json.JSONObject
import java.util.UUID

data class CustomFilterConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "我的滤镜",
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val sharpness: Float = 0f,
    val vignette: Float = 0f
) {
    fun colorMatrix(): ColorMatrix {
        val exposureScale = Math.pow(2.0, exposure.toDouble()).toFloat()
        val contrastScale = 1f + contrast / 100f + sharpness / 500f
        val offset = (1f - contrastScale) * 127.5f
        val warm = temperature / 100f
        val magenta = tint / 100f
        val matrix = ColorMatrix().apply { setSaturation((1f + saturation / 100f).coerceIn(0f, 2f)) }
        matrix.postConcat(ColorMatrix(floatArrayOf(
            exposureScale * contrastScale * (1f + red / 100f + warm * 0.12f), 0f, 0f, 0f, offset + shadows * 0.22f + highlights * 0.06f,
            0f, exposureScale * contrastScale * (1f + green / 100f - magenta * 0.08f), 0f, 0f, offset + shadows * 0.22f + highlights * 0.06f,
            0f, 0f, exposureScale * contrastScale * (1f + blue / 100f - warm * 0.12f), 0f, offset + shadows * 0.18f + highlights * 0.05f,
            0f, 0f, 0f, 1f, 0f
        )))
        return matrix
    }

    fun shareCode(): String {
        val json = JSONObject().apply {
            put("id", id); put("name", name); put("exposure", exposure); put("contrast", contrast)
            put("highlights", highlights); put("shadows", shadows); put("saturation", saturation)
            put("temperature", temperature); put("tint", tint); put("red", red); put("green", green)
            put("blue", blue); put("sharpness", sharpness); put("vignette", vignette)
        }.toString()
        return PREFIX + Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun communityText(): String = "🎨 滤镜资源｜$name\n${shareCode()}"

    companion object {
        const val PREFIX = "GWFLT1:"
        val COMMUNITY_SAMPLE = CustomFilterConfig(
            id = "community-rain-cinema", name = "雨后电影感", exposure = -0.12f,
            contrast = 18f, highlights = -24f, shadows = 14f, saturation = -8f,
            temperature = -10f, tint = 5f, blue = 8f, sharpness = 18f, vignette = 16f
        )

        fun fromText(text: String): CustomFilterConfig? = runCatching {
            val code = text.substringAfter(PREFIX, "").lineSequence().first().trim()
            if (code.isBlank()) return null
            val o = JSONObject(String(Base64.decode(code, Base64.NO_WRAP or Base64.URL_SAFE)))
            CustomFilterConfig(
                id = o.optString("id", UUID.randomUUID().toString()), name = o.optString("name", "社区滤镜"),
                exposure = o.optDouble("exposure").toFloat(), contrast = o.optDouble("contrast").toFloat(),
                highlights = o.optDouble("highlights").toFloat(), shadows = o.optDouble("shadows").toFloat(),
                saturation = o.optDouble("saturation").toFloat(), temperature = o.optDouble("temperature").toFloat(),
                tint = o.optDouble("tint").toFloat(), red = o.optDouble("red").toFloat(),
                green = o.optDouble("green").toFloat(), blue = o.optDouble("blue").toFloat(),
                sharpness = o.optDouble("sharpness").toFloat(), vignette = o.optDouble("vignette").toFloat()
            )
        }.getOrNull()
    }
}
