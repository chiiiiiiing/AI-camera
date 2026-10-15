package com.example.aicamera.guidance

import org.junit.Assert.*
import org.junit.Test

class RecommendationFeedbackTest {
    private fun controller() = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
    private fun frame(id: Long, time: Long = id*250, x: Float = .5f) = CompositionSessionController.FrameObservation(
        frameId=id, timestampMs=time, rollDegrees=0f, isUpright=true,
        hasRealSubject=true, expectsSubject=true, faceCount=1, subjectCx=x, subjectCy=.5f,
        targetCx=.5f, targetCy=.5f, targetId="portrait-a", modelConfidence=.8f)

    @Test fun `cached face or cached IMU never accumulate stability`() {
        for (cacheFace in listOf(true,false)) {
            val c=controller()
            for (id in 1L..20L) c.observe(frame(id,time=id*25).let {
                if (cacheFace) it.copy(subjectObservationId=1,subjectSampledAtMs=25)
                else it.copy(imuObservationId=1,imuSampledAtMs=25)
            })
            assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        }
    }

    @Test fun `fresh aligned samples need at least 750 milliseconds`() {
        val c=controller()
        for (id in 1L..20L) c.observe(frame(id,time=id*10))
        assertEquals(CompositionSessionController.State.Verifying,c.state.value)
        c.observe(frame(21,900))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
    }

    @Test fun `expired observations and multiple faces immediately revoke ready`() {
        for (kind in 0..3) {
            val c=controller(); for (id in 1L..12L) c.observe(frame(id))
            assertEquals(CompositionSessionController.State.Ready,c.state.value)
            c.observe(when(kind) {
                0 -> frame(13).copy(subjectSampledAtMs=1)
                1 -> frame(13).copy(imuSampledAtMs=1)
                2 -> frame(13).copy(faceCount=2)
                else -> frame(13).copy(qualityAcceptable=false)
            })
            assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
            assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        }
    }

    @Test fun `new bad evidence revokes ready even when the other sensor is cached`() {
        for (newFace in listOf(true,false)) {
            val c=controller(); for(id in 1L..12L) c.observe(frame(id))
            assertEquals(CompositionSessionController.State.Ready,c.state.value)
            val bad = if(newFace) frame(13,x=.8f).copy(imuObservationId=12,imuSampledAtMs=3000)
                else frame(13).copy(rollDegrees=18f,subjectObservationId=12,subjectSampledAtMs=3000)
            c.observe(bad)
            assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        }
    }

    @Test fun `two worsening real observations stop the instruction`() {
        val c=controller()
        for (id in 1L..6L) c.observe(frame(id,x=.7f))
        c.observe(frame(7,x=.74f)); c.observe(frame(8,x=.78f))
        assertEquals(GuidanceFeedback.WORSENING,c.decision.value.feedback)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
        c.observe(frame(9,x=.79f))
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
    }

    @Test fun `one timeout requests replan and second timeout pauses permanently`() {
        val c=controller(); var requests=0
        for (id in 1L..24L) {
            c.observe(frame(id,x=.7f))
            if(c.decision.value.replanRequested) requests++
        }
        assertEquals(1,requests)
        for (id in 25L..50L) {
            c.observe(frame(id,x=.7f).copy(targetId="portrait-b",targetCx=.48f))
            if(c.decision.value.replanRequested) requests++
        }
        assertEquals(1,requests)
        assertEquals(GuidanceFeedback.NO_PROGRESS,c.decision.value.feedback)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
    }

    @Test fun `viewport change rejects stale target and new target rebuilds stability`() {
        val c=controller(); for(id in 1L..12L) c.observe(frame(id))
        c.observe(frame(13).copy(viewportVersion=1,targetViewportVersion=0))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        c.observe(frame(14).copy(viewportVersion=1,targetViewportVersion=1))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
    }

    @Test fun `candidate fluctuation does not reverse locked instruction`() {
        val c=controller(); for(id in 1L..6L) c.observe(frame(id,x=.7f))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight,c.action.value)
        c.observe(frame(7,x=.7f).copy(targetId="different",targetCx=.9f))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight,c.action.value)
    }

    @Test fun `new tracking identity and long sampling gaps cannot inherit ready`() {
        val c=controller()
        for(id in 1L..12L) c.observe(frame(id).copy(subjectTrackingId=11))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
        c.observe(frame(13).copy(subjectTrackingId=22))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        for(id in 14L..25L) c.observe(frame(id).copy(subjectTrackingId=22))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
        c.observe(frame(26,10_000).copy(subjectTrackingId=22))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
    }

    @Test fun `raw tilt prevents smoothed angle from certifying readiness`() {
        val c=controller(); for(id in 1L..12L) c.observe(frame(id))
        c.observe(frame(13).copy(rollDegrees=1f,rawRollDegrees=15f))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        assertEquals(CompositionSessionController.GuidanceAction.RotateCounterClockwise,c.action.value)
    }
}
