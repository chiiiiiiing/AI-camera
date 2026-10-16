package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class ExecutionQualityGuardTest {
    private fun sample(id:Long)=ExecutionQualityEvidence(id,id*500,1,SceneFamily.FOOD,4,
        NormalizedBox(.3f,.3f,.7f,.7f),.5f,.2f,.15f,.01f,.02f)
    @Test fun `same image cache cannot count as an independent outcome`() {
        val g=ExecutionQualityGuard()
        assertEquals(ExecutionComparison.INITIAL,g.compare(sample(1)))
        assertEquals(ExecutionComparison.CACHED,g.compare(sample(1)))
        assertEquals(ExecutionComparison.COMPARABLE,g.compare(sample(2)))
    }
    @Test fun `nonfinite motion and highlight evidence is not fresh usable quality`() {
        assertFalse(sample(1).copy(backgroundDx=Float.NaN).isFresh(500))
        assertFalse(sample(1).copy(subjectHighlights=Float.POSITIVE_INFINITY).isFresh(500))
    }
    @Test fun `auto exposure settling is not credited to guidance`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        assertEquals(ExecutionComparison.CONTEXT_CHANGED,g.compare(sample(2).copy(meanLuma=.62f)))
        assertEquals(ExecutionComparison.COMPARABLE,g.compare(sample(3).copy(meanLuma=.62f)))
    }
    @Test fun `subject movement without background motion cannot count as camera execution`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        val moved=sample(2).copy(subjectBox=NormalizedBox(.35f,.3f,.75f,.7f),backgroundDx=0f,backgroundDy=0f,comparedObservationId=1)
        assertEquals(ExecutionComparison.UNATTRIBUTED_MOTION,g.compare(moved))
    }
    @Test fun `consistent background shift permits comparison of executed pan`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        val moved=sample(2).copy(subjectBox=NormalizedBox(.35f,.3f,.75f,.7f),backgroundDx=.05f,backgroundDy=0f,comparedObservationId=1)
        assertEquals(ExecutionComparison.COMPARABLE,g.compare(moved))
    }
    @Test fun `blur and clipped subject independently veto a closer target`() {
        val bad=listOf(sample(2).copy(subjectSharpness=.06f),sample(2).copy(subjectHighlights=.15f))
        for(candidate in bad) {
            val g=ExecutionQualityGuard();g.compare(sample(1))
            assertEquals(ExecutionComparison.QUALITY_REGRESSED,g.compare(candidate))
        }
    }
    @Test fun `background detail is soft in still life and not a landscape defect`() {
        for(family in listOf(SceneFamily.FOOD,SceneFamily.NATURAL_LANDSCAPE,SceneFamily.URBAN_LANDSCAPE)) {
            val g=ExecutionQualityGuard();g.compare(sample(1).copy(family=family))
            assertEquals(ExecutionComparison.COMPARABLE,g.compare(sample(2).copy(family=family,backgroundEdges=.08f)))
            assertEquals(family==SceneFamily.FOOD,"background_detail_increased_soft" in g.lastReasons)
        }
    }
    @Test fun `observed subject moving into a narrow border fails protection`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        assertEquals(ExecutionComparison.QUALITY_REGRESSED,g.compare(sample(2).copy(subjectBox=NormalizedBox(.02f,.3f,.42f,.7f))))
        assertTrue("observed_subject_margin_regressed" in g.lastReasons)
    }
    @Test fun `slow autonomous subject motion never claims successful camera movement`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        for(id in 2L..7L) {
            val shift=(id-1)*.02f
            assertEquals(ExecutionComparison.UNATTRIBUTED_MOTION,g.compare(sample(id).copy(
                subjectBox=NormalizedBox(.3f+shift,.3f,.7f+shift,.7f),backgroundDx=0f,backgroundDy=0f,comparedObservationId=id-1)))
        }
    }
    @Test fun `subthreshold movement accumulates instead of evading the motion guard`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        val results=(2L..6L).map { id ->
            val shift=(id-1)*.004f
            g.compare(sample(id).copy(subjectBox=NormalizedBox(.3f+shift,.3f,.7f+shift,.7f),
                backgroundDx=0f,backgroundDy=0f,comparedObservationId=id-1))
        }
        assertTrue(ExecutionComparison.UNATTRIBUTED_MOTION in results)
    }
    @Test fun `bounded stationary detector jitter cancels and still permits verification`() {
        val c=active()
        val offsets=listOf(0f,.003f,-.004f,.005f,-.002f,.002f,-.001f,0f,.003f,-.003f,0f,.001f)
        for((index,offset) in offsets.withIndex()) {
            val id=index+1L
            c.observe(observation(id).copy(subjectCx=.5f+offset,executionQuality=sample(id).copy(
                subjectBox=NormalizedBox(.3f+offset,.3f,.7f+offset,.7f),backgroundDx=0f,backgroundDy=0f,
                comparedObservationId=id-1)))
            assertFalse(c.decision.value.feedback==GuidanceFeedback.SCENE_CHANGED)
        }
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
    }
    @Test fun `image observations between detector samples do not break camera motion attribution`() {
        val g=ExecutionQualityGuard();g.compare(sample(1).copy(subjectObservationId=1))
        assertEquals(ExecutionComparison.COMPARABLE,g.compare(sample(2).copy(subjectObservationId=1,backgroundDx=.02f,
            backgroundDy=0f,comparedObservationId=1)))
        assertEquals(ExecutionComparison.COMPARABLE,g.compare(sample(3).copy(subjectObservationId=2,
            subjectBox=NormalizedBox(.34f,.3f,.74f,.7f),backgroundDx=.04f,backgroundDy=0f,comparedObservationId=1)))
    }
    @Test fun `gradual quality loss is compared to the original action baseline`() {
        val g=ExecutionQualityGuard();g.compare(sample(1))
        assertEquals(ExecutionComparison.COMPARABLE,g.compare(sample(2).copy(subjectSharpness=.12f)))
        assertEquals(ExecutionComparison.QUALITY_REGRESSED,g.compare(sample(3).copy(subjectSharpness=.10f)))
    }
    @Test fun `validated learned preference can veto but different models cannot be compared`() {
        val g=ExecutionQualityGuard();g.compare(sample(1).copy(preferenceScore=1f,preferenceModelId="one"))
        assertEquals(ExecutionComparison.QUALITY_REGRESSED,g.compare(sample(2).copy(preferenceScore=.8f,preferenceModelId="one")))
        val other=ExecutionQualityGuard();other.compare(sample(1).copy(preferenceScore=1f,preferenceModelId="one"))
        assertEquals(ExecutionComparison.COMPARABLE,other.compare(sample(2).copy(preferenceScore=.2f,preferenceModelId="two")))
    }
    @Test fun `new lens scene or tracked subject invalidates previous comparison`() {
        for(next in listOf(sample(2).copy(viewportVersion=2),sample(2).copy(family=SceneFamily.STILL_LIFE),sample(2).copy(subjectTrackingId=5))) {
            val g=ExecutionQualityGuard();g.compare(sample(1));assertEquals(ExecutionComparison.CONTEXT_CHANGED,g.compare(next))
        }
    }
    private fun active()=CompositionSessionController().apply{startAnalysis();onAnalysisComplete()}
    private fun observation(id:Long)=CompositionSessionController.FrameObservation(frameId=id,timestampMs=id*500,
        rollDegrees=0f,isUpright=true,subjectCx=.5f,subjectCy=.5f,targetCx=.5f,targetCy=.5f,expectsSubject=true,
        hasRealSubject=true,modelConfidence=.8f,viewportVersion=1,targetViewportVersion=1,subjectTrackingId=4,
        targetId="subject",observedAnchorKind=ObservedAnchorKind.OBJECT,executionQualityRequired=true,executionQuality=sample(id))
    @Test fun `stable good original framing verifies without requiring an aesthetic model`() {
        val c=active();for(i in 1L..10)c.observe(observation(i))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
    }
    @Test fun `fresh technical regression revokes ready and pauses the target`() {
        val c=active();for(i in 1L..10)c.observe(observation(i))
        c.observe(observation(11).copy(executionQuality=sample(11).copy(subjectHighlights=.2f)))
        assertEquals(GuidanceFeedback.WORSENING,c.decision.value.feedback)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
        for(i in 12L..20)c.observe(observation(i))
        assertFalse(c.state.value is CompositionSessionController.State.Ready)
    }
    @Test fun `cached image quality cannot verify newer face and sensor samples`() {
        val c=active();for(i in 1L..8)c.observe(observation(i).copy(executionQuality=sample(1).copy(sampledAtMs=i*500)))
        assertFalse(c.state.value is CompositionSessionController.State.Ready)
    }
    @Test fun `new bad image immediately revokes ready while detector and imu are cached`() {
        val c=active();for(i in 1L..10)c.observe(observation(i))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
        c.observe(observation(11).copy(subjectObservationId=10,imuObservationId=10,
            executionQuality=sample(11).copy(subjectSharpness=.06f)))
        assertEquals(GuidanceFeedback.WORSENING,c.decision.value.feedback)
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill,c.action.value)
    }
    @Test fun `exposure change pauses and fresh stable observations can recover`() {
        val c=active();for(i in 1L..8)c.observe(observation(i))
        c.observe(observation(9).copy(executionQuality=sample(9).copy(meanLuma=.62f)))
        assertEquals(GuidanceFeedback.SCENE_CHANGED,c.decision.value.feedback)
        for(i in 10L..20)c.observe(observation(i).copy(executionQuality=sample(i).copy(meanLuma=.62f)))
        assertEquals(CompositionSessionController.State.Ready,c.state.value)
    }
    @Test fun `asynchronous autofocus return must recheck ready and freshness`() {
        val c=active();for(i in 1L..10)c.observe(observation(i))
        assertTrue(c.confirmReadyForCapture(5_100))
        assertFalse(c.confirmReadyForCapture(6_400))
        assertFalse(c.state.value is CompositionSessionController.State.Ready)
    }
    @Test fun `bad frame while autofocus runs cannot be captured by an old ready callback`() {
        val c=active();for(i in 1L..10)c.observe(observation(i))
        c.observe(observation(11).copy(executionQuality=sample(11).copy(subjectSharpness=.01f)))
        assertFalse(c.confirmReadyForCapture(5_510))
    }
}
