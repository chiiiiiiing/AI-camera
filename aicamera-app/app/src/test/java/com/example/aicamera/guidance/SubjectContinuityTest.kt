package com.example.aicamera.guidance

import org.junit.Assert.*
import org.junit.Test

class SubjectContinuityTest {
    private fun frame(id: Long, time: Long = id * 200) = CompositionSessionController.FrameObservation(
        frameId = id, timestampMs = time, viewportVersion = 4, targetViewportVersion = 4,
        subjectCx = .7f, subjectCy = .5f, targetCx = .5f, targetCy = .5f,
        subjectObservationId = id, subjectSampledAtMs = time,
        subjectTrackingId = 2, hasRealSubject = true, expectsSubject = true,
        modelConfidence = .8f, rollDegrees = 0f, isUpright = true)
    private fun missing(id: Long, time: Long = id * 200) = frame(id, time).copy(
        hasRealSubject = false, subjectCx = null, subjectCy = null,
        targetCx = null, targetCy = null, subjectObservationId = 0)

    @Test fun `brief loss retains goal and old evidence identity without certifying a subject`() {
        val continuity = SubjectContinuity()
        continuity.update(frame(1))
        val held = continuity.update(missing(2))
        assertTrue(held.trackingPaused)
        assertFalse(held.hasRealSubject)
        assertEquals(.5f, held.targetCx!!, 0f)
        assertEquals(1L, held.subjectObservationId)
        assertEquals(200L, held.subjectSampledAtMs)
    }

    @Test fun `cached display frames do not extend recovery indefinitely`() {
        val continuity = SubjectContinuity()
        continuity.update(frame(1))
        continuity.update(frame(1).copy(timestampMs = 1000))
        assertTrue(continuity.update(missing(2, 1200)).trackingPaused)
        assertFalse(continuity.update(missing(3, 1500)).trackingPaused)
    }

    @Test fun `new viewport and multiple faces invalidate held subject`() {
        for (changed in listOf(missing(2).copy(viewportVersion = 5), missing(2).copy(faceCount = 2))) {
            val continuity = SubjectContinuity()
            continuity.update(frame(1))
            assertFalse(continuity.update(changed).trackingPaused)
        }
    }

    @Test fun `loss pauses capture while preserving instruction then reacquisition resumes`() {
        val continuity = SubjectContinuity()
        val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        for (id in 1L..6L) controller.observe(continuity.update(frame(id)))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight, controller.action.value)
        controller.observe(continuity.update(missing(7)))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight, controller.action.value)
        assertTrue(controller.state.value is CompositionSessionController.State.Adjusting)
        assertFalse(controller.confirmReadyForCapture(1400))
        for (id in 8L..15L) controller.observe(continuity.update(frame(id).copy(subjectCx = .5f)))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
    }

    @Test fun `loss immediately revokes ready and needs new observations`() {
        val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        val continuity = SubjectContinuity()
        for (id in 1L..12L) controller.observe(continuity.update(frame(id).copy(subjectCx = .5f)))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
        controller.observe(continuity.update(missing(13)))
        assertFalse(controller.state.value is CompositionSessionController.State.Ready)
        assertFalse(controller.confirmReadyForCapture(2600))
    }

    @Test fun `replanned live face target replaces the old direction instead of steering stale coordinates`() {
        val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        for (id in 1L..6L) controller.observe(frame(id).copy(subjectCx = .5f,
            targetCx = .3f, targetId = "live-face:4:1"))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight, controller.action.value)
        for (id in 7L..13L) controller.observe(frame(id).copy(subjectCx = .5f,
            targetCx = .7f, targetId = "live-face:4:2"))
        assertEquals(CompositionSessionController.GuidanceAction.MoveLeft, controller.action.value)
    }

    @Test fun `active movement session retains its goal across long detector loss without refreshing evidence`() {
        val continuity = SubjectContinuity()
        continuity.update(frame(1))
        val later = continuity.update(missing(100, 20000), keepSession = true)
        assertTrue(later.trackingPaused)
        assertEquals(.5f, later.targetCx!!, 0f)
        assertEquals(1L, later.subjectObservationId)
        assertEquals(200L, later.subjectSampledAtMs)
        assertFalse(later.hasRealSubject)
    }

    @Test fun `visual position can steer during detector loss but never triggers automatic capture`() {
        val continuity = SubjectContinuity()
        val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        for (id in 1L..6L) controller.observe(continuity.update(frame(id)))
        val moved = com.example.aicamera.guidance.geometry.NormalizedBox(.2f, .4f, .4f, .6f)
        controller.observe(continuity.update(missing(7), keepSession = true, visualBox = moved))
        assertEquals(CompositionSessionController.GuidanceAction.MoveLeft, controller.action.value)
        val aligned = com.example.aicamera.guidance.geometry.NormalizedBox(.4f, .4f, .6f, .6f)
        for (id in 8L..30L) controller.observe(continuity.update(missing(id), keepSession = true, visualBox = aligned))
        assertFalse(controller.state.value is CompositionSessionController.State.Ready)
        assertFalse(controller.confirmReadyForCapture(6000))
    }

    @Test fun `reacquisition continues toward original goal rather than a new template target`() {
        val continuity = SubjectContinuity()
        continuity.update(frame(1))
        continuity.update(missing(50, 10000), keepSession = true)
        val recovered = continuity.update(frame(51, 10200).copy(targetCx = .8f), keepSession = true)
        assertEquals(.5f, recovered.targetCx!!, 0f)
        assertTrue(recovered.hasRealSubject)
    }
    @Test fun `explicit manual selection can replace a retained goal`() {
        val continuity = SubjectContinuity()
        continuity.update(frame(1).copy(targetId = "original"))
        val selected = continuity.update(frame(2).copy(hasRealSubject = false,
            visualPositionAvailable = true, trackingPaused = true,
            targetCx = .33f, targetId = "manual:1"), keepSession = true)
        assertEquals("manual:1", selected.targetId)
        assertEquals(.33f, selected.targetCx!!, 0f)
        assertFalse(selected.hasRealSubject)
    }
}
