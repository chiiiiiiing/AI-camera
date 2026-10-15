package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.NormalizedBox

/** All times use Android elapsedRealtime; IDs identify samples, never display frames. */
data class StructureObservation(
    val observationId: Long,
    val sampledAtMs: Long,
    val viewportVersion: Long,
    val faceCount: Int,
    val face: NormalizedBox?,
    val faceYawDegrees: Float? = null,
    val trackingId: Int? = null,
    val valid: Boolean = true,
    val aspectRatio: Float = 1f
) {
    fun singleFace(nowMs: Long): NormalizedBox? = face?.takeIf {
        valid && faceCount == 1 && observationId > 0 && nowMs >= sampledAtMs &&
            nowMs - sampledAtMs <= 750 && it.left > .01f && it.top > .01f &&
            it.right < .99f && it.bottom < .99f && it.width >= .04f && it.height >= .04f
    }
}

data class CompositionTarget(
    val id: String,
    val center: NormalizedPoint,
    val tolerance: Float = .08f,
    val viewportVersion: Long,
    val source: String,
    val preserveSubject: Boolean = true,
    val subjectTrackingId: Int? = null,
    val referenceFaceHeight: Float? = null,
    val referenceFaceAspectRatio: Float? = null,
    val referenceYawDegrees: Float? = null
) {
    fun accepts(face: NormalizedBox, yaw: Float? = null): Boolean {
        val dx = center.x-face.centerX; val dy = center.y-face.centerY
        if (kotlin.math.abs(dx) > .28f || kotlin.math.abs(dy) > .28f ||
            face.left+dx < .03f || face.right+dx > .97f || face.top+dy < .03f || face.bottom+dy > .97f) return false
        if (referenceFaceHeight?.let { face.height/it !in .65f..1.55f } == true) return false
        if (referenceFaceAspectRatio?.let { kotlin.math.abs(kotlin.math.ln((face.width/face.height/it).toDouble())) > .45 } == true) return false
        return referenceYawDegrees == null || yaw == null || kotlin.math.abs(referenceYawDegrees-yaw) <= 25f
    }
}

enum class GuidanceFeedback { INSUFFICIENT_EVIDENCE, ADJUSTING, IMPROVING, VERIFYING, READY, WORSENING, NO_PROGRESS }

/** Single decision emitted by the session controller; retrieval cosine is never a probability. */
data class GuidanceDecision(
    val action: CompositionSessionController.GuidanceAction,
    val targetId: String?,
    val error: Float?,
    val feedback: GuidanceFeedback,
    val observationId: Long,
    val replanRequested: Boolean = false
)
