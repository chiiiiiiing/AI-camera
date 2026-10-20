package com.example.aicamera.guidance

import org.junit.Assert.*
import org.junit.Test

class TargetDwellCaptureTest {
    private fun controller() = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
    private fun frame(time: Long, x: Float = .58f, visual: Boolean = true) = CompositionSessionController.FrameObservation(
        frameId = time, timestampMs = time, subjectCx = x, subjectCy = .5f, targetCx = .5f, targetCy = .5f,
        targetTolerance = .12f, targetId = "locked", targetDwellCapture = true,
        trackingPaused = visual, visualPositionAvailable = visual, visualPositionSampledAtMs = time,
        subjectSampledAtMs = time, subjectObservationId = time, hasRealSubject = !visual,
        expectsSubject = true, rollDegrees = 0f, rawRollDegrees = 0f, isUpright = true,
        imuObservationId = time, imuSampledAtMs = time, modelConfidence = .8f,
        observedAnchorKind = ObservedAnchorKind.OBJECT, viewportVersion = 1, targetViewportVersion = 1)

    @Test fun `tracked position inside circle captures after one second without exact overlap`() {
        val c = controller()
        for (t in 1000L..1800L step 200) c.observe(frame(t))
        assertEquals(CompositionSessionController.State.Verifying, c.state.value)
        assertFalse(c.confirmReadyForCapture(1800))
        c.observe(frame(2000))
        assertEquals(CompositionSessionController.State.Ready, c.state.value)
        assertTrue(c.confirmReadyForCapture(2000))
    }
    @Test fun `real detector position uses the same one second circle policy`() {
        val c = controller()
        for (t in 1000L..2000L step 200) c.observe(frame(t, visual = false))
        assertEquals(CompositionSessionController.State.Ready, c.state.value)
        assertTrue(c.confirmReadyForCapture(2000))
    }
    @Test fun `leaving circle resets dwell rather than accumulating separate visits`() {
        val c = controller()
        for (t in 1000L..1800L step 200) c.observe(frame(t))
        c.observe(frame(2000, x = .7f))
        for (t in 2200L..3000L step 200) c.observe(frame(t))
        assertFalse(c.state.value is CompositionSessionController.State.Ready)
        c.observe(frame(3200))
        assertEquals(CompositionSessionController.State.Ready, c.state.value)
    }
    @Test fun `cached or lost visual position cannot trigger shutter`() {
        val c = controller()
        for (t in 1000L..2400L step 200) c.observe(frame(t).copy(visualPositionSampledAtMs = 1000))
        assertFalse(c.state.value is CompositionSessionController.State.Ready)
        c.observe(frame(2600).copy(visualPositionAvailable = false))
        assertFalse(c.confirmReadyForCapture(2600))
    }
    @Test fun `loss during autofocus or stale position revokes capture permission`() {
        val c = controller()
        for (t in 1000L..2000L step 200) c.observe(frame(t))
        assertFalse(c.confirmReadyForCapture(2800))
        val next = controller()
        for (t in 1000L..2000L step 200) next.observe(frame(t))
        next.observe(frame(2200).copy(visualPositionAvailable = false))
        assertFalse(next.confirmReadyForCapture(2200))
    }
    @Test fun `tilt zoom or wrong viewport never certify the target circle`() {
        for (transform in listOf<(CompositionSessionController.FrameObservation) -> CompositionSessionController.FrameObservation>(
            { it.copy(rawRollDegrees = 15f) }, { it.copy(zoomInProgress = true) }, { it.copy(targetViewportVersion = 2) }
        )) {
            val c = controller()
            for (t in 1000L..2400L step 200) c.observe(transform(frame(t)))
            assertFalse(c.state.value is CompositionSessionController.State.Ready)
        }
    }
    @Test fun `viewport changes cannot inherit time already spent inside target`() {
        val c = controller()
        for (t in 1000L..1800L step 200) c.observe(frame(t))
        c.observe(frame(2000).copy(viewportVersion = 2, targetViewportVersion = 2))
        assertEquals(CompositionSessionController.State.Verifying, c.state.value)
    }
    @Test fun `manual region gets same automatic capture policy without pretending to be semantic AI`() {
        val c = controller()
        for (t in 1000L..2000L step 200) c.observe(frame(t).copy(targetId = "manual:7", modelConfidence = 0f))
        assertEquals(CompositionSessionController.State.Ready, c.state.value)
        assertTrue(c.confirmReadyForCapture(2000))
    }
    @Test fun `visible target uses a circle and short edge radius rather than a square or center pixel`() {
        val circleCorner = controller()
        val landscape = controller()
        val nearEdge = controller()
        for (t in 1000L..2400L step 200) {
            circleCorner.observe(frame(t, x = .6f).copy(subjectCy = .6f))
            landscape.observe(frame(t).copy(coordinateAspectRatio = 2f))
            nearEdge.observe(frame(t, x = .61f))
        }
        assertFalse(circleCorner.state.value is CompositionSessionController.State.Ready)
        assertFalse(landscape.state.value is CompositionSessionController.State.Ready)
        assertEquals(CompositionSessionController.State.Ready, nearEdge.state.value)
    }
    @Test fun `detector misses do not reset dwell when current visual tracking continues`() {
        val c = controller()
        for (t in 1000L..1400L step 200) c.observe(frame(t, visual = false))
        for (t in 1600L..2000L step 200) c.observe(frame(t))
        assertEquals(CompositionSessionController.State.Ready, c.state.value)
        assertTrue(c.confirmReadyForCapture(2000))
    }
    @Test fun `fresh image tracking with stale orientation cannot auto capture`() {
        val c = controller()
        for (t in 3000L..4400L step 200) c.observe(frame(t).copy(imuSampledAtMs = 1000))
        assertFalse(c.confirmReadyForCapture(4400))
    }
}
