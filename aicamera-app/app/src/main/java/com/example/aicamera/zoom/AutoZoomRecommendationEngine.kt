package com.example.aicamera.zoom

import com.example.aicamera.filter.FrameColorStats
import com.example.aicamera.pipeline.SearchHit
import com.example.aicamera.pipeline.TemplateMeta

data class AutoZoomRecommendationConfig(
    val rankWeights: List<Float> = listOf(1f, 0.84f, 0.70f, 0.58f, 0.48f),
    val sceneCategoryWeight: Float = 1f,
    val photoTypeWeight: Float = 0.28f,
    val cameraDistanceWeight: Float = 0.20f,
    val timeOfDayWeight: Float = 0.08f,
    val lowLightMeanThreshold: Float = 0.14f,
    val severeShadowClipThreshold: Float = 0.45f,
    val severeHighlightClipThreshold: Float = 0.18f,
    val lowModelMarginThreshold: Float = 0.008f,
    val conflictingEvidenceShare: Float = 0.46f,
    val conflictingEvidenceGap: Float = 0.12f,
    val minimumAutoZoomConfidence: Float = 0.55f
)

/** Pure Kotlin V9 metadata + frame-stat decision engine. */
class AutoZoomRecommendationEngine(
    private val config: AutoZoomRecommendationConfig = AutoZoomRecommendationConfig()
) {
    init {
        require(config.rankWeights.isNotEmpty()) { "rankWeights must not be empty" }
    }

    fun recommend(
        rerankedTop5: List<SearchHit>,
        frameStats: FrameColorStats
    ): AutoZoomRecommendation {
        val hits = rerankedTop5.take(config.rankWeights.size)
        val sourceTemplateId = hits.firstOrNull { it.templateId.isNotBlank() }?.templateId
        if (hits.isEmpty()) return AutoZoomRecommendations.disabled(AutoZoomReason.EMPTY_CANDIDATES)
        if (hits.any { !it.cosine.isFinite() || !it.rerankScore.isFinite() }) {
            return AutoZoomRecommendations.disabled(AutoZoomReason.INVALID_SCORE, sourceTemplateId)
        }
        if (hits.none { it.metadata != null }) {
            return AutoZoomRecommendations.disabled(AutoZoomReason.UNKNOWN_METADATA, sourceTemplateId)
        }

        val stats = frameStats.sanitized()
        val votes = linkedMapOf<ZoomTarget, Float>()
        val reasons = linkedSetOf<AutoZoomReason>()
        hits.forEachIndexed { index, hit ->
            val meta = hit.metadata ?: return@forEachIndexed
            val rankWeight = config.rankWeights[index].finitePositive()
            if (rankWeight == 0f) return@forEachIndexed
            applyMetadataVotes(meta, rankWeight, votes, reasons)
        }

        val selected = votes.maxByOrNull { it.value.finitePositive() }
            ?: return AutoZoomRecommendations.disabled(AutoZoomReason.UNKNOWN_METADATA, sourceTemplateId)
        val totalEvidence = votes.values.sumOf { it.finitePositive().toDouble() }.toFloat()
        val selectedEvidence = selected.value.finitePositive()
        val confidence = if (totalEvidence > 0f) (selectedEvidence / totalEvidence).safeUnit() else 0f
        val safetyReason = safetyReason(hits, stats, votes)
        if (safetyReason != null) {
            return AutoZoomRecommendations.disabled(safetyReason, sourceTemplateId)
        }
        if (confidence < config.minimumAutoZoomConfidence) {
            return AutoZoomRecommendations.disabled(AutoZoomReason.LOW_CONFIDENCE, sourceTemplateId)
        }

        return AutoZoomRecommendation(
            scene = selected.key.scene,
            framing = selected.key.framing,
            suggestedZoomRatio = selected.key.ratio,
            confidence = confidence,
            sourceTemplateId = sourceTemplateId,
            isStable = false,
            shouldAutoZoom = true,
            reasons = reasons.ifEmpty { linkedSetOf(AutoZoomReason.FALLBACK) }.toList()
        ).enforceContract()
    }

    private fun applyMetadataVotes(
        meta: TemplateMeta,
        rankWeight: Float,
        votes: MutableMap<ZoomTarget, Float>,
        reasons: MutableSet<AutoZoomReason>
    ) {
        val target = targetFor(meta) ?: return
        addVote(votes, target, rankWeight * config.sceneCategoryWeight)
        reasons += target.reason

        if (photoTypeSupports(meta, target)) {
            addVote(votes, target, rankWeight * config.photoTypeWeight)
        }
        if (distanceSupports(meta.cameraDistance, target.framing)) {
            addVote(votes, target, rankWeight * config.cameraDistanceWeight)
            reasons += distanceReason(meta.cameraDistance)
        }
        if (meta.timeOfDay in DAYLIGHT_TIME_OF_DAY) {
            addVote(votes, target, rankWeight * config.timeOfDayWeight)
            reasons += AutoZoomReason.DAYLIGHT_METADATA
        }
    }

    private fun targetFor(meta: TemplateMeta): ZoomTarget? = when (meta.sceneCategory) {
        "closeup_portrait" -> ZoomTarget(AutoZoomScene.PORTRAIT, ZoomFraming.CLOSE, 1.6f, AutoZoomReason.PORTRAIT)
        "food_still_life" -> ZoomTarget(AutoZoomScene.FOOD, ZoomFraming.CLOSE, 1.4f, AutoZoomReason.FOOD)
        "lifestyle_still_life", "plant_still_life" ->
            ZoomTarget(AutoZoomScene.STILL_LIFE, ZoomFraming.MEDIUM, 1.2f, AutoZoomReason.STILL_LIFE)
        "natural_landscape" ->
            ZoomTarget(AutoZoomScene.NATURAL_LANDSCAPE, ZoomFraming.WIDE, 1.0f, AutoZoomReason.NATURAL_LANDSCAPE)
        "urban_landscape" ->
            ZoomTarget(AutoZoomScene.URBAN_LANDSCAPE, ZoomFraming.WIDE, 1.0f, AutoZoomReason.URBAN_LANDSCAPE)
        else -> when (meta.photoType) {
            "portrait" -> ZoomTarget(AutoZoomScene.PORTRAIT, ZoomFraming.CLOSE, 1.6f, AutoZoomReason.PORTRAIT)
            "landscape" -> ZoomTarget(AutoZoomScene.NATURAL_LANDSCAPE, ZoomFraming.WIDE, 1.0f, AutoZoomReason.NATURAL_LANDSCAPE)
            "still_life" -> ZoomTarget(AutoZoomScene.STILL_LIFE, ZoomFraming.MEDIUM, 1.2f, AutoZoomReason.STILL_LIFE)
            else -> null
        }
    }

    private fun photoTypeSupports(meta: TemplateMeta, target: ZoomTarget): Boolean = when (target.scene) {
        AutoZoomScene.PORTRAIT -> meta.photoType == "portrait"
        AutoZoomScene.FOOD, AutoZoomScene.STILL_LIFE -> meta.photoType == "still_life"
        AutoZoomScene.NATURAL_LANDSCAPE, AutoZoomScene.URBAN_LANDSCAPE -> meta.photoType == "landscape"
        AutoZoomScene.UNKNOWN -> false
    }

    private fun safetyReason(
        hits: List<SearchHit>,
        stats: FrameColorStats,
        votes: Map<ZoomTarget, Float>
    ): AutoZoomReason? {
        if (stats.meanLuminance <= config.lowLightMeanThreshold ||
            stats.shadowClipRatio >= config.severeShadowClipThreshold
        ) return AutoZoomReason.LOW_LIGHT_SAFETY
        if (stats.highlightClipRatio >= config.severeHighlightClipThreshold) return AutoZoomReason.HIGHLIGHT_SAFETY
        if (modelMarginIsLow(hits)) return AutoZoomReason.LOW_MODEL_MARGIN
        if (evidenceConflicts(votes)) return AutoZoomReason.CONFLICTING_SCENE
        return null
    }

    private fun modelMarginIsLow(hits: List<SearchHit>): Boolean {
        if (hits.size < 2) return false
        return (hits[0].rerankScore - hits[1].rerankScore).coerceAtLeast(0f) < config.lowModelMarginThreshold
    }

    private fun evidenceConflicts(votes: Map<ZoomTarget, Float>): Boolean {
        val sorted = votes.values.map { it.finitePositive() }.filter { it > 0f }.sortedDescending()
        val total = sorted.sum()
        if (sorted.size < 2 || total <= 0f) return false
        return sorted[0] / total < config.conflictingEvidenceShare ||
            (sorted[0] - sorted[1]) / total < config.conflictingEvidenceGap
    }

    private fun addVote(votes: MutableMap<ZoomTarget, Float>, target: ZoomTarget, amount: Float) {
        if (!amount.isFinite() || amount <= 0f) return
        votes[target] = (votes[target] ?: 0f) + amount
    }

    private data class ZoomTarget(
        val scene: AutoZoomScene,
        val framing: ZoomFraming,
        val ratio: Float,
        val reason: AutoZoomReason
    )

    private companion object {
        val DAYLIGHT_TIME_OF_DAY = setOf("day", "golden_hour", "blue_hour", "overcast")
    }
}

private fun distanceSupports(distance: String, framing: ZoomFraming): Boolean = when (framing) {
    ZoomFraming.CLOSE -> distance == "close"
    ZoomFraming.MEDIUM -> distance == "medium"
    ZoomFraming.WIDE -> distance == "wide"
}

private fun distanceReason(distance: String): AutoZoomReason = when (distance) {
    "close" -> AutoZoomReason.CLOSE_DISTANCE
    "medium" -> AutoZoomReason.MEDIUM_DISTANCE
    else -> AutoZoomReason.WIDE_DISTANCE
}

private fun Float.finitePositive(): Float = if (isFinite()) coerceAtLeast(0f) else 0f
private fun Float.safeUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
