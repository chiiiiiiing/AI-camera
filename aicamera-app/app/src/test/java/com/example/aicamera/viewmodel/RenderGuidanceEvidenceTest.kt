package com.example.aicamera.viewmodel

import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import com.example.aicamera.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class RenderGuidanceEvidenceTest {
    @Test fun `manual region provides a movement goal without pretending to be a detector observation`() {
        val output = FakeInferenceOutput(SceneType.NORMAL, CompositionStrategy.ADAPTIVE, .8f, 128f, emptyList(),
            visualSubjectBox = NormalizedBox(.2f, .2f, .4f, .4f), visualSampledAtMs = 0,
            manualTarget = NormalizedPoint(.6f, .5f), manualSelectionId = 7)
        val actual = observation(output)
        assertTrue(actual.trackingPaused)
        assertTrue(actual.visualPositionAvailable)
        assertFalse(actual.hasRealSubject)
        assertEquals("manual:7", actual.targetId)
        assertEquals(.6f, actual.targetCx!!, 0f)
    }
    private fun observation(lines: List<FakeGuideLine>): CompositionSessionController.FrameObservation {
        val output = FakeInferenceOutput(SceneType.NORMAL, CompositionStrategy.RULE_OF_THIRDS,
            0.8f, 128f, lines, expectsSubject = true)
        return observation(output)
    }
    private fun observation(output: FakeInferenceOutput, portrait: Boolean = false): CompositionSessionController.FrameObservation {
        val result = PipelineResult(1, 0, false, output, FrameTiming(1, 0, 0, 0, 0))
        val method = RenderViewModel::class.java.getDeclaredMethod("buildFrameObservation",
            FakeInferenceOutput::class.java, PipelineResult::class.java).apply { isAccessible = true }
        return method.invoke(RenderViewModel().apply { setPortraitGuide(portrait) }, output, result) as CompositionSessionController.FrameObservation
    }

    @Test fun `explicit portrait mode retains a real face when retrieval misclassifies the scene`() {
        val layout = SceneLayout(List(9) { .5f }, List(9) { .1f }, .5f, 0f, 0f)
        val output = FakeInferenceOutput(SceneType.NORMAL, CompositionStrategy.ADAPTIVE, .9f, 128f,
            emptyList(), structureObservation = StructureObservation(1, 0, 7, 1,
                NormalizedBox(.3f, .2f, .5f, .5f)),
            sceneAnalysis = SceneAnalysisObservation(1, 0, 7, SceneFamily.STILL_LIFE, layout), aiInvolved = true)
        val actual = observation(output, portrait = true)
        assertTrue(actual.hasRealSubject)
        assertTrue(actual.expectsSubject)
        assertEquals(ObservedAnchorKind.FACE, actual.observedAnchorKind)
        assertEquals(.9f, actual.modelConfidence, 0f)
    }

    @Test fun `demo output cannot provide automatic capture confidence`() {
        val output = FakeInferenceOutput(SceneType.NORMAL, CompositionStrategy.ADAPTIVE, .9f, 128f, emptyList())
        assertEquals(0f, observation(output).modelConfidence, 0f)
    }

    @Test fun `landscape observation never promotes an incidental face to the capture target`() {
        val layout=SceneLayout(List(9){.5f},List(9){.1f},.5f,0f,0f)
        val face=StructureObservation(1,0,7,1,NormalizedBox(.3f,.2f,.5f,.5f))
        val output=FakeInferenceOutput(SceneType.NORMAL,CompositionStrategy.ADAPTIVE,.9f,128f,emptyList(),
            structureObservation=face,sceneAnalysis=SceneAnalysisObservation(1,0,7,SceneFamily.NATURAL_LANDSCAPE,layout))
        val actual=observation(output)
        assertFalse(actual.hasRealSubject);assertEquals(0,actual.faceCount)
        assertEquals(ObservedAnchorKind.NONE,actual.observedAnchorKind)
        assertNull(actual.targetCx)
    }

    @Test fun `object bridge carries independent detection identity while face count remains zero`() {
        val layout=SceneLayout(List(9){.5f},List(9){.1f},.5f,0f,0f)
        val box=NormalizedBox(.2f,.2f,.6f,.7f)
        val obj=ObjectObservation(17,0,9,3,41,box,.9f,2,.75f)
        val target=ObjectCompositionTarget("object:cup",NormalizedPoint(.5f,.5f),9,3,41,box)
        val output=FakeInferenceOutput(SceneType.NORMAL,CompositionStrategy.ADAPTIVE,.9f,128f,emptyList(),
            sceneAnalysis=SceneAnalysisObservation(4,0,9,SceneFamily.FOOD,layout,obj,true),objectTarget=target,rollObservable=false)
        val actual=observation(output)
        assertTrue(actual.hasRealSubject);assertEquals(0,actual.faceCount)
        assertEquals(ObservedAnchorKind.OBJECT,actual.observedAnchorKind)
        assertEquals(17L,actual.subjectObservationId);assertEquals("object:cup",actual.targetId)
        assertEquals(.5f,actual.targetCx!!,0f);assertFalse(actual.rollObservable)
    }

    private fun target(kind: String? = null, visible: Boolean = true) = FakeGuideLine("target_zone", visible,
        mapOf("center_x" to .4f, "center_y" to .3f, "radius" to .08f,
            "anchor_kind" to (kind ?: "subject"), "coordinate_aspect_ratio" to .75f))

    @Test
    fun `face observations never receive generic whole subject targets`() {
        assertNull(observation(listOf(target())).targetCx)
        assertNull(observation(listOf(target("face", visible = false))).targetCx)
        val face = observation(listOf(target("face")))
        assertEquals(.4f, face.targetCx!!, 0f)
        assertEquals(.3f, face.targetCy!!, 0f)
        assertEquals(.75f, face.coordinateAspectRatio, 0f)
    }

    @Test
    fun `raw pose observations do not invent target pose`() {
        val pose = FakeGuideLine("horizon_level", true, mapOf(
            "angle_degrees" to 0f, "pitch_degrees" to -45f, "yaw_degrees" to 123f,
            "is_upright" to true, "target_pitch_degrees" to 0f))
        val untrusted = observation(listOf(pose))
        assertEquals(-45f, untrusted.pitchDegrees!!, 0f)
        assertNull(untrusted.pitchTargetDegrees)
        assertNull(untrusted.yawTargetDegrees)
        val trusted = observation(listOf(pose.copy(params = pose.params + mapOf(
            "pose_target_verified" to true, "target_pitch_degrees" to -45f))))
        assertEquals(-45f, trusted.pitchTargetDegrees!!, 0f)
        assertNull(trusted.yawTargetDegrees)
    }

    @Test
    fun `hidden and nonfinite subject boxes cannot provide live subject evidence`() {
        val box = FakeGuideLine("subject_box", false, mapOf(
            "real" to true, "anchor_kind" to "face", "x" to .3f, "y" to .2f, "width" to .2f, "height" to .2f))
        assertFalse(observation(listOf(box)).hasRealSubject)
        assertFalse(observation(listOf(box.copy(visible = true,
            params = box.params + ("width" to Float.NaN)))).hasRealSubject)
        assertTrue(observation(listOf(box.copy(visible = true))).hasRealSubject)
    }

    @Test
    fun `face targets reject whole body and out of viewport detections`() {
        val box = FakeGuideLine("subject_box", true, mapOf(
            "real" to true, "x" to .3f, "y" to .2f, "width" to .2f, "height" to .2f))
        assertFalse(observation(listOf(target("face"), box)).hasRealSubject)
        assertFalse(observation(listOf(target("face"), box.copy(params = box.params + mapOf(
            "anchor_kind" to "body")))).hasRealSubject)
        assertFalse(observation(listOf(target("face"), box.copy(params = box.params + mapOf(
            "anchor_kind" to "face", "x" to .95f)))).hasRealSubject)
        assertTrue(observation(listOf(target("face"), box.copy(params = box.params + mapOf(
            "anchor_kind" to "face")))).hasRealSubject)
    }
}
