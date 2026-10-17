package com.example.aicamera.guidance

enum class TechniqueEvidenceKind { MEASURED_IMAGE, DETECTED_SUBJECT, SCENE_RECIPE }

/** Optional coaching, never a movement command or an automatic capture authorization. */
data class PhotographyTechniqueAdvice(
    val id: String,
    val title: String,
    val suggestion: String,
    val evidenceKind: TechniqueEvidenceKind,
    val sampledAtMs: Long,
    val viewportVersion: Long,
    val reasonCodes: List<String> = emptyList(),
    /** Limited by every dependency's actual sampling time, including cached face/object evidence. */
    val expiresAtMs: Long = sampledAtMs + 1500L,
    val requiredAnchorKind: ObservedAnchorKind = ObservedAnchorKind.NONE,
    val requiredTrackingId: Int? = null,
    val requiredClassId: Int? = null,
    val requiredFacingDirectionX: Float? = null
) {
    /** New geometry may revoke a cached tip, but may never renew its image evidence. */
    fun isValidFor(scene: SceneAnalysisObservation, structure: StructureObservation?, nowMs: Long): Boolean {
        if (!scene.isFresh(nowMs) || scene.family == SceneFamily.UNKNOWN || scene.viewportVersion != viewportVersion ||
            nowMs < sampledAtMs || nowMs > expiresAtMs || nowMs - sampledAtMs > 1500L) return false
        return when(requiredAnchorKind) {
            ObservedAnchorKind.NONE -> true
            ObservedAnchorKind.FACE -> scene.family == SceneFamily.PORTRAIT && structure != null &&
                structure.viewportVersion == viewportVersion && structure.trackingId == requiredTrackingId &&
                structure.singleFace(nowMs) != null &&
                (requiredFacingDirectionX == null || structure.facingDirectionX == requiredFacingDirectionX)
            ObservedAnchorKind.OBJECT -> scene.objectObservation?.let {
                it.viewportVersion == viewportVersion && it.trackingId == requiredTrackingId &&
                    it.classId == requiredClassId && it.isFresh(nowMs)
            } == true
        }
    }
}
