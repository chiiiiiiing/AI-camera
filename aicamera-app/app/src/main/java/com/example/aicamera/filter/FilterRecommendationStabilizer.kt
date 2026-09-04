package com.example.aicamera.filter

data class FilterStabilizerConfig(
    val switchLeadCount: Int = 2,
    val firstRecommendationConfidence: Float = 0.55f,
    val lowCandidateMargin: Float = 0.06f
)

class FilterRecommendationStabilizer(
    private val config: FilterStabilizerConfig = FilterStabilizerConfig()
) {
    private var current: FilterRecommendation? = null
    private var challengerId: String? = null
    private var challengerLeadCount: Int = 0

    init {
        require(config.switchLeadCount > 0) { "switchLeadCount must be positive" }
    }

    @Synchronized
    fun stabilize(input: FilterRecommendation): FilterRecommendation {
        val incoming = input.enforceContract()
        if (incoming.requiresImmediateOriginal()) {
            return accept(incoming.copy(isStable = true, shouldAutoPreview = false))
        }

        val incumbent = current
        if (incumbent == null) {
            return if (incoming.selectedFilterId == FilterIds.ORIGINAL) {
                accept(incoming.copy(isStable = true, shouldAutoPreview = false))
            } else if (incoming.confidence >= config.firstRecommendationConfidence && incoming.shouldAutoPreview) {
                accept(incoming.copy(isStable = true, shouldAutoPreview = true))
            } else {
                accept(FilterRecommendations.original(sourceTemplateId = incoming.sourceTemplateId))
            }
        }

        if (incoming.selectedFilterId == incumbent.selectedFilterId) {
            clearChallenger()
            return accept(incoming.copy(
                isStable = true,
                shouldAutoPreview = incoming.shouldAutoPreview && incoming.selectedFilterId != FilterIds.ORIGINAL
            ))
        }

        if (incoming.candidateMargin() < config.lowCandidateMargin || !incoming.shouldAutoPreview) {
            clearChallenger()
            return hold(incumbent, incoming)
        }

        if (challengerId == incoming.selectedFilterId) {
            challengerLeadCount++
        } else {
            challengerId = incoming.selectedFilterId
            challengerLeadCount = 1
        }

        return if (challengerLeadCount >= config.switchLeadCount) {
            accept(incoming.copy(isStable = true, shouldAutoPreview = true))
        } else {
            hold(incumbent, incoming)
        }
    }

    @Synchronized
    fun reset() {
        current = null
        clearChallenger()
    }

    private fun accept(recommendation: FilterRecommendation): FilterRecommendation {
        val safe = recommendation.enforceContract()
        current = safe
        clearChallenger()
        return safe
    }

    private fun hold(
        incumbent: FilterRecommendation,
        incoming: FilterRecommendation
    ): FilterRecommendation {
        val incumbentCandidate = incumbent.candidates.firstOrNull {
            it.filterId == incumbent.selectedFilterId
        } ?: FilterCandidate(incumbent.selectedFilterId, incumbent.confidence, listOf(FilterReason.FALLBACK))
        val merged = (incoming.candidates + incumbentCandidate)
            .distinctBy { it.filterId }
            .sortedByDescending { it.score }
            .take(3)
            .toMutableList()
        if (merged.none { it.filterId == incumbent.selectedFilterId }) {
            if (merged.size == 3) merged.removeAt(merged.lastIndex)
            merged.add(incumbentCandidate)
        }
        return incumbent.copy(
            candidates = merged.sortedByDescending { it.score },
            isStable = true,
            shouldAutoPreview = incumbent.shouldAutoPreview && incumbent.selectedFilterId != FilterIds.ORIGINAL
        ).enforceContract()
    }

    private fun FilterRecommendation.requiresImmediateOriginal(): Boolean {
        if (selectedFilterId != FilterIds.ORIGINAL) return false
        val selectedReasons = candidates.firstOrNull { it.filterId == FilterIds.ORIGINAL }?.reasons.orEmpty()
        return FilterReason.LOW_LIGHT_SAFETY in selectedReasons ||
            FilterReason.HIGHLIGHT_SAFETY in selectedReasons ||
            selectedReasons.toSet() == setOf(FilterReason.FALLBACK)
    }

    private fun FilterRecommendation.candidateMargin(): Float {
        val first = candidates.getOrNull(0)?.score ?: return 0f
        val second = candidates.getOrNull(1)?.score ?: return first
        return (first - second).coerceAtLeast(0f)
    }

    private fun clearChallenger() {
        challengerId = null
        challengerLeadCount = 0
    }
}
