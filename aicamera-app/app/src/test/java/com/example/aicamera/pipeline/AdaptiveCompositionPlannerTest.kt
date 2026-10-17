package com.example.aicamera.pipeline

import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.CompositionSessionController.GuidanceAction
import com.example.aicamera.guidance.CompositionSessionController.State
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class AdaptiveCompositionPlannerTest {
    private val layout = SceneLayout(List(9) { .5f }, List(9) { .02f }, .5f, 0f, 0f)
    private fun face(box: NormalizedBox = NormalizedBox(.22f, .18f, .38f, .42f),
        id: Long = 7, time: Long = 1000, viewport: Long = 2, tracking: Int? = 4) =
        StructureObservation(id, time, viewport, 1, box, trackingId = tracking, aspectRatio = .75f)
    private fun obj(box: NormalizedBox = NormalizedBox(.21f, .3f, .42f, .65f),
        id: Long = 7, time: Long = 1000, viewport: Long = 2, tracking: Int = 4) =
        ObjectObservation(id, time, viewport, tracking, 41, box, .9f, 3, .75f)
    private fun scene(family: SceneFamily = SceneFamily.PORTRAIT, time: Long = 1000,
        id: Long = 7, viewport: Long = 2, objectObservation: ObjectObservation? = null) =
        SceneAnalysisObservation(id, time, viewport, family, layout, objectObservation, true)

    @Test fun `missing references still permit healthy face and complete object rule targets`() {
        val planner = AdaptiveCompositionPlanner()
        val portrait = planner.select(scene(), face(), null, null, 1200)
        assertEquals(RecommendationRoute.PHOTOGRAPHY_RULE, portrait.route)
        assertNotNull(portrait.portrait)
        assertNull(portrait.objectTarget)
        val item = planner.select(scene(SceneFamily.STILL_LIFE, objectObservation = obj()), null, null, null, 1200)
        assertEquals(RecommendationRoute.PHOTOGRAPHY_RULE, item.route)
        assertNull(item.portrait)
        assertNotNull(item.objectTarget)
    }

    @Test fun `keep target stays at its initial coordinate across fresh movement`() {
        val planner = AdaptiveCompositionPlanner()
        val first = planner.select(scene(), face(), null, null, 1200)
        val movedFace = face(NormalizedBox(.30f, .18f, .46f, .42f), id = 8, time = 1300)
        val after = planner.select(scene(time = 1300, id = 8), movedFace, null, null, 1400)
        assertEquals(first.portrait!!.id, after.portrait!!.id)
        assertEquals(first.portrait!!.center, after.portrait!!.center)
        assertTrue(kotlin.math.abs(after.portrait!!.center.x - movedFace.face!!.centerX) > .06f)
        assertEquals("photography_rule_keep", after.portrait!!.source)
    }

    @Test fun `margin target stays locked when movement relieves triggering defect`() {
        val planner = AdaptiveCompositionPlanner()
        val first = planner.select(scene(), face(NormalizedBox(.025f, .3f, .225f, .6f)), null, null, 1200)
        val corrected = face(NormalizedBox(.085f, .3f, .285f, .6f), 8, 1300)
        val after = planner.select(scene(time = 1300, id = 8), corrected, null, null, 1400)
        assertEquals(first.portrait!!.id, after.portrait!!.id)
        assertEquals(first.portrait!!.center, after.portrait!!.center)
        assertEquals("photography_rule_subject_margin", after.portrait!!.source)
    }

    @Test fun `new edge defect invalidates keep even inside the old ready circle`() {
        val planner = AdaptiveCompositionPlanner()
        val first = planner.select(scene(), face(NormalizedBox(.06f, .3f, .26f, .6f)), null, null, 1200)
        val pressured = face(NormalizedBox(.035f, .3f, .235f, .6f), 8, 1300)
        assertTrue(kotlin.math.abs(first.portrait!!.center.x - pressured.face!!.centerX) < .06f)
        val after = planner.select(scene(time = 1300, id = 8), pressured, null, null, 1400)
        assertNotEquals(first.portrait!!.id, after.portrait!!.id)
        assertEquals("photography_rule_subject_margin", after.portrait!!.source)
        assertTrue(after.portrait!!.center.x > pressured.face!!.centerX)
    }

    @Test fun `unresolvable margin defect cannot be bypassed by a compatible reference`() {
        val planner = AdaptiveCompositionPlanner()
        val box = NormalizedBox(.025f, .3f, .875f, .7f)
        val observed = face(box)
        val reference = CompositionTarget("ref", NormalizedPoint(.46f, .5f), viewportVersion = 2,
            source = "reference", subjectTrackingId = 4)
        assertTrue(reference.accepts(box))
        val result = planner.select(scene(), observed, reference, null, 1200)
        assertEquals(RecommendationRoute.OBSERVATION_ONLY, result.route)
        assertNull(result.portrait)
        assertTrue("subject_margin_requires_unverified_scale_change" in result.reasons)
        val item = obj(box)
        val objectReference = ObjectCompositionTarget("obj-ref", NormalizedPoint(.46f, .5f), 2, 4, 41, box)
        assertTrue(objectReference.accepts(item))
        val itemResult = planner.select(scene(SceneFamily.FOOD, objectObservation = item), null, null, objectReference, 1200)
        assertNull(itemResult.objectTarget)
        assertEquals(RecommendationRoute.OBSERVATION_ONLY, itemResult.route)
    }

    @Test fun `repeated replanning cannot regenerate an endless stream of equivalent targets`() {
        val planner = AdaptiveCompositionPlanner()
        assertNotNull(planner.select(scene(), face(), null, null, 1200).portrait)
        for (n in 1L..4L) {
            planner.replan()
            val result = planner.select(scene(time = 1000 + n * 300, id = 7 + n),
                face(id = 7 + n, time = 1000 + n * 300), null, null, 1100 + n * 300)
            assertEquals(RecommendationRoute.OBSERVATION_ONLY, result.route)
            assertEquals(listOf("rule_execution_paused"), result.reasons)
            assertNull(result.portrait)
        }
        planner.reset()
        assertNotNull(planner.select(scene(), face(), null, null, 1200).portrait)
    }

    @Test fun `object identity face mode and viewport changes invalidate previous targets and pause`() {
        val planner = AdaptiveCompositionPlanner()
        val first = planner.select(scene(SceneFamily.FOOD, objectObservation = obj()), null, null, null, 1200)
        planner.replan()
        val replacement = obj(id = 8, time = 1300, tracking = 9)
        val changed = planner.select(scene(SceneFamily.FOOD, time = 1300, id = 8, objectObservation = replacement),
            null, null, null, 1400)
        assertNotEquals(first.objectTarget!!.id, changed.objectTarget!!.id)
        assertEquals(9, changed.objectTarget!!.trackingId)
        planner.replan()
        val portrait = planner.select(scene(time = 1600, id = 9), face(id = 9, time = 1600), null, null, 1700)
        assertNotNull(portrait.portrait)
        assertNull(portrait.objectTarget)
        planner.replan()
        val rotated = planner.select(scene(time = 1900, id = 10, viewport = 3), face(id = 10, time = 1900, viewport = 3),
            null, null, 2000)
        assertNotNull(rotated.portrait)
        assertEquals(3L, rotated.portrait!!.viewportVersion)
        assertNotEquals(portrait.portrait!!.id, rotated.portrait!!.id)
    }

    @Test fun `stale scene or missing subject removes locked target`() {
        val planner = AdaptiveCompositionPlanner()
        val first = planner.select(scene(), face(), null, null, 1200)
        val stale = planner.select(scene(), face(), null, null, 3000)
        assertNull(stale.portrait)
        assertEquals(listOf("stale_scene_evidence"), stale.reasons)
        val fresh = planner.select(scene(time = 3100, id = 8), face(id = 8, time = 3100), null, null, 3200)
        assertNotEquals(first.portrait!!.id, fresh.portrait!!.id)
        val lost = planner.select(scene(time = 3400, id = 9), null, null, null, 3500)
        assertNull(lost.portrait)
    }

    @Test fun `reference cannot force a healthy composition to a different placement`() {
        val planner = AdaptiveCompositionPlanner()
        val reference = CompositionTarget("ref", NormalizedPoint(.5f, .4f), viewportVersion = 2,
            source = "reference", subjectTrackingId = 4)
        val observed = face()
        assertTrue(reference.accepts(observed.face!!))
        val result = planner.select(scene(), observed, reference, null, 1200)
        assertEquals(RecommendationRoute.PHOTOGRAPHY_RULE, result.route)
        assertEquals(observed.face!!.centerX, result.portrait!!.center.x, 0f)
    }

    @Test fun `matching reference can endorse current framing without moving it`() {
        val planner = AdaptiveCompositionPlanner()
        val reference = CompositionTarget("ref", NormalizedPoint(.32f, .31f), viewportVersion = 2,
            source = "reference", subjectTrackingId = 4)
        val observed = face()
        val result = planner.select(scene(), observed, reference, null, 1200)
        assertEquals(RecommendationRoute.REFERENCE_STRUCTURE, result.route)
        assertEquals("current_compatible", result.portrait!!.source)
        assertEquals(observed.face!!.centerX, result.portrait!!.center.x, 0f)
        assertEquals(observed.face!!.centerY, result.portrait!!.center.y, 0f)
    }

    @Test fun `locked target cannot bypass stricter rule input validation`() {
        val portraitPlanner = AdaptiveCompositionPlanner()
        assertNotNull(portraitPlanner.select(scene(), face(), null, null, 1200).portrait)
        val invalidFace = face(id = 8, time = 1300).copy(aspectRatio = Float.NaN)
        val portrait = portraitPlanner.select(scene(id = 8, time = 1300), invalidFace, null, null, 1400)
        assertNull(portrait.portrait)
        val objectPlanner = AdaptiveCompositionPlanner()
        assertNotNull(objectPlanner.select(scene(SceneFamily.STILL_LIFE, objectObservation = obj()), null, null, null, 1200).objectTarget)
        val invalidObject = obj(id = 8, time = 1300).copy(confidence = 1.1f)
        val item = objectPlanner.select(scene(SceneFamily.STILL_LIFE, id = 8, time = 1300, objectObservation = invalidObject),
            null, null, null, 1400)
        assertNull(item.objectTarget)
    }

    @Test fun `reference from a different tracked face cannot endorse the active person`() {
        val planner = AdaptiveCompositionPlanner()
        val wrongIdentity = CompositionTarget("ref", NormalizedPoint(.32f, .31f), viewportVersion = 2,
            source = "reference", subjectTrackingId = 999)
        val result = planner.select(scene(), face(), wrongIdentity, null, 1200)
        assertEquals(RecommendationRoute.PHOTOGRAPHY_RULE, result.route)
        assertEquals(4, result.portrait!!.subjectTrackingId)
    }

    @Test fun `real direct rule targets produce inward actions and verified ready after execution`() {
        for (family in listOf(SceneFamily.PORTRAIT, SceneFamily.FOOD, SceneFamily.STILL_LIFE)) {
            val planner = AdaptiveCompositionPlanner()
            val controller = CompositionSessionController().apply { startAnalysis(); onAnalysisComplete() }
            var left = .025f
            var previousLeft = left
            var firstTargetId: String? = null
            var sawMove = false
            for (i in 1L..18L) {
                val lastAction = controller.action.value
                if (lastAction == GuidanceAction.MoveLeft) left += .035f
                if (lastAction == GuidanceAction.MoveRight) fail("$family gave outward instruction")
                val time = i * 1000
                val box = NormalizedBox(left, .3f, left + .2f, .6f)
                val structure = if (family == SceneFamily.PORTRAIT) face(box, i, time) else null
                val item = if (family == SceneFamily.PORTRAIT) null else obj(box, i, time)
                val selection = planner.select(scene(family, time, i, objectObservation = item), structure, null, null, time)
                val targetId = selection.portrait?.id ?: selection.objectTarget!!.id
                if (firstTargetId == null) firstTargetId = targetId
                assertEquals(firstTargetId, targetId)
                val center = selection.portrait?.center ?: selection.objectTarget!!.center
                val quality = ExecutionQualityEvidence(i, time, 2, family, 4, box, .5f, .1f, .2f, .01f, .04f,
                    backgroundDx = left - previousLeft, backgroundDy = 0f, comparedObservationId = i - 1)
                controller.observe(CompositionSessionController.FrameObservation(frameId = i, timestampMs = time,
                    rollDegrees = 0f, rawRollDegrees = 0f, isUpright = true, subjectCx = box.centerX, subjectCy = box.centerY,
                    targetCx = center.x, targetCy = center.y, targetTolerance = .06f, expectsSubject = true,
                    hasRealSubject = true, modelConfidence = .9f, subjectObservationId = i, subjectTrackingId = 4,
                    subjectSampledAtMs = time, imuObservationId = i, imuSampledAtMs = time, viewportVersion = 2,
                    targetViewportVersion = 2, targetId = targetId, subjectBox = box,
                    observedAnchorKind = if (family == SceneFamily.PORTRAIT) ObservedAnchorKind.FACE else ObservedAnchorKind.OBJECT,
                    executionQuality = quality, executionQualityRequired = true))
                sawMove = sawMove || controller.action.value == GuidanceAction.MoveLeft
                previousLeft = left
            }
            assertTrue("$family did not issue an inward move", sawMove)
            assertEquals("$family failed verified execution", State.Ready, controller.state.value)
            assertTrue("$family did not improve edge margin", left > .07f)
        }
    }
}
