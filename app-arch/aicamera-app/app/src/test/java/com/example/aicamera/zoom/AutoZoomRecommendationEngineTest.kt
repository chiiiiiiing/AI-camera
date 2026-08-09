package com.example.aicamera.zoom

import com.example.aicamera.filter.FrameColorStats
import com.example.aicamera.pipeline.SearchHit
import com.example.aicamera.pipeline.TemplateMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoZoomRecommendationEngineTest {
    private val engine = AutoZoomRecommendationEngine()
    private val normalStats = FrameColorStats(0.55f, 0.12f, 0.40f, 0f, 0f, 0f)

    @Test
    fun `known V9 categories produce the intended framing and ratio`() {
        assertTarget("portrait", "closeup_portrait", AutoZoomScene.PORTRAIT, ZoomFraming.CLOSE, 1.6f)
        assertTarget("still_life", "food_still_life", AutoZoomScene.FOOD, ZoomFraming.CLOSE, 1.4f)
        assertTarget("still_life", "lifestyle_still_life", AutoZoomScene.STILL_LIFE, ZoomFraming.MEDIUM, 1.2f)
        assertTarget("still_life", "plant_still_life", AutoZoomScene.STILL_LIFE, ZoomFraming.MEDIUM, 1.2f)
        assertTarget("landscape", "natural_landscape", AutoZoomScene.NATURAL_LANDSCAPE, ZoomFraming.WIDE, 1.0f)
        assertTarget("landscape", "urban_landscape", AutoZoomScene.URBAN_LANDSCAPE, ZoomFraming.WIDE, 1.0f)
    }

    @Test
    fun `majority top five evidence can defeat a different top one`() {
        val result = engine.recommend(
            listOf(
                hit("food", 0.90f, "still_life", "food_still_life"),
                hit("p1", 0.86f, "portrait", "closeup_portrait"),
                hit("p2", 0.83f, "portrait", "closeup_portrait"),
                hit("p3", 0.80f, "portrait", "closeup_portrait"),
                hit("p4", 0.77f, "portrait", "closeup_portrait")
            ),
            normalStats
        )
        assertEquals(AutoZoomScene.PORTRAIT, result.scene)
        assertEquals(1.6f, result.suggestedZoomRatio)
        assertTrue(result.shouldAutoZoom)
    }

    @Test
    fun `unsafe ambiguous and invalid input disables automatic zoom`() {
        val hits = consistentHits("portrait", "closeup_portrait")
        assertDisabled(engine.recommend(hits, normalStats.copy(meanLuminance = 0.05f)))
        assertDisabled(engine.recommend(hits, normalStats.copy(highlightClipRatio = 0.30f)))
        assertDisabled(engine.recommend(listOf(
            hit("a", 0.800f, "portrait", "closeup_portrait"),
            hit("b", 0.796f, "portrait", "closeup_portrait")
        ), normalStats))
        assertDisabled(engine.recommend(listOf(
            hit("p", 0.90f, "portrait", "closeup_portrait"),
            hit("l", 0.88f, "landscape", "natural_landscape"),
            hit("u", 0.86f, "landscape", "urban_landscape")
        ), normalStats))
        assertDisabled(engine.recommend(emptyList(), normalStats))
        assertDisabled(engine.recommend(listOf(
            SearchHit("bad", Float.NaN, Float.POSITIVE_INFINITY, meta("portrait", "closeup_portrait"))
        ), normalStats))
    }

    @Test
    fun `unknown metadata and non finite stats safely fall back`() {
        val unknown = listOf(
            SearchHit("unknown", 0.9f, 0.9f, meta("mystery", "mystery"))
        )
        assertDisabled(engine.recommend(unknown, normalStats))

        val result = engine.recommend(
            consistentHits("portrait", "closeup_portrait"),
            FrameColorStats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        )
        assertTrue(result.suggestedZoomRatio.isFinite())
        assertTrue(result.confidence.isFinite())
        assertTrue(result.suggestedZoomRatio in 1f..2.5f)
    }

    @Test
    fun `output contract is finite and explainable`() {
        val result = engine.recommend(consistentHits("portrait", "closeup_portrait", "golden_hour"), normalStats)
        assertEquals(AutoZoomContract.VERSION, result.contractVersion)
        assertTrue(result.confidence.isFinite() && result.confidence in 0f..1f)
        assertTrue(result.suggestedZoomRatio.isFinite() && result.suggestedZoomRatio in 1f..2.5f)
        assertTrue(result.reasons.isNotEmpty())
        assertEquals(result.reasons.distinct(), result.reasons)
    }

    private fun assertTarget(
        photoType: String,
        category: String,
        scene: AutoZoomScene,
        framing: ZoomFraming,
        ratio: Float
    ) {
        val result = engine.recommend(consistentHits(photoType, category), normalStats)
        assertEquals(scene, result.scene)
        assertEquals(framing, result.framing)
        assertEquals(ratio, result.suggestedZoomRatio)
        assertTrue(result.shouldAutoZoom)
    }

    private fun assertDisabled(result: AutoZoomRecommendation) {
        assertFalse(result.shouldAutoZoom)
        assertEquals(AutoZoomScene.UNKNOWN, result.scene)
        assertEquals(1f, result.suggestedZoomRatio)
        assertTrue(result.reasons.isNotEmpty())
    }

    private fun consistentHits(
        photoType: String,
        category: String,
        timeOfDay: String = "unknown"
    ): List<SearchHit> = listOf(0.91f, 0.88f, 0.84f, 0.80f, 0.76f).mapIndexed { index, score ->
        hit("t$index", score, photoType, category, timeOfDay)
    }

    private fun hit(id: String, score: Float, photoType: String, category: String, timeOfDay: String = "unknown") =
        SearchHit(id, score, score, meta(photoType, category, timeOfDay))

    private fun meta(photoType: String, category: String, timeOfDay: String = "unknown") = TemplateMeta(
        templateId = "$photoType-$category",
        photoType = photoType,
        sceneCategory = category,
        compositionType = "rule_of_thirds",
        subjectPosition = "center",
        horizonPosition = "not_visible",
        leadingLines = false,
        negativeSpace = "medium",
        lightingQuality = "soft",
        lightingDirection = "front",
        cameraAngle = "eye_level",
        cameraDistance = when (category) {
            "closeup_portrait", "food_still_life" -> "close"
            "lifestyle_still_life", "plant_still_life" -> "medium"
            else -> "wide"
        },
        cameraOrientation = "portrait",
        visualSummary = "",
        timeOfDay = timeOfDay
    )
}
