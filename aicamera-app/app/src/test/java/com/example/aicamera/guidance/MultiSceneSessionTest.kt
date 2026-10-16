package com.example.aicamera.guidance

import org.junit.Assert.*
import org.junit.Test

class MultiSceneSessionTest {
    private fun controller()=CompositionSessionController().apply{startAnalysis();onAnalysisComplete()}
    private fun objectFrame(id: Long, x: Float=.5f, target: String?="object:cup")=CompositionSessionController.FrameObservation(
        frameId=id,timestampMs=id*1000,rollDegrees=0f,isUpright=true,
        subjectCx=x,subjectCy=.5f,targetCx=.5f,targetCy=.5f,targetTolerance=.06f,
        expectsSubject=true,hasRealSubject=true,modelConfidence=.85f,
        subjectObservationId=id,subjectTrackingId=7,subjectSampledAtMs=id*1000,
        imuObservationId=id,imuSampledAtMs=id*1000,faceCount=0,
        viewportVersion=3,targetViewportVersion=3,targetId=target,observedAnchorKind=ObservedAnchorKind.OBJECT)

    @Test fun `real object positions guide without inventing a face and fresh frames verify`() {
        val c=controller()
        for(id in 1L..5) c.observe(objectFrame(id,.64f))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight,c.action.value)
        for(id in 6L..12) c.observe(objectFrame(id))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
    }
    @Test fun `flat lay uses a fresh unobservable roll measurement instead of upside down`() {
        val c=controller()
        for(id in 1L..8) c.observe(objectFrame(id).copy(rollDegrees=null,rawRollDegrees=110f,isUpright=false,rollObservable=false))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
        val missing=controller()
        for(id in 1L..8) missing.observe(objectFrame(id).copy(rollDegrees=null,rollObservable=false,imuObservationId=0))
        assertNotEquals(CompositionSessionController.State.Ready,missing.state.value)
    }
    @Test fun `cached object detection cannot create readiness despite fresh IMU`() {
        val c=controller()
        c.observe(objectFrame(1))
        for(id in 2L..12) c.observe(objectFrame(id).copy(timestampMs=1000+id*40,subjectObservationId=1,subjectSampledAtMs=1000,imuSampledAtMs=1000+id*40))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
    }
    @Test fun `expired evidence and incompatible viewport revoke a ready object target`() {
        for(invalid in listOf(objectFrame(9).copy(subjectSampledAtMs=7000),objectFrame(9).copy(targetViewportVersion=2))) {
            val c=controller(); for(id in 1L..8) c.observe(objectFrame(id))
            assertEquals(CompositionSessionController.State.Ready,c.state.value)
            c.observe(invalid)
            assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        }
    }
    @Test fun `replacement target resets lock and does not keep guiding old coordinates`() {
        val c=controller(); for(id in 1L..8) c.observe(objectFrame(id))
        c.observe(objectFrame(9,target="object:new").copy(targetCx=.7f))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        for(id in 10L..13) c.observe(objectFrame(id,target="object:new").copy(targetCx=.7f))
        assertEquals(CompositionSessionController.GuidanceAction.MoveLeft,c.action.value)
    }
    @Test fun `scene change and missing object target cannot retain readiness`() {
        val c=controller(); for(id in 1L..8) c.observe(objectFrame(id))
        for(id in 9L..14) c.observe(objectFrame(id,target=null).copy(expectsSubject=false,hasRealSubject=false,
            subjectCx=null,subjectCy=null,targetCx=null,targetCy=null,observedAnchorKind=ObservedAnchorKind.NONE))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
    }
    @Test fun `same class new tracking identity rebuilds independent stable evidence`() {
        val c=controller(); for(id in 1L..8) c.observe(objectFrame(id))
        c.observe(objectFrame(9).copy(subjectTrackingId=8))
        assertNotEquals(CompositionSessionController.State.Ready,c.state.value)
    }
    @Test fun `two worsening object samples pause rather than repeat an ineffective action`() {
        val c=controller()
        c.observe(objectFrame(1,.61f));c.observe(objectFrame(2,.65f));c.observe(objectFrame(3,.69f))
        assertEquals(GuidanceFeedback.WORSENING,c.decision.value.feedback)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
    }
    @Test fun `one replan budget survives replacement targets for the same tracked object`() {
        val c=controller()
        for(id in 1L..10) c.observe(objectFrame(id,.7f))
        assertTrue(c.decision.value.replanRequested)
        for(id in 11L..20) c.observe(objectFrame(id,.7f,"object:second"))
        assertEquals(GuidanceFeedback.NO_PROGRESS,c.decision.value.feedback)
        assertFalse(c.decision.value.replanRequested)
        c.observe(objectFrame(21,.7f,"object:third"))
        assertFalse(c.decision.value.replanRequested)
    }
}
