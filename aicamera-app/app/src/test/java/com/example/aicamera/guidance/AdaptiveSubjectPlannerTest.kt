package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class AdaptiveSubjectPlannerTest {
    private fun face(id: Long = 1) = StructureObservation(id, id * 200, 1, 1,
        NormalizedBox(.43f, .45f, .57f, .57f), trackingId = 9)

    @Test fun `real face without a template has a reachable target and measured zoom extent`() {
        val target = AdaptiveSubjectPlanner().face(face(), 200)!!
        assertEquals("live_face_rule", target.source)
        assertTrue(target.accepts(face().face!!))
        assertTrue(target.referenceFaceHeight!! > face().face!!.height)
        assertEquals(9, target.subjectTrackingId)
    }

    @Test fun `small detector jitter does not move a locked goal to the opposite third`() {
        val planner = AdaptiveSubjectPlanner()
        val first = planner.face(face(), 200)!!
        val next = face(2).copy(face = NormalizedBox(.42f, .45f, .56f, .57f), trackingId = 10)
        val second = planner.face(next, 400)!!
        assertEquals(first.center, second.center)
        assertEquals(10, second.subjectTrackingId)
    }

    @Test fun `stale and multiple faces cannot produce a goal`() {
        val planner = AdaptiveSubjectPlanner()
        assertNull(planner.face(face(), 2000))
        assertNull(planner.face(face().copy(faceCount = 2), 200))
    }

    @Test fun `detected object without reviewed reference gets a reachable explicitly rule based goal`() {
        val obj = ObjectObservation(1, 1000, 2, 3, 63, NormalizedBox(.4f, .3f, .6f, .6f), .9f, 2, .75f)
        val target = AdaptiveSubjectPlanner().objectTarget(obj, 1000)!!
        assertEquals("live_object_rule", target.source)
        assertTrue(target.accepts(obj))
        assertTrue(target.referenceBox.height > obj.box.height)
        assertNull(AdaptiveSubjectPlanner().objectTarget(obj.copy(stableSamples = 1), 1000))
    }

    @Test fun `following generated face goal yields directions then verified readiness`() {
        val planner = AdaptiveSubjectPlanner()
        val target = planner.face(face(), 200)!!
        val session = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        fun obs(id: Long, x: Float, y: Float) = CompositionSessionController.FrameObservation(
            frameId = id, timestampMs = id * 250, rollDegrees = 0f, isUpright = true,
            subjectCx = x, subjectCy = y, targetCx = target.center.x, targetCy = target.center.y,
            targetTolerance = target.tolerance, modelConfidence = .8f, hasRealSubject = true, expectsSubject = true)
        for (id in 1L..6L) session.observe(obs(id, .5f, .51f))
        assertTrue(session.action.value.toGuidanceCue() in setOf(GuidanceCue.MOVE_LEFT, GuidanceCue.MOVE_DOWN,
            GuidanceCue.MOVE_DOWN_LEFT))
        assertFalse(session.state.value is CompositionSessionController.State.Ready)
        for (id in 7L..15L) session.observe(obs(id, target.center.x, target.center.y))
        assertEquals(CompositionSessionController.State.Ready, session.state.value)
    }

    @Test fun `no target cannot auto capture after any amount of horizontal waiting`() {
        val session = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        for (id in 1L..120L) session.observe(CompositionSessionController.FrameObservation(
            frameId = id, timestampMs = id * 250, rollDegrees = 0f, isUpright = true, modelConfidence = .9f))
        assertFalse(session.state.value is CompositionSessionController.State.Ready)
        assertEquals(GuidanceFeedback.INSUFFICIENT_EVIDENCE, session.decision.value.feedback)
    }

    @Test fun `verification cannot become ready by drifting away from goal`() {
        val session = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        fun obs(id: Long, x: Float) = CompositionSessionController.FrameObservation(
            frameId = id, timestampMs = id * 50, rollDegrees = 0f, isUpright = true,
            subjectCx = x, subjectCy = .5f, targetCx = .5f, targetCy = .5f,
            modelConfidence = .8f, hasRealSubject = true, expectsSubject = true)
        for (id in 1L..6L) session.observe(obs(id, .5f))
        assertEquals(CompositionSessionController.State.Verifying, session.state.value)
        session.observe(obs(7, .7f))
        assertFalse(session.state.value is CompositionSessionController.State.Ready)
    }
}
