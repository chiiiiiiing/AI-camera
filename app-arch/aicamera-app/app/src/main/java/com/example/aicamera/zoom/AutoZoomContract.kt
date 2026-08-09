package com.example.aicamera.zoom

/** Versioned, device-independent contract produced only by the real V9 path. */
object AutoZoomContract {
    const val VERSION = 1
    const val MIN_SUGGESTED_RATIO = 1.0f
    const val MAX_SUGGESTED_RATIO = 2.5f
}

enum class AutoZoomScene {
    PORTRAIT,
    FOOD,
    STILL_LIFE,
    NATURAL_LANDSCAPE,
    URBAN_LANDSCAPE,
    UNKNOWN
}

enum class ZoomFraming {
    WIDE,
    MEDIUM,
    CLOSE
}

/** Explainable evidence and safety decisions used by [AutoZoomRecommendation]. */
enum class AutoZoomReason {
    PORTRAIT,
    FOOD,
    STILL_LIFE,
    NATURAL_LANDSCAPE,
    URBAN_LANDSCAPE,
    CLOSE_DISTANCE,
    MEDIUM_DISTANCE,
    WIDE_DISTANCE,
    DAYLIGHT_METADATA,
    LOW_LIGHT_SAFETY,
    HIGHLIGHT_SAFETY,
    LOW_MODEL_MARGIN,
    CONFLICTING_SCENE,
    EMPTY_CANDIDATES,
    UNKNOWN_METADATA,
    INVALID_SCORE,
    STATS_UNAVAILABLE,
    LOW_CONFIDENCE,
    FALLBACK
}

/**
 * A device-independent zoom intention. Consumers must clamp [suggestedZoomRatio] to the active
 * camera's supported range before applying it. The engine itself never controls CameraX.
 */
data class AutoZoomRecommendation(
    val contractVersion: Int = AutoZoomContract.VERSION,
    val scene: AutoZoomScene,
    val framing: ZoomFraming,
    val suggestedZoomRatio: Float,
    val confidence: Float,
    val sourceTemplateId: String?,
    val isStable: Boolean,
    val shouldAutoZoom: Boolean,
    val reasons: List<AutoZoomReason>
)

object AutoZoomRecommendations {
    fun disabled(
        reason: AutoZoomReason = AutoZoomReason.FALLBACK,
        sourceTemplateId: String? = null,
        isStable: Boolean = true
    ): AutoZoomRecommendation = AutoZoomRecommendation(
        scene = AutoZoomScene.UNKNOWN,
        framing = ZoomFraming.WIDE,
        suggestedZoomRatio = AutoZoomContract.MIN_SUGGESTED_RATIO,
        confidence = 0f,
        sourceTemplateId = sourceTemplateId,
        isStable = isStable,
        shouldAutoZoom = false,
        reasons = listOf(reason)
    )
}

internal fun AutoZoomRecommendation.enforceContract(): AutoZoomRecommendation {
    val numericInputIsValid = suggestedZoomRatio.isFinite() && confidence.isFinite()
    val safeReasons = reasons.distinct().ifEmpty { listOf(AutoZoomReason.FALLBACK) }.toMutableList()
    if (!numericInputIsValid && AutoZoomReason.FALLBACK !in safeReasons) {
        safeReasons += AutoZoomReason.FALLBACK
    }
    return copy(
        contractVersion = AutoZoomContract.VERSION,
        scene = if (numericInputIsValid) scene else AutoZoomScene.UNKNOWN,
        framing = if (numericInputIsValid) framing else ZoomFraming.WIDE,
        suggestedZoomRatio = suggestedZoomRatio.safeZoomRatio(),
        confidence = confidence.safeUnit(),
        shouldAutoZoom = numericInputIsValid && shouldAutoZoom && scene != AutoZoomScene.UNKNOWN,
        reasons = safeReasons
    )
}

private fun Float.safeZoomRatio(): Float = if (isFinite()) {
    coerceIn(AutoZoomContract.MIN_SUGGESTED_RATIO, AutoZoomContract.MAX_SUGGESTED_RATIO)
} else {
    AutoZoomContract.MIN_SUGGESTED_RATIO
}

private fun Float.safeUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
