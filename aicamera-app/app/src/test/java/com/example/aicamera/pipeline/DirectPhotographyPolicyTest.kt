package com.example.aicamera.pipeline

import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.guidance.ObjectObservation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.StructureObservation
import com.example.aicamera.guidance.geometry.GuidanceGeometry
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class DirectPhotographyPolicyTest {
    private fun face(box: NormalizedBox = NormalizedBox(.22f, .18f, .38f, .42f)) =
        StructureObservation(7, 1000, 2, 1, box, trackingId = 4, aspectRatio = .75f)
    private fun obj(box: NormalizedBox = NormalizedBox(.21f, .3f, .42f, .65f)) =
        ObjectObservation(7, 1000, 2, 4, 41, box, .9f, 3, .75f)

    @Test fun `healthy off center face keeps its composition without a reference`() {
        val observed = face()
        val plan = DirectPhotographyPolicy.portrait(observed, 1200, 2)!!
        assertEquals(1, plan.candidates.size)
        assertEquals(DirectFramingIntent.KEEP, plan.selected!!.intent)
        assertEquals(observed.face!!.centerX, plan.portraitTarget!!.center.x, 0f)
        assertEquals(observed.face!!.centerY, plan.portraitTarget!!.center.y, 0f)
        assertEquals("photography_rule_keep", plan.portraitTarget!!.source)
        assertTrue("face_only_protection" in plan.reasons)
        assertTrue("aesthetic_preference_unverified" in plan.reasons)
    }

    @Test fun `all observed edge defects get inward rather than outward directions`() {
        val cases = listOf(
            NormalizedBox(.025f, .30f, .225f, .60f) to GuidanceCue.MOVE_LEFT,
            NormalizedBox(.775f, .30f, .975f, .60f) to GuidanceCue.MOVE_RIGHT,
            NormalizedBox(.30f, .025f, .60f, .225f) to GuidanceCue.MOVE_UP,
            NormalizedBox(.30f, .775f, .60f, .975f) to GuidanceCue.MOVE_DOWN
        )
        for ((box, direction) in cases) {
            val plan = DirectPhotographyPolicy.portrait(face(box), 1200, 2)!!
            val target = plan.portraitTarget!!
            assertEquals(DirectFramingIntent.KEEP, plan.candidates.first().intent)
            assertEquals(DirectFramingIntent.IMPROVE_OBSERVED_MARGIN, plan.selected!!.intent)
            assertTrue(plan.minimumMarginExpectedAfter!! > plan.minimumMarginBefore)
            assertEquals(direction, GuidanceGeometry.guidanceCue(box.centerX, box.centerY, true,
                target.center.x, target.center.y, target.tolerance, coordinateAspectRatio = .75f))
            assertTrue(target.accepts(box))
        }
    }

    @Test fun `ready circle still guarantees improved margin in every supported aspect`() {
        val box = NormalizedBox(.035f, .30f, .235f, .60f)
        for (aspect in listOf(.5625f, .75f, 1f, 1.333333f, 1.777778f)) {
            val plan = DirectPhotographyPolicy.portrait(face(box).copy(aspectRatio = aspect), 1200, 2)!!
            val target = plan.portraitTarget!!
            // Leftmost accepted subject centre is the worst accepted horizontal margin.
            val edgeX = target.center.x - target.tolerance / maxOf(1f, aspect)
            val worstAcceptedMargin = edgeX - box.width / 2
            assertTrue(worstAcceptedMargin >= .069f)
            assertTrue(worstAcceptedMargin > plan.minimumMarginBefore)
            assertTrue(GuidanceGeometry.isSubjectAligned(edgeX + .00001f, target.center.y,
                target.center.x, target.center.y, target.tolerance, aspect))
        }
    }

    @Test fun `corner correction preserves observed box size and does not change healthy axis`() {
        val oneEdge = NormalizedBox(.025f, .075f, .225f, .375f)
        val plan = DirectPhotographyPolicy.portrait(face(oneEdge), 1200, 2)!!
        val shifted = plan.selected!!.expectedSubjectBox
        assertEquals(oneEdge.top, shifted.top, 0f)
        assertEquals(oneEdge.width, shifted.width, .00001f)
        assertEquals(oneEdge.height, shifted.height, .00001f)
        val corner = DirectPhotographyPolicy.portrait(face(NormalizedBox(.025f, .025f, .225f, .325f)), 1200, 2)!!
        assertTrue(corner.selected!!.expectedSubjectBox.left >= .129f)
        assertTrue(corner.selected!!.expectedSubjectBox.top >= .129f)
    }

    @Test fun `fresh stable supported object can use rule without compatible reference`() {
        val observed = obj(NormalizedBox(.025f, .3f, .325f, .65f))
        for (family in listOf(SceneFamily.FOOD, SceneFamily.STILL_LIFE)) {
            val plan = DirectPhotographyPolicy.objectSubject(observed, family, 1200, 2)!!
            assertNotNull(plan.objectTarget)
            assertNull(plan.portraitTarget)
            assertEquals("photography_rule_subject_margin", plan.objectTarget!!.source)
            assertTrue(plan.objectTarget!!.accepts(observed))
            assertEquals(2, plan.candidates.size)
        }
    }

    @Test fun `wide subject with edge pressure abstains instead of inventing zoom or ready`() {
        val box = NormalizedBox(.025f, .3f, .875f, .7f)
        val portrait = DirectPhotographyPolicy.portrait(face(box), 1200, 2)!!
        val objectPlan = DirectPhotographyPolicy.objectSubject(obj(box), SceneFamily.STILL_LIFE, 1200, 2)!!
        for (plan in listOf(portrait, objectPlan)) {
            assertEquals(DirectFramingIntent.KEEP, plan.candidates.single().intent)
            assertNull(plan.selected)
            assertNull(plan.portraitTarget)
            assertNull(plan.objectTarget)
            assertTrue("subject_margin_requires_unverified_scale_change" in plan.reasons)
        }
    }

    @Test fun `missing stale multi face invalid and changed viewport evidence cannot guide`() {
        assertNull(DirectPhotographyPolicy.portrait(null, 1200, 2))
        for (observed in listOf(face().copy(faceCount = 2), face().copy(valid = false),
            face().copy(sampledAtMs = 449), face().copy(sampledAtMs = 1201), face().copy(observationId = 0),
            face().copy(viewportVersion = 3), face().copy(aspectRatio = Float.NaN),
            face(NormalizedBox(Float.NaN, .2f, .5f, .7f)), face(NormalizedBox(0f, .2f, .5f, .7f)))) {
            assertNull(DirectPhotographyPolicy.portrait(observed, 1200, 2))
        }
    }

    @Test fun `plant landscape ambiguous unsupported and unstable object evidence cannot guide`() {
        for (family in listOf(SceneFamily.PORTRAIT, SceneFamily.UNKNOWN, SceneFamily.PLANT,
            SceneFamily.NATURAL_LANDSCAPE, SceneFamily.URBAN_LANDSCAPE)) {
            assertNull(DirectPhotographyPolicy.objectSubject(obj(), family, 1200, 2))
        }
        for (observed in listOf(obj().copy(stableSamples = 1), obj().copy(classId = 0), obj().copy(classId = 58),
            obj().copy(sampledAtMs = 0), obj().copy(sampledAtMs = 1601), obj().copy(viewportVersion = 3),
            obj().copy(confidence = 1.1f), obj().copy(confidence = Float.NaN),
            obj(NormalizedBox(.01f, .2f, .4f, .7f)))) {
            assertNull(DirectPhotographyPolicy.objectSubject(observed, SceneFamily.STILL_LIFE, 1600, 2))
        }
    }

    @Test fun `target identities bind the initial sample so a caller cannot silently move the same target`() {
        val original = DirectPhotographyPolicy.portrait(face(), 1200, 2)!!.portraitTarget!!
        val later = DirectPhotographyPolicy.portrait(face().copy(observationId = 8, sampledAtMs = 1200), 1300, 2)!!.portraitTarget!!
        assertNotEquals(original.id, later.id)
        assertTrue(original.id.startsWith("rule:face:2:4:"))
    }
}
