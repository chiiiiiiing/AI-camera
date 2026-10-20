package com.example.aicamera.guidance

/** Retains an active session's goal across misses; held geometry is never fresh detector evidence. */
class SubjectContinuity(private val recoveryMs: Long = 1200L) {
    private var last: CompositionSessionController.FrameObservation? = null
    fun reset() { last = null }

    fun update(current: CompositionSessionController.FrameObservation, keepSession: Boolean = false,
               visualBox: com.example.aicamera.guidance.geometry.NormalizedBox? = null,
               visualSampledAtMs: Long = -1L): CompositionSessionController.FrameObservation {
        val sameKind = last?.observedAnchorKind?.let { it == current.observedAnchorKind } != false
        if ((current.hasRealSubject || current.visualPositionAvailable) && current.targetCx != null && current.targetCy != null &&
            (!keepSession || sameKind || current.targetId?.startsWith("manual:") == true)) {
            val previous = last
            val accepted = if (keepSession && previous != null && previous.viewportVersion == current.viewportVersion &&
                previous.observedAnchorKind == current.observedAnchorKind &&
                !(current.targetId?.startsWith("manual:") == true && current.targetId != previous.targetId)) current.copy(
                targetCx = previous.targetCx, targetCy = previous.targetCy, targetId = previous.targetId,
                targetTolerance = previous.targetTolerance, targetViewportVersion = previous.targetViewportVersion) else current
            last = accepted
            return accepted
        }
        val previous = last ?: return current
        val age = current.timestampMs - previous.subjectSampledAtMs
        if (current.viewportVersion != previous.viewportVersion || age < 0 ||
            (!keepSession && (age > recoveryMs || current.faceCount > 1))) {
            last = null
            return current
        }
        return current.copy(
            subjectCx = visualBox?.centerX ?: previous.subjectCx,
            subjectCy = visualBox?.centerY ?: previous.subjectCy,
            targetCx = previous.targetCx, targetCy = previous.targetCy,
            targetTolerance = previous.targetTolerance, targetId = previous.targetId,
            targetViewportVersion = previous.targetViewportVersion,
            observedAnchorKind = previous.observedAnchorKind,
            subjectTrackingId = previous.subjectTrackingId,
            subjectObservationId = previous.subjectObservationId,
            subjectSampledAtMs = previous.subjectSampledAtMs,
            hasRealSubject = false, expectsSubject = true, trackingPaused = true,
            visualPositionAvailable = visualBox != null,
            visualPositionSampledAtMs = visualSampledAtMs
        )
    }
}
