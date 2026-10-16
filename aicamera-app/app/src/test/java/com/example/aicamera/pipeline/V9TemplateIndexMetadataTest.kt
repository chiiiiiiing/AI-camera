package com.example.aicamera.pipeline

import com.example.aicamera.filter.FilterIds
import com.example.aicamera.filter.FilterReason
import com.example.aicamera.filter.FilterRecommendationEngine
import com.example.aicamera.filter.FrameColorStats
import com.example.aicamera.zoom.AutoZoomReason
import com.example.aicamera.zoom.AutoZoomRecommendationEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class V9TemplateIndexMetadataTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val stats = FrameColorStats(0.55f, 0.12f, 0.4f, 0f, 0f, 0f)
    private val draftMethods = listOf("legacy_scene_rule_draft", "scene_rule_draft", "not_annotated_after_recovery")

    private fun load(method: String?, photoType: String = "landscape", scene: String = "natural_landscape",
                     includeAttributes: Boolean = true): Pair<V9TemplateIndex, TemplateMeta> {
        val directory = temporaryFolder.newFolder()
        val annotation = JSONObject()
        if (includeAttributes) {
            annotation.put("composition", JSONObject("""{"type":"rule_of_thirds","subject_position":"center","horizon_position":"upper_third","leading_lines":true,"negative_space":"high"}"""))
            annotation.put("lighting", JSONObject("""{"quality":"soft","direction":"front","contrast":"high","time_of_day":"golden_hour"}"""))
            annotation.put("camera", JSONObject("""{"angle":"eye_level","distance":"close","orientation":"portrait"}"""))
            annotation.put("visual_summary", "Generated scene description")
        }
        if (method != null) annotation.put("provenance", JSONObject().put("method", method))
        val sourceHash = "a".repeat(64)
        val row = JSONObject().put("template_id", "reference").put("photo_type", photoType)
            .put("scene_category", scene).put("source_sha256", sourceHash).put("annotation", annotation)
            .put("reference_review", JSONObject().put("status", "candidate_clean").put("source_sha256", sourceHash)
                .put("provenance", JSONObject().put("method", "ai_visual_review")))
        File(directory, "templates.metadata.jsonl").writeText(row.toString())
        File(directory, "templates.keys.json").writeText("""{"dimension":512,"count":1,"items":[{"template_id":"reference"}]}""")
        val bytes = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(512) { bytes.putFloat(if (it == 0) 1f else 0f) }
        File(directory, "templates.embeddings.f32").writeBytes(bytes.array())
        val index = V9TemplateIndex(directory)
        assertTrue(index.load())
        return index to requireNotNull(index.getMetadata("reference"))
    }

    @Test
    fun `explicit drafts load unknown attributes without inventing defaults`() {
        for (method in draftMethods) for (includeAttributes in listOf(false, true)) {
            val (_, meta) = load(method, includeAttributes = includeAttributes)
            assertTrue(meta.referenceEligible)
            assertEquals("natural_landscape", meta.sceneCategory)
            listOf(meta.compositionType, meta.subjectPosition, meta.horizonPosition, meta.negativeSpace,
                meta.lightingQuality, meta.lightingDirection, meta.lightingContrast, meta.timeOfDay,
                meta.cameraAngle, meta.cameraDistance, meta.cameraOrientation).forEach {
                assertEquals("unknown", it)
            }
            assertFalse(meta.leadingLines)
            assertEquals("", meta.visualSummary)
        }
    }

    @Test
    fun `draft composition and lighting cannot add reranking bonuses`() {
        for (method in draftMethods) {
            val (index, meta) = load(method)
            val hit = SearchHit("reference", .8f, .8f, meta)
            val context = V9SceneContextEstimator().estimate(listOf(hit), stats)
            assertNull(context.preferredComposition)
            val ranked = index.rerank(listOf(hit), SceneContext(
                preferredComposition = "rule_of_thirds", lightingQuality = "soft"))
            assertEquals(.8f, ranked.single().rerankScore, 0f)
        }
    }

    @Test
    fun `draft golden hour label cannot recommend warm filter evidence`() {
        for (method in draftMethods) {
            val (_, meta) = load(method)
            val result = FilterRecommendationEngine().recommend(listOf(SearchHit("reference", .9f, .9f, meta)), stats)
            assertFalse(result.candidates.any { it.filterId == FilterIds.GOLDEN_WARM })
            assertFalse(result.candidates.any { FilterReason.GOLDEN_HOUR in it.reasons })
        }
    }

    @Test
    fun `draft camera distance and daylight cannot add zoom evidence`() {
        for (method in draftMethods) {
            val (_, meta) = load(method, photoType = "portrait", scene = "closeup_portrait")
            val result = AutoZoomRecommendationEngine().recommend(listOf(SearchHit("reference", .9f, .9f, meta)), stats)
            // Semantic scene advice remains available, without invented capture settings.
            assertTrue(AutoZoomReason.PORTRAIT in result.reasons)
            assertFalse(AutoZoomReason.CLOSE_DISTANCE in result.reasons)
            assertFalse(AutoZoomReason.DAYLIGHT_METADATA in result.reasons)
        }
    }

    @Test
    fun `legacy metadata without provenance keeps its existing attributes and defaults`() {
        val (_, populated) = load(null)
        assertEquals("rule_of_thirds", populated.compositionType)
        assertEquals("golden_hour", populated.timeOfDay)
        assertEquals("close", populated.cameraDistance)
        assertTrue(populated.leadingLines)
        val (_, missing) = load(null, includeAttributes = false)
        assertEquals("rule_of_thirds", missing.compositionType)
        assertEquals("soft", missing.lightingQuality)
        assertEquals("medium", missing.cameraDistance)
    }
}
