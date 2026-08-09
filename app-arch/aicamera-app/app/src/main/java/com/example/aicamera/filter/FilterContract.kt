package com.example.aicamera.filter

object FilterContract {
    const val VERSION = 1
}

object FilterIds {
    const val ORIGINAL = "original"
    const val PORTRAIT_SOFT = "portrait_soft"
    const val FOOD_VIVID = "food_vivid"
    const val STILL_LIFE_CLEAN = "still_life_clean"
    const val LANDSCAPE_CLEAR = "landscape_clear"
    const val GOLDEN_WARM = "golden_warm"
    const val URBAN_CRISP = "urban_crisp"

    val ALL: Set<String> = linkedSetOf(
        ORIGINAL,
        PORTRAIT_SOFT,
        FOOD_VIVID,
        STILL_LIFE_CLEAN,
        LANDSCAPE_CLEAR,
        GOLDEN_WARM,
        URBAN_CRISP
    )

    fun isKnown(filterId: String): Boolean = filterId in ALL
}

enum class FilterReason {
    PORTRAIT,
    FOOD,
    STILL_LIFE,
    NATURAL_LANDSCAPE,
    URBAN_LANDSCAPE,
    GOLDEN_HOUR,
    LOW_CONTRAST,
    HIGH_CONTRAST,
    LOW_SATURATION,
    HIGH_SATURATION,
    LOW_LIGHT_SAFETY,
    HIGHLIGHT_SAFETY,
    LOW_MODEL_MARGIN,
    FALLBACK
}

data class FilterCandidate(
    val filterId: String,
    val score: Float,
    val reasons: List<FilterReason>
)

data class FilterRecommendation(
    val contractVersion: Int = FilterContract.VERSION,
    val selectedFilterId: String,
    val confidence: Float,
    val candidates: List<FilterCandidate>,
    val sourceTemplateId: String?,
    val isStable: Boolean,
    val shouldAutoPreview: Boolean
)

object FilterRecommendations {
    fun original(
        reason: FilterReason = FilterReason.FALLBACK,
        sourceTemplateId: String? = null,
        isStable: Boolean = true
    ): FilterRecommendation = FilterRecommendation(
        selectedFilterId = FilterIds.ORIGINAL,
        confidence = 0f,
        candidates = listOf(
            FilterCandidate(FilterIds.ORIGINAL, 1f, listOf(reason))
        ),
        sourceTemplateId = sourceTemplateId,
        isStable = isStable,
        shouldAutoPreview = false
    )
}
