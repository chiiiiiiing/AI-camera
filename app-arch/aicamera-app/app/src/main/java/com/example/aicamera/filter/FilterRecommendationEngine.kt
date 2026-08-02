package com.example.aicamera.filter

import com.example.aicamera.pipeline.SearchHit
import com.example.aicamera.pipeline.TemplateMeta
import kotlin.math.max

data class FilterRecommendationConfig(
    val rankWeights: List<Float> = listOf(1f, 0.84f, 0.70f, 0.58f, 0.48f),
    val originalBaseScore: Float = 0.18f,
    val photoTypeSupport: Float = 0.25f,
    val sceneSupport: Float = 1f,
    val goldenHourSupport: Float = 0.62f,
    val lowLightMeanThreshold: Float = 0.14f,
    val severeShadowClipThreshold: Float = 0.45f,
    val severeHighlightClipThreshold: Float = 0.18f,
    val highSaturationThreshold: Float = 0.72f,
    val lowSaturationThreshold: Float = 0.20f,
    val highContrastThreshold: Float = 0.25f,
    val lowContrastThreshold: Float = 0.08f,
    val warmFrameThreshold: Float = 0.08f,
    val coolFrameThreshold: Float = -0.08f,
    val urbanShadowThreshold: Float = 0.22f,
    val lowModelMarginThreshold: Float = 0.008f,
    val conflictingEvidenceShare: Float = 0.46f,
    val conflictingEvidenceGap: Float = 0.12f,
    val autoPreviewConfidence: Float = 0.55f,
    val styleScoreScale: Float = 1.20f,
    val highSaturationFoodMultiplier: Float = 0.34f,
    val lowSaturationFoodMultiplier: Float = 1.08f,
    val highContrastUrbanMultiplier: Float = 0.48f,
    val highContrastPortraitMultiplier: Float = 0.75f,
    val lowContrastLandscapeMultiplier: Float = 1.08f,
    val shadowedUrbanMultiplier: Float = 0.42f,
    val clippedFoodMultiplier: Float = 0.25f,
    val clippedGoldenMultiplier: Float = 0.45f,
    val clippedUrbanMultiplier: Float = 0.45f,
    val warmGoldenMultiplier: Float = 1.08f,
    val coolGoldenMultiplier: Float = 0.78f,
    val originalPriorityBoost: Float = 0.45f,
    val confidenceScoreWeight: Float = 0.82f
)

/** Offline deterministic V9 metadata + frame-color recommendation engine. */
class FilterRecommendationEngine(
    private val config: FilterRecommendationConfig = FilterRecommendationConfig()
) {
    fun recommend(
        rerankedTop5: List<SearchHit>,
        frameStats: FrameColorStats
    ): FilterRecommendation {
        val usableHits = rerankedTop5.take(config.rankWeights.size)
        val sourceTemplateId = usableHits.firstOrNull { it.templateId.isNotBlank() }?.templateId
        if (usableHits.isEmpty() || usableHits.none { it.metadata != null }) {
            return FilterRecommendations.original(sourceTemplateId = sourceTemplateId)
        }

        val stats = frameStats.sanitized()
        val scores = linkedMapOf(FilterIds.ORIGINAL to config.originalBaseScore)
        val reasons = mutableMapOf(FilterIds.ORIGINAL to linkedSetOf(FilterReason.FALLBACK))
        val evidenceByFilter = linkedMapOf<String, Float>()

        applyTemplateVotes(usableHits, scores, reasons, evidenceByFilter)
        val marginIsLow = modelMarginIsLow(usableHits)
        val evidenceConflicts = evidenceConflicts(evidenceByFilter)
        val safety = applyColorSafety(scores, reasons, stats, marginIsLow, evidenceConflicts)

        val candidates = normalizeAndSort(scores, reasons)
        val selected = candidates.firstOrNull()
            ?: return FilterRecommendations.original(sourceTemplateId = sourceTemplateId)
        val runnerUp = candidates.getOrNull(1)?.score ?: 0f
        val scoreWeight = config.confidenceScoreWeight.coerceIn(0f, 1f)
        val confidence = (selected.score * scoreWeight +
            (selected.score - runnerUp).coerceAtLeast(0f) * (1f - scoreWeight))
            .finiteUnit()
        val autoPreview = selected.filterId != FilterIds.ORIGINAL &&
            !safety && !marginIsLow && !evidenceConflicts &&
            confidence >= config.autoPreviewConfidence

        return FilterRecommendation(
            selectedFilterId = selected.filterId,
            confidence = confidence,
            candidates = candidates,
            sourceTemplateId = sourceTemplateId,
            isStable = false,
            shouldAutoPreview = autoPreview
        ).enforceContract()
    }

    internal fun applyTemplateVotes(
        hits: List<SearchHit>,
        scores: MutableMap<String, Float>,
        reasons: MutableMap<String, LinkedHashSet<FilterReason>>,
        evidenceByFilter: MutableMap<String, Float>
    ) {
        hits.forEachIndexed { index, hit ->
            val meta = hit.metadata ?: return@forEachIndexed
            val rankWeight = config.rankWeights.getOrElse(index) { 0f }.finitePositive()
            if (rankWeight == 0f) return@forEachIndexed

            val sceneVote = sceneVote(meta)
            if (sceneVote != null) {
                addVote(scores, reasons, sceneVote.filterId, rankWeight * config.sceneSupport, sceneVote.reason)
                evidenceByFilter[sceneVote.filterId] =
                    (evidenceByFilter[sceneVote.filterId] ?: 0f) + rankWeight
            }
            applyPhotoTypeSupport(meta, rankWeight, scores, reasons)

            if (meta.sceneCategory == "natural_landscape" && meta.timeOfDay == "golden_hour") {
                addVote(
                    scores,
                    reasons,
                    FilterIds.GOLDEN_WARM,
                    rankWeight * config.goldenHourSupport,
                    FilterReason.GOLDEN_HOUR
                )
            }
        }
    }

    internal fun applyColorSafety(
        scores: MutableMap<String, Float>,
        reasons: MutableMap<String, LinkedHashSet<FilterReason>>,
        stats: FrameColorStats,
        marginIsLow: Boolean,
        evidenceConflicts: Boolean
    ): Boolean {
        if (stats.meanSaturation >= config.highSaturationThreshold) {
            scale(scores, FilterIds.FOOD_VIVID, config.highSaturationFoodMultiplier)
            addReason(reasons, FilterIds.FOOD_VIVID, FilterReason.HIGH_SATURATION)
        } else if (stats.meanSaturation <= config.lowSaturationThreshold) {
            scale(scores, FilterIds.FOOD_VIVID, config.lowSaturationFoodMultiplier)
            addReason(reasons, FilterIds.FOOD_VIVID, FilterReason.LOW_SATURATION)
        }

        if (stats.luminanceStdDev >= config.highContrastThreshold) {
            scale(scores, FilterIds.URBAN_CRISP, config.highContrastUrbanMultiplier)
            scale(scores, FilterIds.PORTRAIT_SOFT, config.highContrastPortraitMultiplier)
            addReason(reasons, FilterIds.URBAN_CRISP, FilterReason.HIGH_CONTRAST)
            addReason(reasons, FilterIds.PORTRAIT_SOFT, FilterReason.HIGH_CONTRAST)
        } else if (stats.luminanceStdDev <= config.lowContrastThreshold) {
            scale(scores, FilterIds.LANDSCAPE_CLEAR, config.lowContrastLandscapeMultiplier)
            addReason(reasons, FilterIds.LANDSCAPE_CLEAR, FilterReason.LOW_CONTRAST)
        }

        when {
            stats.warmth >= config.warmFrameThreshold ->
                scale(scores, FilterIds.GOLDEN_WARM, config.warmGoldenMultiplier)
            stats.warmth <= config.coolFrameThreshold ->
                scale(scores, FilterIds.GOLDEN_WARM, config.coolGoldenMultiplier)
        }

        if (stats.shadowClipRatio >= config.urbanShadowThreshold) {
            scale(scores, FilterIds.URBAN_CRISP, config.shadowedUrbanMultiplier)
            addReason(reasons, FilterIds.URBAN_CRISP, FilterReason.LOW_LIGHT_SAFETY)
        }

        val lowLight = stats.meanLuminance <= config.lowLightMeanThreshold ||
            stats.shadowClipRatio >= config.severeShadowClipThreshold
        val clippedHighlights = stats.highlightClipRatio >= config.severeHighlightClipThreshold
        val unsafe = lowLight || clippedHighlights
        if (lowLight) prioritizeOriginal(scores, reasons, FilterReason.LOW_LIGHT_SAFETY)
        if (clippedHighlights) {
            scale(scores, FilterIds.FOOD_VIVID, config.clippedFoodMultiplier)
            scale(scores, FilterIds.GOLDEN_WARM, config.clippedGoldenMultiplier)
            scale(scores, FilterIds.URBAN_CRISP, config.clippedUrbanMultiplier)
            prioritizeOriginal(scores, reasons, FilterReason.HIGHLIGHT_SAFETY)
        }
        if (marginIsLow || evidenceConflicts) {
            prioritizeOriginal(scores, reasons, FilterReason.LOW_MODEL_MARGIN)
        }
        return unsafe
    }

    internal fun normalizeAndSort(
        scores: Map<String, Float>,
        reasons: Map<String, Set<FilterReason>>
    ): List<FilterCandidate> {
        val scoreScale = config.styleScoreScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        return scores.asSequence()
            .filter { (id, score) -> FilterIds.isKnown(id) && score.isFinite() && score >= 0f }
            .map { (id, score) ->
                FilterCandidate(
                    filterId = id,
                    score = (score / (score + scoreScale)).finiteUnit(),
                    reasons = reasons[id].orEmpty().ifEmpty { setOf(FilterReason.FALLBACK) }.toList()
                )
            }
            .sortedWith(compareByDescending<FilterCandidate> { it.score }.thenBy { it.filterId })
            .distinctBy { it.filterId }
            .take(3)
            .toList()
    }

    private fun applyPhotoTypeSupport(
        meta: TemplateMeta,
        weight: Float,
        scores: MutableMap<String, Float>,
        reasons: MutableMap<String, LinkedHashSet<FilterReason>>
    ) {
        when (meta.photoType) {
            "portrait" -> addVote(scores, reasons, FilterIds.PORTRAIT_SOFT, weight * config.photoTypeSupport, FilterReason.PORTRAIT)
            "still_life" -> addVote(scores, reasons, FilterIds.STILL_LIFE_CLEAN, weight * config.photoTypeSupport, FilterReason.STILL_LIFE)
            "landscape" -> addVote(scores, reasons, FilterIds.LANDSCAPE_CLEAR, weight * config.photoTypeSupport, FilterReason.NATURAL_LANDSCAPE)
        }
    }

    private fun sceneVote(meta: TemplateMeta): Vote? = when (meta.sceneCategory) {
        "closeup_portrait" -> Vote(FilterIds.PORTRAIT_SOFT, FilterReason.PORTRAIT)
        "food_still_life" -> Vote(FilterIds.FOOD_VIVID, FilterReason.FOOD)
        "lifestyle_still_life", "plant_still_life" -> Vote(FilterIds.STILL_LIFE_CLEAN, FilterReason.STILL_LIFE)
        "natural_landscape" -> Vote(FilterIds.LANDSCAPE_CLEAR, FilterReason.NATURAL_LANDSCAPE)
        "urban_landscape" -> Vote(FilterIds.URBAN_CRISP, FilterReason.URBAN_LANDSCAPE)
        else -> null
    }

    private fun modelMarginIsLow(hits: List<SearchHit>): Boolean {
        if (hits.size < 2) return false
        val first = hits[0].safeModelScore()
        val second = hits[1].safeModelScore()
        return first != null && second != null && (first - second).coerceAtLeast(0f) < config.lowModelMarginThreshold
    }

    private fun evidenceConflicts(evidence: Map<String, Float>): Boolean {
        val sorted = evidence.values.filter { it.isFinite() && it > 0f }.sortedDescending()
        val total = sorted.sum()
        if (sorted.size < 2 || total <= 0f) return false
        return sorted[0] / total < config.conflictingEvidenceShare ||
            (sorted[0] - sorted[1]) / total < config.conflictingEvidenceGap
    }

    private fun prioritizeOriginal(
        scores: MutableMap<String, Float>,
        reasons: MutableMap<String, LinkedHashSet<FilterReason>>,
        reason: FilterReason
    ) {
        val strongest = scores.values.filter { it.isFinite() }.maxOrNull() ?: 0f
        val priorityBoost = config.originalPriorityBoost.finitePositive()
        scores[FilterIds.ORIGINAL] = max(
            scores[FilterIds.ORIGINAL] ?: 0f,
            strongest + priorityBoost
        )
        addReason(reasons, FilterIds.ORIGINAL, reason)
    }

    private data class Vote(val filterId: String, val reason: FilterReason)
}

private fun addVote(
    scores: MutableMap<String, Float>,
    reasons: MutableMap<String, LinkedHashSet<FilterReason>>,
    filterId: String,
    amount: Float,
    reason: FilterReason
) {
    if (!FilterIds.isKnown(filterId) || !amount.isFinite() || amount <= 0f) return
    scores[filterId] = (scores[filterId] ?: 0f) + amount
    addReason(reasons, filterId, reason)
}

private fun addReason(
    reasons: MutableMap<String, LinkedHashSet<FilterReason>>,
    filterId: String,
    reason: FilterReason
) {
    reasons.getOrPut(filterId) { linkedSetOf() }.add(reason)
}

private fun scale(scores: MutableMap<String, Float>, filterId: String, factor: Float) {
    val score = scores[filterId] ?: return
    scores[filterId] = if (factor.isFinite()) (score * factor).coerceAtLeast(0f) else 0f
}

private fun SearchHit.safeModelScore(): Float? = when {
    rerankScore.isFinite() -> rerankScore
    cosine.isFinite() -> cosine
    else -> null
}

private fun Float.finitePositive(): Float = if (isFinite()) coerceAtLeast(0f) else 0f
private fun Float.finiteUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f

internal fun FilterRecommendation.enforceContract(): FilterRecommendation {
    val safeSelected = selectedFilterId.takeIf(FilterIds::isKnown) ?: FilterIds.ORIGINAL
    val safeCandidates = candidates.asSequence()
        .filter { FilterIds.isKnown(it.filterId) }
        .map {
            it.copy(
                score = it.score.finiteUnit(),
                reasons = it.reasons.distinct().ifEmpty { listOf(FilterReason.FALLBACK) }
            )
        }
        .distinctBy { it.filterId }
        .sortedByDescending { it.score }
        .toMutableList()
    if (safeCandidates.none { it.filterId == safeSelected }) {
        safeCandidates.add(FilterCandidate(safeSelected, 0f, listOf(FilterReason.FALLBACK)))
    }
    val sorted = safeCandidates.sortedByDescending { it.score }
    val limited = sorted.take(3).toMutableList()
    if (limited.none { it.filterId == safeSelected }) {
        if (limited.size == 3) limited.removeAt(limited.lastIndex)
        limited.add(sorted.first { it.filterId == safeSelected })
        limited.sortByDescending { it.score }
    }
    return copy(
        contractVersion = FilterContract.VERSION,
        selectedFilterId = safeSelected,
        confidence = confidence.finiteUnit(),
        candidates = limited,
        shouldAutoPreview = shouldAutoPreview && safeSelected != FilterIds.ORIGINAL
    )
}
