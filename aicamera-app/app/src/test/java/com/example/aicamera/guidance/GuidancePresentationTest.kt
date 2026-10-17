package com.example.aicamera.guidance

import com.example.aicamera.guidance.CompositionSessionController.GuidanceAction
import com.example.aicamera.guidance.CompositionSessionController.State
import com.example.aicamera.guidance.geometry.NormalizedBox
import com.example.aicamera.overlay.model.GuideLineType
import org.junit.Assert.*
import org.junit.Test

class GuidancePresentationTest {
    private val evidence = GuidanceDisplayEvidence(1000,1750,7,ObservedAnchorKind.OBJECT,
        NormalizedPoint(.7f,.5f),NormalizedBox(.6f,.4f,.8f,.6f),NormalizedPoint(.5f,.5f),.06f,0f)
    private fun decision(action:GuidanceAction=GuidanceAction.MoveRight, feedback:GuidanceFeedback=GuidanceFeedback.ADJUSTING)=
        GuidanceDecision(action,"object:cup",.2f,feedback,3,displayEvidence=evidence)

    @Test fun `object geometry follows decision without requiring face lines`() {
        val p=GuidancePresentation.from(State.Adjusting(GuidanceAction.MoveRight),decision(),1100)
        assertEquals(GuidanceCue.MOVE_RIGHT,p.cue)
        assertEquals(.7f,p.evidence!!.subject!!.x,0f)
        assertEquals("object",p.guideLines.first{it.type==GuideLineType.SUBJECT_BOX}.params["anchor_kind"])
        assertFalse(p.ready)
    }
    @Test fun `verifying never displays success even with valid aligned geometry`() {
        val p=GuidancePresentation.from(State.Verifying,decision(GuidanceAction.HoldStill,GuidanceFeedback.VERIFYING),1100)
        assertTrue(p.verifying);assertFalse(p.ready);assertEquals(GuidanceCue.NONE,p.cue)
        assertFalse(p.guideLines.first{it.type==GuideLineType.SUBJECT_BOX}.params["aligned"] as Boolean)
    }
    @Test fun `ready requires matching final decision and expires without another camera frame`() {
        val d=decision(GuidanceAction.Ready,GuidanceFeedback.READY)
        assertTrue(GuidancePresentation.from(State.Ready,d,1750).ready)
        val expired=GuidancePresentation.from(State.Ready,d,1751)
        assertFalse(expired.ready);assertTrue(expired.paused);assertTrue(expired.guideLines.isEmpty())
        assertFalse(GuidancePresentation.from(State.Ready,decision(GuidanceAction.HoldStill,GuidanceFeedback.VERIFYING),1100).ready)
    }
    @Test fun `pause clears old target and cannot reverse the action`() {
        for(feedback in listOf(GuidanceFeedback.WORSENING,GuidanceFeedback.NO_PROGRESS,
            GuidanceFeedback.SCENE_CHANGED,GuidanceFeedback.INSUFFICIENT_EVIDENCE)) {
            val p=GuidancePresentation.from(State.Adjusting(GuidanceAction.HoldStill),
                decision(GuidanceAction.HoldStill,feedback),1100)
            assertEquals(GuidanceCue.NONE,p.cue);assertNull(p.evidence);assertTrue(p.paused)
        }
    }
    @Test fun `asynchronous action state mismatch fails closed`() {
        val p=GuidancePresentation.from(State.Adjusting(GuidanceAction.MoveLeft),decision(),1100)
        assertTrue(p.paused);assertEquals(GuidanceCue.NONE,p.cue)
    }
    @Test fun `capture save cancel do not inherit old ready display`() {
        for(state in listOf(State.Capturing,State.Saved("photo"),State.Idle,State.Error("failed"))) {
            val p=GuidancePresentation.from(state,decision(GuidanceAction.Ready,GuidanceFeedback.READY),1100)
            assertEquals(state,p.state);assertFalse(p.ready);assertNull(p.evidence)
        }
    }
    @Test fun `controller presentation uses locked target and invalidates on missing evidence`() {
        val c=CompositionSessionController().apply{startAnalysis();onAnalysisComplete()}
        fun frame(i:Long,targetX:Float=.5f)=CompositionSessionController.FrameObservation(
            frameId=i,timestampMs=i*200,rollDegrees=0f,isUpright=true,modelConfidence=.9f,
            subjectCx=.7f,subjectCy=.5f,targetCx=targetX,targetCy=.5f,hasRealSubject=true,expectsSubject=true,
            targetId="portrait",subjectBox=NormalizedBox(.6f,.4f,.8f,.6f))
        for(i in 1L..6)c.observe(frame(i))
        c.observe(frame(7,.3f))
        assertEquals(.5f,c.decision.value.displayEvidence!!.target!!.x,0f)
        c.observe(frame(8).copy(hasRealSubject=false))
        assertNull(c.decision.value.displayEvidence)
    }
}
