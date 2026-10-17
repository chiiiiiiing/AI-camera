package com.example.aicamera.pipeline

import com.example.aicamera.guidance.ObjectObservation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.StructureObservation

/** Analysis mode only: selecting PORTRAIT never by itself authorizes a subject or camera action. */
enum class AutoPhotoMode { UNKNOWN, PORTRAIT, STILL_LIFE, LANDSCAPE }

enum class AutoSceneEvidence { NONE, FACE, OBJECT, SEMANTIC_CONSENSUS }

data class AutoSceneDecision(
    val family: SceneFamily,
    val context: SceneContext,
    val mode: AutoPhotoMode,
    val evidenceSource: AutoSceneEvidence,
    val reason: String
)

/**
 * Chooses the analysis mode from current observed subjects, then semantic consensus. No selected
 * reference or user mode is required. Scores describe retrieval agreement, never beauty or a
 * calibrated category probability. Unknown/conflicting evidence immediately withdraws the old mode.
 */
class AutoScenePolicy(
    private val minimumSemanticConfidence: Float = .70f,
    private val confirmationSpanMs: Long = 300L
) {
    private var viewport = Long.MIN_VALUE
    private var lastFrameSample = Long.MIN_VALUE
    private var lastNow = Long.MIN_VALUE
    private var active = SceneFamily.UNKNOWN
    private var pending = SceneFamily.UNKNOWN
    private var pendingSource = AutoSceneEvidence.NONE
    private var firstPendingAt = Long.MIN_VALUE
    private var lastEvidenceAt = Long.MIN_VALUE
    private var lastEvidenceId = Long.MIN_VALUE
    private var samples = 0

    fun resolve(
        context: SceneContext,
        structure: StructureObservation?,
        objectObservation: ObjectObservation?,
        sampledAtMs: Long,
        nowMs: Long,
        viewportVersion: Long
    ): AutoSceneDecision {
        if (viewport != viewportVersion || nowMs < lastNow) {
            reset()
            viewport = viewportVersion
        }
        lastNow = nowMs
        if (viewportVersion < 0 || sampledAtMs <= 0 || sampledAtMs > nowMs || nowMs - sampledAtMs > 1500) {
            clearSelection()
            return unknown(context, "scene_observation_stale")
        }
        if (sampledAtMs < lastFrameSample) {
            clearSelection()
            return unknown(context, "scene_observation_out_of_order")
        }
        lastFrameSample = sampledAtMs

        val candidate = candidate(context, structure, objectObservation, sampledAtMs, nowMs, viewportVersion)
            ?: run {
                clearSelection()
                return unknown(context, "scene_unconfirmed")
            }
        if (candidate.family != pending || candidate.source != pendingSource ||
            lastEvidenceAt != Long.MIN_VALUE && candidate.sampledAtMs - lastEvidenceAt > 1500) {
            if (candidate.family != active) active = SceneFamily.UNKNOWN
            if (lastEvidenceAt != Long.MIN_VALUE && candidate.sampledAtMs - lastEvidenceAt > 1500) active = SceneFamily.UNKNOWN
            pending = candidate.family
            pendingSource = candidate.source
            firstPendingAt = candidate.sampledAtMs
            lastEvidenceAt = candidate.sampledAtMs
            lastEvidenceId = candidate.observationId
            samples = 1
        } else if (candidate.observationId > lastEvidenceId && candidate.sampledAtMs > lastEvidenceAt) {
            samples++
            lastEvidenceId = candidate.observationId
            lastEvidenceAt = candidate.sampledAtMs
        } else if (candidate.observationId < lastEvidenceId || candidate.sampledAtMs < lastEvidenceAt) {
            clearSelection()
            return unknown(context, "scene_subject_out_of_order")
        }
        if (samples >= 2 && lastEvidenceAt - firstPendingAt >= confirmationSpanMs) active = candidate.family
        if (active != candidate.family) return unknown(context, "scene_confirming")
        return AutoSceneDecision(candidate.family, confirmedContext(context, candidate.family),
            mode(candidate.family), candidate.source, "scene_confirmed")
    }

    fun reset() {
        viewport = Long.MIN_VALUE
        lastFrameSample = Long.MIN_VALUE
        lastNow = Long.MIN_VALUE
        clearSelection()
    }

    private fun clearSelection() {
        active = SceneFamily.UNKNOWN
        pending = SceneFamily.UNKNOWN
        pendingSource = AutoSceneEvidence.NONE
        firstPendingAt = Long.MIN_VALUE
        lastEvidenceAt = Long.MIN_VALUE
        lastEvidenceId = Long.MIN_VALUE
        samples = 0
    }

    private data class Candidate(val family: SceneFamily, val source: AutoSceneEvidence,
        val observationId: Long, val sampledAtMs: Long)

    private fun candidate(context: SceneContext, face: StructureObservation?, obj: ObjectObservation?,
        sampledAtMs: Long, nowMs: Long, viewportVersion: Long): Candidate? {
        val freshFace = face?.takeIf { it.valid && it.observationId > 0 && it.viewportVersion == viewportVersion &&
            it.sampledAtMs > 0 && it.sampledAtMs <= nowMs && nowMs - it.sampledAtMs <= 750 && it.faceCount >= 0 }
        if (freshFace != null && freshFace.faceCount > 0) {
            // Multi-person scenes are classified as portrait, while target selection still abstains.
            // Edge-clipped faces may establish a mode, but singleFace() separately protects guidance.
            val b = freshFace.face
            val validBox = b != null && listOf(b.left, b.top, b.right, b.bottom).all { it.isFinite() && it in 0f..1f } &&
                b.width > 0f && b.height > 0f
            if (freshFace.faceCount == 1 && !validBox) return null
            return Candidate(SceneFamily.PORTRAIT, AutoSceneEvidence.FACE, freshFace.observationId, freshFace.sampledAtMs)
        }
        val liveObject = obj?.takeIf { it.viewportVersion == viewportVersion && it.isFresh(nowMs) &&
            it.confidence <= 1f && it.classId in SUPPORTED_OBJECT_CLASSES &&
            it.box.width * it.box.height in .02f..0.65f }
        if (liveObject != null) return Candidate(
            if (liveObject.classId in FOOD_CLASSES) SceneFamily.FOOD else SceneFamily.STILL_LIFE,
            AutoSceneEvidence.OBJECT, liveObject.observationId, liveObject.sampledAtMs)

        if (!context.sceneConfidence.isFinite() || context.sceneConfidence !in minimumSemanticConfidence..1f) return null
        val semantic = family(context)
        // A fresh successful face scan with no face must not be overruled by lookalike gallery rows.
        if (semantic == SceneFamily.UNKNOWN || semantic == SceneFamily.PORTRAIT && freshFace?.faceCount == 0) return null
        return Candidate(semantic, AutoSceneEvidence.SEMANTIC_CONSENSUS, sampledAtMs, sampledAtMs)
    }

    private fun unknown(context: SceneContext, reason: String) = AutoSceneDecision(SceneFamily.UNKNOWN,
        SceneContext(lightingQuality = context.lightingQuality), AutoPhotoMode.UNKNOWN, AutoSceneEvidence.NONE, reason)

    private fun confirmedContext(context: SceneContext, selected: SceneFamily): SceneContext {
        val compatible = family(context) == selected
        val photoType = when (selected) {
            SceneFamily.PORTRAIT -> "portrait"
            SceneFamily.FOOD, SceneFamily.STILL_LIFE, SceneFamily.PLANT -> "still_life"
            SceneFamily.NATURAL_LANDSCAPE, SceneFamily.URBAN_LANDSCAPE -> "landscape"
            SceneFamily.UNKNOWN -> null
        }
        val sceneCategory = if (compatible) context.sceneCategory else when (selected) {
            SceneFamily.FOOD -> "food_still_life"
            else -> null
        }
        return SceneContext(photoType, sceneCategory, if (compatible) context.sceneConfidence else 0f,
            context.preferredComposition.takeIf { compatible }, context.lightingQuality)
    }

    companion object {
        // COCO object classes with bounded photographic subjects. A flowerpot is not a whole plant.
        private val FOOD_CLASSES = (46..55).toSet()
        private val SUPPORTED_OBJECT_CLASSES = setOf(39, 40, 41, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 73, 74, 77)

        fun family(context: SceneContext): SceneFamily {
            val scene = context.sceneCategory.orEmpty()
            val photo = context.photoType.orEmpty()
            return when {
                photo == "portrait" -> SceneFamily.PORTRAIT
                "plant" in scene || photo == "plant" -> SceneFamily.PLANT
                "food" in scene || photo == "food" -> SceneFamily.FOOD
                "still_life" in scene || photo == "lifestyle" || photo == "still_life" -> SceneFamily.STILL_LIFE
                "urban" in scene || "city" in scene || photo == "city" -> SceneFamily.URBAN_LANDSCAPE
                "landscape" in scene || "nature" in scene || photo == "nature" || photo == "landscape" -> SceneFamily.NATURAL_LANDSCAPE
                else -> SceneFamily.UNKNOWN
            }
        }

        fun mode(family: SceneFamily): AutoPhotoMode = when (family) {
            SceneFamily.PORTRAIT -> AutoPhotoMode.PORTRAIT
            SceneFamily.FOOD, SceneFamily.STILL_LIFE, SceneFamily.PLANT -> AutoPhotoMode.STILL_LIFE
            SceneFamily.NATURAL_LANDSCAPE, SceneFamily.URBAN_LANDSCAPE -> AutoPhotoMode.LANDSCAPE
            SceneFamily.UNKNOWN -> AutoPhotoMode.UNKNOWN
        }
    }
}
