package com.example.aicamera.pipeline

import com.example.aicamera.filter.FrameColorStats

data class V9SceneContextConfig(
    val rankWeights: List<Float> = listOf(1f, 0.84f, 0.70f, 0.58f, 0.48f, 0.38f, 0.30f, 0.24f),
    val minimumPhotoTypeShare: Float = 0.60f,
    val minimumSceneShare: Float = 0.58f,
    val minimumCompositionShare: Float = 0.66f,
    val softLightingStdDev: Float = 0.08f,
    val hardLightingStdDev: Float = 0.25f,
    val minimumCosine: Float = 0.65f,
    val minimumSupportingCandidates: Int = 2
)

/**
 * Derives a conservative reranking context from the original V9 candidate set. It is deliberately
 * consensus-only: ambiguous candidates return an empty field instead of reinforcing a weak top1.
 */
class V9SceneContextEstimator(
    private val config: V9SceneContextConfig = V9SceneContextConfig()
) {
    fun estimate(candidates: List<SearchHit>, stats: FrameColorStats): SceneContext {
        val usable = candidates.take(config.rankWeights.size).withIndex()
            .filter { it.value.metadata != null && it.value.cosine.isFinite() &&
                it.value.cosine in config.minimumCosine..1f &&
                config.rankWeights[it.index].isFinite() && config.rankWeights[it.index] > 0f }
            .distinctBy { it.value.templateId }
        if (usable.isEmpty()) return SceneContext(lightingQuality = estimateLighting(stats))

        val photoVotes = linkedMapOf<String, Float>()
        val sceneVotes = linkedMapOf<String, Float>()
        val compositionVotes = linkedMapOf<String, Float>()
        var totalWeight = 0f
        usable.forEach { (index, hit) ->
            val meta = hit.metadata ?: return@forEach
            val weight = (config.rankWeights[index] * hit.cosine.coerceIn(0f, 1f))
                .takeIf { it.isFinite() && it > 0f } ?: return@forEach
            totalWeight += weight
            addVote(photoVotes, meta.photoType, weight)
            addVote(sceneVotes, meta.sceneCategory, weight)
            addVote(compositionVotes, meta.compositionType, weight)
        }

        val photo = consensus(photoVotes, config.minimumPhotoTypeShare, totalWeight)?.takeIf { vote ->
            usable.count { it.value.metadata?.photoType == vote.value } >= config.minimumSupportingCandidates
        }
        val scene = consensus(sceneVotes, config.minimumSceneShare, totalWeight)?.takeIf { vote ->
            usable.count { it.value.metadata?.sceneCategory == vote.value } >= config.minimumSupportingCandidates
        }
        val composition = consensus(compositionVotes, config.minimumCompositionShare, totalWeight)?.takeIf { vote ->
            usable.count { it.value.metadata?.compositionType == vote.value } >= config.minimumSupportingCandidates
        }
        val sceneMetaMatchesPhoto = scene?.value?.let { sceneCategory ->
            usable.any { (_, hit) ->
                val meta = hit.metadata
                meta != null && meta.sceneCategory == sceneCategory && meta.photoType == photo?.value
            }
        } ?: false

        return SceneContext(
            photoType = photo?.value,
            sceneCategory = scene?.value?.takeIf { sceneMetaMatchesPhoto },
            sceneConfidence = if (sceneMetaMatchesPhoto) scene?.share ?: 0f else 0f,
            preferredComposition = composition?.value,
            lightingQuality = estimateLighting(stats)
        )
    }

    private fun estimateLighting(stats: FrameColorStats): String {
        val sanitized = stats.sanitized()
        return when {
            sanitized.luminanceStdDev <= config.softLightingStdDev -> "soft"
            sanitized.luminanceStdDev >= config.hardLightingStdDev -> "hard"
            else -> "mixed"
        }
    }

    private fun consensus(votes: Map<String, Float>, threshold: Float, total: Float): Consensus? {
        val positive = votes.filter { (key, value) -> key.isNotBlank() && value.isFinite() && value > 0f }
        // Unknown rows still consume evidence mass; two weak known labels cannot turn a mostly
        // unknown neighborhood into unanimous scene confidence.
        if (total <= 0f || !total.isFinite()) return null
        val winner = positive.maxByOrNull { it.value } ?: return null
        val share = (winner.value / total).coerceIn(0f, 1f)
        return Consensus(winner.key, share).takeIf { share >= threshold }
    }

    private fun addVote(votes: MutableMap<String, Float>, key: String, weight: Float) {
        if (key.isBlank() || key == "unknown" || !weight.isFinite() || weight <= 0f) return
        votes[key] = (votes[key] ?: 0f) + weight
    }

    private data class Consensus(val value: String, val share: Float)
}
