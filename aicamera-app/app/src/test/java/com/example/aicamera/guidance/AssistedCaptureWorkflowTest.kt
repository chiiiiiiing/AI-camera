package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.GuidanceGeometry
import com.example.aicamera.guidance.geometry.HandheldHorizonPolicy
import org.junit.Assert.*
import org.junit.Test

/** End-to-end decisions over independently sampled, monotonic detector/IMU evidence. */
class AssistedCaptureWorkflowTest {
    private fun controller() = CompositionSessionController(HandheldHorizonPolicy).apply {
        startAnalysis()
        onAnalysisComplete()
    }

    private fun frame(id: Long, x: Float = .5f, y: Float = .5f) =
        CompositionSessionController.FrameObservation(
            frameId = id, timestampMs = id * 250, rollDegrees = 0f, rawRollDegrees = 0f,
            pitchDegrees = -45f, isUpright = true, modelConfidence = .8f,
            hasRealSubject = true, expectsSubject = true, subjectCx = x, subjectCy = y,
            targetCx = .5f, targetCy = .5f, targetTolerance = .12f
        )

    @Test fun `eight phone directions reduce image target error`() {
        val positions = listOf(
            Triple(.2f, .5f, GuidanceCue.MOVE_LEFT), Triple(.8f, .5f, GuidanceCue.MOVE_RIGHT),
            Triple(.5f, .2f, GuidanceCue.MOVE_UP), Triple(.5f, .8f, GuidanceCue.MOVE_DOWN),
            Triple(.2f, .2f, GuidanceCue.MOVE_UP_LEFT), Triple(.8f, .2f, GuidanceCue.MOVE_UP_RIGHT),
            Triple(.2f, .8f, GuidanceCue.MOVE_DOWN_LEFT), Triple(.8f, .8f, GuidanceCue.MOVE_DOWN_RIGHT)
        )
        positions.forEach { (x, y, expected) ->
            assertEquals(expected, GuidanceGeometry.guidanceCue(x, y, true))
            val phoneDx = when (expected) {
                GuidanceCue.MOVE_LEFT, GuidanceCue.MOVE_UP_LEFT, GuidanceCue.MOVE_DOWN_LEFT -> -.05f
                GuidanceCue.MOVE_RIGHT, GuidanceCue.MOVE_UP_RIGHT, GuidanceCue.MOVE_DOWN_RIGHT -> .05f
                else -> 0f
            }
            val phoneDy = when (expected) {
                GuidanceCue.MOVE_UP, GuidanceCue.MOVE_UP_LEFT, GuidanceCue.MOVE_UP_RIGHT -> -.05f
                GuidanceCue.MOVE_DOWN, GuidanceCue.MOVE_DOWN_LEFT, GuidanceCue.MOVE_DOWN_RIGHT -> .05f
                else -> 0f
            }
            assertTrue(kotlin.math.hypot(x - phoneDx - .5f, y - phoneDy - .5f) <
                kotlin.math.hypot(x - .5f, y - .5f))
        }
    }

    @Test fun `off target subject is guided then becomes ready with deliberate overhead pitch`() {
        val session = controller()
        for (id in 1L..6L) session.observe(frame(id, .7f, .3f))
        assertEquals(GuidanceCue.MOVE_UP_RIGHT, session.action.value.toGuidanceCue())
        assertFalse(session.state.value is CompositionSessionController.State.Ready)
        for (id in 7L..13L) session.observe(frame(id))
        assertEquals(CompositionSessionController.State.Ready, session.state.value)
    }

    @Test fun `bounded zoom preparation prevents capture until camera settles`() {
        val session = controller()
        for (id in 1L..10L) session.observe(frame(id).copy(zoomInProgress = true))
        assertFalse(session.state.value is CompositionSessionController.State.Ready)
        for (id in 11L..18L) session.observe(frame(id))
        assertEquals(CompositionSessionController.State.Ready, session.state.value)
    }

    @Test fun `cached detector frames cannot manufacture readiness`() {
        val session = controller()
        for (id in 1L..20L) session.observe(frame(id).copy(subjectObservationId = 1, subjectSampledAtMs = 250))
        assertFalse(session.state.value is CompositionSessionController.State.Ready)
    }

    @Test fun `reference arriving after initial sample window can trigger one limited zoom`() {
        val zoom = GuidanceStepSequencer(HandheldHorizonPolicy)
        zoom.reset(0)
        val targets = mutableListOf<Float>()
        for (i in 1..30) {
            val out = zoom.onFrame(GuidanceStepSequencer.Input(
                horizonAngleDeg = 0f, subjectHeightFraction = .15f,
                hasSubject = true, expectsSubject = true, currentZoomRatio = 1f,
                minZoomRatio = 1f, maxZoomRatio = 4f, nowMs = i * 100L,
                targetSubjectHeightFraction = if (i >= 10) .20f else null
            ), GuidanceCue.NONE, 0f)
            out.zoomTarget?.let { targets += it; zoom.notifyZoomApplied(i * 100L) }
        }
        assertEquals(1, targets.size)
        assertEquals(1.25f, targets.single(), .001f)
    }

    @Test fun `manual zoom wins while reference is still pending`() {
        val zoom = GuidanceStepSequencer(HandheldHorizonPolicy)
        zoom.reset(0)
        zoom.cancelAutoZoom()
        val out = zoom.onFrame(GuidanceStepSequencer.Input(
            horizonAngleDeg = 0f, subjectHeightFraction = .15f, hasSubject = true,
            expectsSubject = true, currentZoomRatio = 1.4f, minZoomRatio = 1f,
            maxZoomRatio = 4f, nowMs = 500, targetSubjectHeightFraction = .3f
        ), GuidanceCue.NONE, 0f)
        assertNull(out.zoomTarget)
        assertEquals(GuidanceStepSequencer.GuidanceStep.LEVEL, out.step)
    }
}
