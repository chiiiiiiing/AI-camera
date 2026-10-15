package com.example.aicamera.pipeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V9ReferencePolicyTest {
    private val hash = "a".repeat(64)
    private fun reviewed(): JSONObject = JSONObject("""{
      "source_sha256":"$hash",
      "reference_review":{"status":"candidate_clean","source_sha256":"$hash",
        "provenance":{"method":"ai_visual_review","human_reviewed":false}},
      "reference_geometry":{"anchor_kind":"face","coordinate_space":"exif_upright_full_frame",
        "source_sha256":"$hash","method":"ai_visual_review","center_x":0.72,"center_y":0.55,
        "aspect_ratio":0.75}
    }""")

    @Test fun `unreviewed legacy rules never authorize a reference`() {
        assertFalse(V9ReferencePolicy.isEligible(JSONObject("""{"annotation":{"aesthetic_score":4.0}}""")))
        assertNull(V9ReferencePolicy.parseFaceAnchor(JSONObject()))
    }

    @Test fun `rejected screenshots and mismatched source hashes are excluded`() {
        val record = reviewed()
        assertTrue(V9ReferencePolicy.isEligible(record))
        record.getJSONObject("reference_review").put("status", "rejected")
        assertFalse(V9ReferencePolicy.isEligible(record))
        record.getJSONObject("reference_review").put("status", "candidate_clean").put("source_sha256", "b".repeat(64))
        assertFalse(V9ReferencePolicy.isEligible(record))
    }

    @Test fun `only face geometry from the exact reviewed image is transferred`() {
        val record = reviewed()
        assertEquals(V9FaceAnchor(.72f, .55f, .75f), V9ReferencePolicy.parseFaceAnchor(record))
        record.getJSONObject("reference_geometry").put("anchor_kind", "whole_body")
        assertNull(V9ReferencePolicy.parseFaceAnchor(record))
        record.getJSONObject("reference_geometry").put("anchor_kind", "face").put("center_x", 5)
        assertNull(V9ReferencePolicy.parseFaceAnchor(record))
    }

    private fun meta() = TemplateMeta("reference", "portrait", "closeup_portrait", "centered_subject",
        "center", "not_visible", false, "low", "soft", "front", "eye_level", "close", "portrait", "",
        referenceEligible = true, faceAnchor = V9FaceAnchor(.72f, .55f, .75f))

    @Test fun `horizontal references cannot dictate a vertical face target`() {
        assertNotNull(V9ReferencePolicy.faceTarget(meta(), .9f, .75f))
        assertNull(V9ReferencePolicy.faceTarget(meta(), .9f, 1.333f))
        assertNull(V9ReferencePolicy.faceTarget(meta(), .9f, null))
        assertNull(V9ReferencePolicy.faceTarget(meta(), .5f, .75f))
        assertNull(V9ReferencePolicy.faceTarget(meta(), Float.NaN, .75f))
        assertNull(V9ReferencePolicy.faceTarget(meta().copy(referenceEligible = false), .9f, .75f))
    }

    @Test fun `portrait anchor follows centered viewport crop without clipping the face`() {
        val target = V9ReferencePolicy.faceTarget(meta(), .9f, 9f / 16f)!!
        assertEquals((.72f - .125f) / .75f, target.x, .00001f)
        assertEquals(.55f, target.y, .00001f)
        assertNull(V9ReferencePolicy.faceTarget(meta().copy(faceAnchor = V9FaceAnchor(.9f, .55f, .75f)), .9f, 9f / 16f))
    }
}
