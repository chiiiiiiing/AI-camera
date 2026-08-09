package com.example.aicamera.zoom

data class AutoZoomStabilizerConfig(
    val switchLeadCount: Int = 2,
    val firstRecommendationConfidence: Float = 0.55f,
    val lowConfidenceThreshold: Float = 0.55f
)

/** Keeps a V9 zoom intention stable without ever issuing a camera-control command. */
class AutoZoomRecommendationStabilizer(
    private val config: AutoZoomStabilizerConfig = AutoZoomStabilizerConfig()
) {
    private var current: AutoZoomRecommendation? = null
    private var challengerKey: TargetKey? = null
    private var challengerLeadCount: Int = 0

    init {
        require(config.switchLeadCount > 0) { "switchLeadCount must be positive" }
    }

    @Synchronized
    fun stabilize(input: AutoZoomRecommendation): AutoZoomRecommendation {
        val incoming = input.enforceContract()
        if (!incoming.shouldAutoZoom) return accept(incoming.copy(isStable = true))

        val incumbent = current
        if (incumbent == null || !incumbent.shouldAutoZoom) {
            return if (incoming.confidence >= config.firstRecommendationConfidence) {
                accept(incoming.copy(isStable = true))
            } else {
                accept(AutoZoomRecommendations.disabled(AutoZoomReason.LOW_CONFIDENCE, incoming.sourceTemplateId))
            }
        }

        if (incoming.targetKey() == incumbent.targetKey()) {
            clearChallenger()
            return accept(incoming.copy(isStable = true))
        }
        if (incoming.confidence < config.lowConfidenceThreshold) {
            clearChallenger()
            return incumbent.copy(isStable = true).enforceContract()
        }

        if (challengerKey == incoming.targetKey()) challengerLeadCount++ else {
            challengerKey = incoming.targetKey()
            challengerLeadCount = 1
        }
        return if (challengerLeadCount >= config.switchLeadCount) {
            accept(incoming.copy(isStable = true))
        } else {
            incumbent.copy(isStable = true).enforceContract()
        }
    }

    @Synchronized
    fun reset() {
        current = null
        clearChallenger()
    }

    private fun accept(recommendation: AutoZoomRecommendation): AutoZoomRecommendation {
        val safe = recommendation.enforceContract()
        current = safe
        clearChallenger()
        return safe
    }

    private fun clearChallenger() {
        challengerKey = null
        challengerLeadCount = 0
    }

    private data class TargetKey(val scene: AutoZoomScene, val framing: ZoomFraming)
    private fun AutoZoomRecommendation.targetKey() = TargetKey(scene, framing)
}
