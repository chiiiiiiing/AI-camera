package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.NormalizedBox

/** Live detector geometry + explicitly labelled composition rules, not template ground truth. */
class AdaptiveSubjectPlanner {
    private var faceTarget: CompositionTarget? = null
    private var objectTarget: ObjectCompositionTarget? = null
    private var faceSeenAt = Long.MIN_VALUE
    private var objectSeenAt = Long.MIN_VALUE
    private var faceRevision = 0L
    private var objectRevision = 0L

    fun reset() { faceTarget = null; objectTarget = null }

    fun face(observation: StructureObservation?, now: Long): CompositionTarget? {
        val obs = observation ?: return null.also { if (faceSeenAt == Long.MIN_VALUE || now - faceSeenAt > 1200) faceTarget = null }
        val box = obs.singleFace(now) ?: return null.also { if (faceSeenAt == Long.MIN_VALUE || now - faceSeenAt > 1200) faceTarget = null }
        faceSeenAt = obs.sampledAtMs
        if (box.width > .85f || box.height > .85f) { faceTarget = null; return null }
        faceTarget?.takeIf { it.viewportVersion == obs.viewportVersion && it.accepts(box, obs.faceYawDegrees) }
            ?.let { return it.copy(subjectTrackingId = obs.trackingId).also { updated -> faceTarget = updated } }
        val scale = when { box.height < .20f -> 1.25f; box.height > .36f -> .90f; else -> 1f }
        val center = center(box, face = true)
        return CompositionTarget("live-face:${obs.viewportVersion}:${++faceRevision}", center, .12f, obs.viewportVersion,
            source = "live_face_rule", referenceFaceHeight = box.height * scale,
            subjectTrackingId = obs.trackingId,
            referenceFaceAspectRatio = box.width / box.height).also { faceTarget = it }
    }

    fun objectTarget(observation: ObjectObservation?, now: Long): ObjectCompositionTarget? {
        val obs = observation?.takeIf { it.isFresh(now) } ?: return null.also {
            if (objectSeenAt == Long.MIN_VALUE || now - objectSeenAt > 1200) objectTarget = null
        }
        objectSeenAt = obs.sampledAtMs
        objectTarget?.takeIf { it.accepts(obs) }?.let { return it }
        val box = obs.box
        if (box.width > .85f || box.height > .85f) { objectTarget = null; return null }
        val scale = (.50f / maxOf(box.width, box.height)).coerceIn(.90f, 1.25f)
        val center = center(box, face = false)
        val reference = NormalizedBox(center.x - box.width * scale / 2, center.y - box.height * scale / 2,
            center.x + box.width * scale / 2, center.y + box.height * scale / 2)
        return ObjectCompositionTarget("live-object:${obs.viewportVersion}:${obs.trackingId}:${++objectRevision}", center,
            obs.viewportVersion, obs.trackingId, obs.classId, reference, .12f,
            source = "live_object_rule").also { objectTarget = it }
    }

    private fun center(box: NormalizedBox, face: Boolean): NormalizedPoint {
        // Use the nearer third to avoid large sweeps; leave headroom for faces.
        // Clamp by the actual box extent so the complete detected subject stays visible.
        val x = if (box.width > .55f) .5f else if (box.centerX < .5f) 1f / 3f else 2f / 3f
        val y = if (face) .35f else .5f
        val maxShift = if (face) .25f else .18f
        return NormalizedPoint(x.coerceIn(box.centerX - maxShift, box.centerX + maxShift)
            .coerceIn(.04f + box.width / 2, .96f - box.width / 2),
            y.coerceIn(box.centerY - maxShift, box.centerY + maxShift)
                .coerceIn(.04f + box.height / 2, .96f - box.height / 2))
    }
}
