package com.example.aicamera.viewmodel

import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import com.example.aicamera.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class RenderGuidanceEvidenceTest {
    private fun observation(lines: List<FakeGuideLine>): CompositionSessionController.FrameObservation {
        val output = FakeInferenceOutput(SceneType.NORMAL, CompositionStrategy.RULE_OF_THIRDS,
            0.8f, 128f, lines, expectsSubject = true)
        return observation(output)
    }
    private fun observation(output: FakeInferenceOutput): CompositionSessionController.FrameObservation {
        val result = PipelineResult(1, 0, false, output, FrameTiming(1, 0, 0, 0, 0))
        val method = RenderViewModel::class.java.getDeclaredMethod("buildFrameObservation",
            FakeInferenceOutput::class.java, PipelineResult::class.java).apply { isAccessible = true }
        return method.invoke(RenderViewModel(), output, result) as CompositionSessionController.FrameObservation
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

    private val lookingFace = StructureObservation(1, 0, 7, 1,
        NormalizedBox(.45f, .2f, .65f, .6f), faceYawDegrees = 22f, trackingId = 9,
        facingDirectionX = 1f)

    private fun lookRoomOutput(face: StructureObservation? = lookingFace,
                               targetX: Float = .4f): FakeInferenceOutput {
        val target = CompositionTarget("rule:face:look-room", NormalizedPoint(targetX, .4f), .06f, 7,
            "photography_technique_facing_space", subjectTrackingId = 9, requiredFacingDirectionX = 1f)
        return FakeInferenceOutput(SceneType.NORMAL, CompositionStrategy.ADAPTIVE, .9f, 128f,
            listOf(FakeGuideLine("target_zone", true, mapOf("center_x" to targetX,
                "center_y" to .4f, "radius" to .06f, "anchor_kind" to "face"))),
            expectsSubject = true, structureObservation = face, compositionTarget = target)
    }

    private fun timed(frame: CompositionSessionController.FrameObservation, id: Long) = frame.copy(
        frameId = id, timestampMs = id * 250, subjectSampledAtMs = id * 250,
        subjectObservationId = id, imuObservationId = id, imuSampledAtMs = id * 250,
        rollDegrees = 0f, rawRollDegrees = 0f, isUpright = true)

    @Test fun `look room target remains authorized only by matching fresh face and head orientation`() {
        val valid = observation(lookRoomOutput())
        assertEquals("rule:face:look-room", valid.targetId)
        assertEquals(.4f, valid.targetCx!!, 0f)
        for (face in listOf(null, lookingFace.copy(facingDirectionX = null),
            lookingFace.copy(facingDirectionX = -1f), lookingFace.copy(sampledAtMs = -751),
            lookingFace.copy(sampledAtMs = 1), lookingFace.copy(trackingId = 10),
            lookingFace.copy(viewportVersion = 8), lookingFace.copy(faceCount = 2))) {
            val invalid = observation(lookRoomOutput(face))
            assertNull("old cached recommendation must lose target authority: $face", invalid.targetId)
            assertNull(invalid.targetCx)
            assertNull(invalid.targetCy)
            assertEquals(-2L, invalid.targetViewportVersion)
        }
    }

    @Test fun `new head direction loss cancels locked move while recommendation stays cached`() {
        val output = lookRoomOutput()
        val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        val valid = observation(output)
        for (id in 1L..8L) controller.observe(timed(valid, id))
        assertEquals(CompositionSessionController.GuidanceAction.MoveRight, controller.action.value)
        // Same target/model output, only the independently sampled structure changes.
        val lost = observation(output.copy(structureObservation = lookingFace.copy(observationId = 9,
            facingDirectionX = null)))
        controller.observe(timed(lost, 9))
        assertEquals(CompositionSessionController.GuidanceAction.HoldStill, controller.action.value)
        assertNull(controller.decision.value.targetId)
        assertNull(controller.decision.value.displayEvidence)
    }

    @Test fun `reversed head orientation immediately revokes ready and capture authorization`() {
        val output = lookRoomOutput(targetX = lookingFace.face!!.centerX)
        val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
        val valid = observation(output)
        for (id in 1L..16L) controller.observe(timed(valid, id))
        assertEquals(CompositionSessionController.State.Ready, controller.state.value)
        assertTrue(controller.confirmReadyForCapture(4000))
        val reversed = observation(output.copy(structureObservation = lookingFace.copy(observationId = 17,
            facingDirectionX = -1f)))
        controller.observe(timed(reversed, 17))
        assertFalse(controller.state.value is CompositionSessionController.State.Ready)
        assertFalse(controller.confirmReadyForCapture(4250))
        assertEquals(GuidanceFeedback.INSUFFICIENT_EVIDENCE, controller.decision.value.feedback)
        assertNull(controller.decision.value.displayEvidence)
    }

    @Test fun `head independent framing is not rejected for missing head direction`() {
        val original = lookRoomOutput(lookingFace.copy(facingDirectionX = null))
        val actual = observation(original.copy(compositionTarget = original.compositionTarget!!.copy(
            source = "photography_rule", requiredFacingDirectionX = null)))
        assertEquals("rule:face:look-room", actual.targetId)
        assertEquals(.4f, actual.targetCx!!, 0f)
    }

    @Test fun `validated composition target replaces stale decorative target coordinates`() {
        val output = lookRoomOutput().copy(guideLines = listOf(target("face")))
        val actual = observation(output)
        assertEquals(output.compositionTarget!!.center.y, actual.targetCy!!, 0f)
        assertEquals(output.compositionTarget!!.tolerance, actual.targetTolerance, 0f)
    }

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
