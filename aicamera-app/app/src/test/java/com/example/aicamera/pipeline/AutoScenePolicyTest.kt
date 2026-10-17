package com.example.aicamera.pipeline

import com.example.aicamera.guidance.ObjectObservation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.StructureObservation
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoScenePolicyTest {
    private val landscape = SceneContext("landscape", "natural_landscape", .9f, "rule_of_thirds", "mixed")
    private val food = SceneContext("still_life", "food_still_life", .9f)
    private val portrait = SceneContext("portrait", "closeup_portrait", .9f)
    private val box = NormalizedBox(.25f, .2f, .65f, .7f)

    @Test fun `visible face overrules conflicting retrieval and object after independent confirmation`() {
        val policy = AutoScenePolicy()
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, face(1, 1000), obj(1, 1000), 1000, 1000, 1).family)
        val decision = policy.resolve(landscape, face(2, 1400), obj(2, 1400), 1400, 1400, 1)
        assertEquals(SceneFamily.PORTRAIT, decision.family)
        assertEquals(AutoPhotoMode.PORTRAIT, decision.mode)
        assertEquals(AutoSceneEvidence.FACE, decision.evidenceSource)
        assertEquals("portrait", decision.context.photoType)
        assertNull(decision.context.sceneCategory)
        assertNull(decision.context.preferredComposition)
    }

    @Test fun `stable object identifies food without a gallery candidate`() {
        val policy = AutoScenePolicy()
        policy.resolve(SceneContext(), null, obj(1, 1000), 1000, 1000, 1)
        val decision = policy.resolve(SceneContext(), null, obj(2, 2000), 2000, 2000, 1)
        assertEquals(SceneFamily.FOOD, decision.family)
        assertEquals(AutoPhotoMode.STILL_LIFE, decision.mode)
        assertEquals(AutoSceneEvidence.OBJECT, decision.evidenceSource)
        assertEquals("food_still_life", decision.context.sceneCategory)
    }

    @Test fun `supported non-food object selects still life without forcing food semantics`() {
        val policy = AutoScenePolicy()
        policy.resolve(food, null, obj(1, 1000, 39), 1000, 1000, 1)
        val decision = policy.resolve(food, null, obj(2, 2000, 39), 2000, 2000, 1)
        assertEquals(SceneFamily.STILL_LIFE, decision.family)
        assertEquals("still_life", decision.context.photoType)
        assertNull(decision.context.sceneCategory)
    }

    @Test fun `landscape is automatic after confident semantic consensus stays stable`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, face(1, 1000, 0), null, 1000, 1000, 1)
        val result = policy.resolve(landscape, face(2, 1400, 0), null, 1400, 1400, 1)
        assertEquals(SceneFamily.NATURAL_LANDSCAPE, result.family)
        assertEquals(AutoPhotoMode.LANDSCAPE, result.mode)
        assertEquals(AutoSceneEvidence.SEMANTIC_CONSENSUS, result.evidenceSource)
    }

    @Test fun `repeated face and repeated semantic frames cannot confirm a switch`() {
        val policy = AutoScenePolicy()
        for (now in listOf(1000L, 1300L, 1600L)) {
            assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), face(1, 1000), null, now, now, 1).family)
        }
        policy.reset()
        for (now in listOf(1000L, 1300L, 1600L)) {
            assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 1000, now, 1).family)
        }
    }

    @Test fun `rapid new samples must span a real confirmation interval`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, null, null, 1000, 1000, 1)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 1100, 1100, 1).family)
        assertEquals(SceneFamily.NATURAL_LANDSCAPE, policy.resolve(landscape, null, null, 1300, 1300, 1).family)
    }

    @Test fun `a conflicting scene immediately invalidates the old mode and requires new confirmation`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, null, null, 1000, 1000, 1)
        policy.resolve(landscape, null, null, 1400, 1400, 1)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(food, null, null, 1800, 1800, 1).family)
        assertEquals(SceneFamily.FOOD, policy.resolve(food, null, null, 2200, 2200, 1).family)
    }

    @Test fun `weak or nonfinite semantics never preserve previous confident classification`() {
        for (confidence in listOf(.69f, Float.NaN, Float.POSITIVE_INFINITY, 1.01f)) {
            val policy = AutoScenePolicy()
            policy.resolve(landscape, null, null, 1000, 1000, 1)
            policy.resolve(landscape, null, null, 1400, 1400, 1)
            assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape.copy(sceneConfidence = confidence), null, null, 1800, 1800, 1).family)
        }
    }

    @Test fun `fresh absent face prevents portrait gallery rows from becoming the subject`() {
        val policy = AutoScenePolicy()
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(portrait, face(1, 1000, 0), null, 1000, 1000, 1).family)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(portrait, face(2, 1400, 0), null, 1400, 1400, 1).family)
    }

    @Test fun `multiple faces identify portrait but do not create a single face anchor`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, face(1, 1000, 2), null, 1000, 1000, 1)
        val many = face(2, 1400, 2)
        assertEquals(SceneFamily.PORTRAIT, policy.resolve(landscape, many, null, 1400, 1400, 1).family)
        assertNull(many.singleFace(1400))
    }

    @Test fun `stale future malformed and wrong-viewport subjects are not classification evidence`() {
        val invalidFaces = listOf(face(1, 1000), face(1, 2100), face(1, 2000).copy(viewportVersion = 2),
            face(1, 2000).copy(valid = false), face(1, 2000).copy(face = null),
            face(1, 2000).copy(face = box.copy(left = Float.NaN)))
        for (invalid in invalidFaces) {
            val policy = AutoScenePolicy()
            assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), invalid, null, 2000, 2000, 1).family)
        }
        val invalidObjects = listOf(obj(1, 2000, 58), obj(1, 2000).copy(stableSamples = 1),
            obj(1, 2000).copy(viewportVersion = 2), obj(1, 2000).copy(confidence = 1.2f))
        for (invalid in invalidObjects) {
            val policy = AutoScenePolicy()
            assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), null, invalid, 2000, 2000, 1).family)
        }
    }

    @Test fun `viewport changes and stale frame withdraw old modes`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, null, null, 1000, 1000, 1)
        policy.resolve(landscape, null, null, 1400, 1400, 1)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 1800, 1800, 2).family)
        assertEquals(SceneFamily.NATURAL_LANDSCAPE, policy.resolve(landscape, null, null, 2200, 2200, 2).family)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 2200, 4000, 2).family)
    }

    @Test fun `out of order detector and image samples cannot keep a valid mode`() {
        val policy = AutoScenePolicy()
        policy.resolve(SceneContext(), face(1, 1000), null, 1000, 1000, 1)
        policy.resolve(SceneContext(), face(2, 1400), null, 1400, 1400, 1)
        assertEquals("scene_subject_out_of_order", policy.resolve(SceneContext(), face(1, 1200), null, 1600, 1600, 1).reason)
        assertEquals("scene_observation_out_of_order", policy.resolve(landscape, null, null, 1500, 1700, 1).reason)
    }

    @Test fun `long gap requires fresh confirmation instead of pairing distant frames`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, null, null, 1000, 1000, 1)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 4000, 4000, 1).family)
        assertEquals(SceneFamily.NATURAL_LANDSCAPE, policy.resolve(landscape, null, null, 4400, 4400, 1).family)
    }

    @Test fun `unknown scene and explicit reset clear prior confirmations`() {
        val policy = AutoScenePolicy()
        policy.resolve(landscape, null, null, 1000, 1000, 1)
        policy.resolve(landscape, null, null, 1400, 1400, 1)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(lightingQuality = "soft"), null, null, 1800, 1800, 1).family)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 2200, 2200, 1).family)
        policy.reset()
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(landscape, null, null, 2600, 2600, 1).family)
    }

    @Test fun `one second object cadence stays stable across cached half-second frames then expires`() {
        val policy = AutoScenePolicy()
        // The first detection does not yet establish a stable object track.
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), null,
            obj(1, 1000).copy(stableSamples = 1), 1000, 1000, 1).family)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), null, obj(2, 2000), 2000, 2000, 1).family)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), null, obj(2, 2000), 2500, 2500, 1).family)
        assertEquals(SceneFamily.FOOD, policy.resolve(SceneContext(), null, obj(3, 3000), 3000, 3000, 1).family)
        assertEquals(SceneFamily.FOOD, policy.resolve(SceneContext(), null, obj(3, 3000), 3500, 3500, 1).family)
        assertEquals(SceneFamily.FOOD, policy.resolve(SceneContext(), null, obj(4, 4000), 4000, 4000, 1).family)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(SceneContext(), null, obj(4, 4000), 5501, 5501, 1).family)
    }

    @Test fun `detected back-view person selects portrait without inventing a face target`() {
        val policy = AutoScenePolicy()
        policy.resolve(food, face(1, 1000, 0), obj(1, 1000), 1000, 1000, 1,
            PersonSceneObservation(1, 1000, 1, box, .8f))
        val noFace = face(2, 2000, 0)
        val result = policy.resolve(food, noFace, obj(2, 2000), 2000, 2000, 1,
            PersonSceneObservation(2, 2000, 1, box, .8f))
        assertEquals(SceneFamily.PORTRAIT, result.family)
        assertEquals(AutoSceneEvidence.PERSON, result.evidenceSource)
        assertNull(noFace.singleFace(2000))
        assertNull(result.context.sceneCategory)
    }

    @Test fun `face takes precedence over person and cached person cannot confirm a mode`() {
        val policy = AutoScenePolicy()
        val person = PersonSceneObservation(1, 1000, 1, box, .8f)
        policy.resolve(food, null, null, 1000, 1000, 1, person)
        assertEquals(SceneFamily.UNKNOWN, policy.resolve(food, null, null, 1500, 1500, 1, person).family)
        policy.resolve(food, face(1, 2000), null, 2000, 2000, 1, person.copy(observationId = 2, sampledAtMs = 2000))
        val result = policy.resolve(food, face(2, 2400), null, 2400, 2400, 1, person.copy(observationId = 2, sampledAtMs = 2000))
        assertEquals(AutoSceneEvidence.FACE, result.evidenceSource)
        assertEquals(SceneFamily.PORTRAIT, result.family)
    }

    @Test fun `unreliable stale and different-viewport person observations cannot establish portrait`() {
        val person = PersonSceneObservation(1, 2000, 1, box, .8f)
        val invalid = listOf(person.copy(confidence = .64f), person.copy(confidence = Float.NaN),
            person.copy(sampledAtMs = 400), person.copy(sampledAtMs = 2100), person.copy(viewportVersion = 2),
            person.copy(observationId = 0), person.copy(box = NormalizedBox(.5f, .5f, .6f, .6f)),
            person.copy(box = box.copy(left = Float.NaN)))
        for (bad in invalid) {
            assertEquals(SceneFamily.UNKNOWN,
                AutoScenePolicy().resolve(SceneContext(), null, null, 2000, 2000, 1, bad).family)
        }
    }

    private fun face(id: Long, time: Long, count: Int = 1) = StructureObservation(id, time, 1, count,
        box.takeIf { count == 1 }, trackingId = 1)
    private fun obj(id: Long, time: Long, classId: Int = 47) = ObjectObservation(id, time, 1, 1,
        classId, box, .9f, 2, 1f)
}
