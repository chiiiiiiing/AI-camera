package com.example.aicamera.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionSessionControllerTest {
    private fun activeController() = CompositionSessionController().apply {
        startAnalysis()
        onAnalysisComplete()
    }

    private fun observation(id: Long, confidence: Float = 0.8f) =
        CompositionSessionController.FrameObservation(
            frameId = id,
            timestampMs = id * 100L,
            rollDegrees = 0f,
            pitchDegrees = 0f,
            isUpright = true,
            modelConfidence = confidence
        )

    private fun assertUnverified(controller: CompositionSessionController) {
        assertEquals(
            CompositionSessionController.State.Adjusting(
                CompositionSessionController.GuidanceAction.HoldStill, progress = 0f
            ),
            controller.state.value
        )
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill, controller.action.value)
    }

    @Test
    fun `invalid model confidence never advances a level frame toward capture`() {
        for (confidence in listOf(0f, -0.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val controller = activeController()
            for (id in 1L..20L) controller.observe(observation(id, confidence))
            assertUnverified(controller)
        }
    }

    @Test
    fun `positive confidence retains the existing level verification workflow`() {
        val controller = activeController()
        // Confidence is an availability check, not an arbitrary quality threshold.
        for (id in 1L..4L) controller.observe(observation(id, Float.MIN_VALUE))
        assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
        controller.observe(observation(5L, Float.MIN_VALUE))
        assertEquals(CompositionSessionController.State.Verifying, controller.state.value)
        for (id in 6L..9L) controller.observe(observation(id, Float.MIN_VALUE))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
    }

    @Test
    fun `model failure leaves ready and requires fresh stable observations`() {
        val controller = activeController()
        for (id in 1L..9L) controller.observe(observation(id))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
        controller.observe(observation(10L, 0f))
        assertUnverified(controller)

        // An older healthy frame cannot restore readiness after the newer failure.
        controller.observe(observation(9L))
        assertUnverified(controller)
        for (id in 11L..14L) controller.observe(observation(id))
        assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
        controller.observe(observation(15L))
        assertEquals(CompositionSessionController.State.Verifying, controller.state.value)
        for (id in 16L..19L) controller.observe(observation(id))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
    }

    @Test
    fun `model failure cancels a pending verification timer`() {
        val controller = activeController()
        for (id in 1L..5L) controller.observe(observation(id))
        assertEquals(CompositionSessionController.State.Verifying, controller.state.value)
        controller.observe(observation(6L, Float.NaN))
        assertUnverified(controller)
        controller.observe(observation(10L))
        assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
    }

    @Test
    fun `portrait cannot become ready without a live subject`() {
        val controller = activeController()
        for (id in 1L..20L) {
            controller.observe(observation(id).copy(expectsSubject = true, hasRealSubject = false))
            assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
            assertEquals(CompositionSessionController.GuidanceAction.HoldStill, controller.action.value)
        }
    }

    @Test
    fun `lost portrait exits ready and reacquisition requires new position stability`() {
        val controller = activeController()
        fun portrait(id: Long) = observation(id).copy(
            expectsSubject = true, hasRealSubject = true,
            subjectCx = 0.5f, subjectCy = 0.5f, targetCx = 0.5f, targetCy = 0.5f
        )
        for (id in 1L..10L) controller.observe(portrait(id))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
        controller.observe(portrait(11L).copy(hasRealSubject = false, subjectCx = null, subjectCy = null))
        assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill, controller.action.value)
        controller.observe(portrait(12L))
        assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
        controller.observe(portrait(13L))
        assertEquals(CompositionSessionController.State.Verifying, controller.state.value)
    }

    @Test
    fun `inactive and capture states ignore missing recommendations`() {
        val controller = CompositionSessionController()
        controller.observe(observation(1L, 0f))
        assertEquals(CompositionSessionController.State.Idle, controller.state.value)
        controller.startAnalysis()
        controller.observe(observation(2L, 0f))
        assertEquals(CompositionSessionController.State.Analyzing, controller.state.value)
        controller.startCapture()
        controller.observe(observation(3L, 0f))
        assertEquals(CompositionSessionController.State.Capturing, controller.state.value)
    }
}
