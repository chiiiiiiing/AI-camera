package com.example.aicamera.filter

import android.graphics.ColorMatrix
import androidx.annotation.StringRes
import com.example.aicamera.R

/**
 * 系统预设滤镜 — 用户手动选择，拍摄后对 Bitmap 应用 [ColorMatrix]。
 *
 * 使用 enum 而非 sealed class + data object，避免低端机型/特殊 ROM 上
 * 嵌套单例初始化顺序异常导致列表中出现 null 而崩溃。
 */
enum class PresetFilter(
    val id: String,
    @StringRes val nameRes: Int,
    val colorMatrix: ColorMatrix
) {
    Original(
        FilterIds.ORIGINAL,
        R.string.filter_original,
        ColorMatrix()
    ),

    /** 人像柔和：降饱和、略降对比 */
    PortraitSoft(
        FilterIds.PORTRAIT_SOFT,
        R.string.filter_portrait_soft,
        ColorMatrix().apply {
            setSaturation(0.94f)
            postConcat(contrastMatrix(0.97f))
            postConcat(rgbScaleMatrix(r = 1.03f, g = 1.0f, b = 0.98f))
            postConcat(brightnessMatrix(1.02f))
        }
    ),

    /** 美食鲜艳：提升饱和，红/绿通道略提亮 */
    FoodVivid(
        FilterIds.FOOD_VIVID,
        R.string.filter_food_vivid,
        ColorMatrix().apply {
            setSaturation(1.12f)
            postConcat(rgbScaleMatrix(r = 1.04f, g = 1.025f, b = 0.97f))
            postConcat(contrastMatrix(1.04f))
        }
    ),

    /** 静物干净：亮度 +10%，轻微提升对比 */
    StillLifeClean(
        FilterIds.STILL_LIFE_CLEAN,
        R.string.filter_still_life_clean,
        ColorMatrix().apply {
            setSaturation(0.97f)
            postConcat(brightnessMatrix(1.04f))
            postConcat(contrastMatrix(1.06f))
        }
    ),

    /** 风景通透：提升饱和，蓝/青通道增强 */
    LandscapeClear(
        FilterIds.LANDSCAPE_CLEAR,
        R.string.filter_landscape_clear,
        ColorMatrix().apply {
            setSaturation(1.08f)
            postConcat(rgbScaleMatrix(r = 0.99f, g = 1.025f, b = 1.04f))
            postConcat(contrastMatrix(1.035f))
        }
    ),

    /** 金色暖调：红/绿提升、蓝降低 */
    GoldenWarm(
        FilterIds.GOLDEN_WARM,
        R.string.filter_golden_warm,
        ColorMatrix().apply {
            setSaturation(0.98f)
            postConcat(rgbScaleMatrix(r = 1.05f, g = 1.015f, b = 0.94f))
            postConcat(brightnessMatrix(1.02f))
        }
    ),

    /** 城市冷锐：增强对比，轻微降饱和 */
    UrbanCrisp(
        FilterIds.URBAN_CRISP,
        R.string.filter_urban_crisp,
        ColorMatrix().apply {
            setSaturation(0.92f)
            postConcat(contrastMatrix(1.08f))
            postConcat(rgbScaleMatrix(r = 0.97f, g = 1.0f, b = 1.045f))
        }
    );

    companion object {
        /** 所有预设，原图放在第一位 */
        val ALL: List<PresetFilter> = entries.toList()

        fun fromId(id: String): PresetFilter = entries.find { it.id == id } ?: Original
    }
}

/** 对比度矩阵：scale > 1 增强，< 1 降低 */
private fun contrastMatrix(scale: Float): ColorMatrix {
    val translate = (1f - scale) * 0.5f * 255f
    return ColorMatrix(
        floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

/** 亮度矩阵：scale > 1 提亮 */
private fun brightnessMatrix(scale: Float): ColorMatrix {
    return ColorMatrix(
        floatArrayOf(
            scale, 0f, 0f, 0f, 0f,
            0f, scale, 0f, 0f, 0f,
            0f, 0f, scale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

/** RGB 通道独立缩放 */
private fun rgbScaleMatrix(r: Float, g: Float, b: Float): ColorMatrix {
    return ColorMatrix(
        floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}
