package com.example.aicamera.filter

import com.example.aicamera.pipeline.SearchHit
import com.example.aicamera.pipeline.TemplateMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRecommendationEngineTest {
    private val engine = FilterRecommendationEngine()
    private val normalStats = FrameColorStats(0.55f, 0.12f, 0.40f, 0f, 0f, 0f)

    @Test
    fun `metadata categories produce their expected semantic filters`() {
        assertSelected("portrait", "closeup_portrait", FilterIds.PORTRAIT_SOFT)
        assertSelected("still_life", "food_still_life", FilterIds.FOOD_VIVID)
        assertSelected("still_life", "lifestyle_still_life", FilterIds.STILL_LIFE_CLEAN)
        assertSelected("still_life", "plant_still_life", FilterIds.STILL_LIFE_CLEAN)
        assertSelected("landscape", "natural_landscape", FilterIds.LANDSCAPE_CLEAR)
        assertSelected("landscape", "urban_landscape", FilterIds.URBAN_CRISP)
    }

    @Test
    fun `golden hour adds an explainable warm candidate`() {
        val result = engine.recommend(
            consistentHits("landscape", "natural_landscape", timeOfDay = "golden_hour"),
            normalStats
        )
        val warm = result.candidates.firstOrNull { it.filterId == FilterIds.GOLDEN_WARM }
        assertTrue(warm != null)
        assertTrue(FilterReason.GOLDEN_HOUR in warm!!.reasons)
    }

    @Test
    fun `frame warmth weakly adjusts golden recommendation`() {
        val hits = consistentHits("landscape", "natural_landscape", timeOfDay = "golden_hour")
        val warm = engine.recommend(hits, normalStats.copy(warmth = 0.30f))
        val cool = engine.recommend(hits, normalStats.copy(warmth = -0.30f))
        assertTrue(scoreOf(warm, FilterIds.GOLDEN_WARM) > scoreOf(cool, FilterIds.GOLDEN_WARM))
    }

    @Test
    fun `high saturation suppresses food vivid`() {
        val normal = engine.recommend(consistentHits("still_life", "food_still_life"), normalStats)
        val saturated = engine.recommend(
            consistentHits("still_life", "food_still_life"),
            normalStats.copy(meanSaturation = 0.95f)
        )
        assertTrue(scoreOf(saturated, FilterIds.FOOD_VIVID) < scoreOf(normal, FilterIds.FOOD_VIVID))
        assertTrue(FilterReason.HIGH_SATURATION in candidate(saturated, FilterIds.FOOD_VIVID).reasons)
    }

    @Test
    fun `high contrast suppresses aggressive urban style`() {
        val normal = engine.recommend(consistentHits("landscape", "urban_landscape"), normalStats)
        val highContrast = engine.recommend(
            consistentHits("landscape", "urban_landscape"),
            normalStats.copy(luminanceStdDev = 0.35f, shadowClipRatio = 0.30f)
        )
        assertTrue(scoreOf(highContrast, FilterIds.URBAN_CRISP) < scoreOf(normal, FilterIds.URBAN_CRISP))
        assertTrue(FilterReason.HIGH_CONTRAST in candidate(highContrast, FilterIds.URBAN_CRISP).reasons)
    }

    @Test
    fun `severe highlight clipping prioritizes original`() {
        val result = engine.recommend(
            consistentHits("still_life", "food_still_life"),
            normalStats.copy(highlightClipRatio = 0.45f)
        )
        assertEquals(FilterIds.ORIGINAL, result.selectedFilterId)
        assertFalse(result.shouldAutoPreview)
        assertTrue(FilterReason.HIGHLIGHT_SAFETY in candidate(result, FilterIds.ORIGINAL).reasons)
    }

    @Test
    fun `low light immediately falls back to original`() {
        val result = engine.recommend(
            consistentHits("landscape", "urban_landscape"),
            normalStats.copy(meanLuminance = 0.05f, shadowClipRatio = 0.7f)
        )
        assertEquals(FilterIds.ORIGINAL, result.selectedFilterId)
        assertFalse(result.shouldAutoPreview)
        assertTrue(FilterReason.LOW_LIGHT_SAFETY in candidate(result, FilterIds.ORIGINAL).reasons)
    }

    @Test
    fun `top five majority can defeat a different top one`() {
        val hits = listOf(
            hit("food", 0.90f, "still_life", "food_still_life"),
            hit("p1", 0.86f, "portrait", "closeup_portrait"),
            hit("p2", 0.83f, "portrait", "closeup_portrait"),
            hit("p3", 0.80f, "portrait", "closeup_portrait"),
            hit("p4", 0.77f, "portrait", "closeup_portrait")
        )
        val result = engine.recommend(hits, normalStats)
        assertEquals(FilterIds.PORTRAIT_SOFT, result.selectedFilterId)
    }

    @Test
    fun `unknown empty and illegal evidence safely falls back`() {
        assertEquals(FilterIds.ORIGINAL, engine.recommend(emptyList(), normalStats).selectedFilterId)

        val unknown = listOf(
            SearchHit("unknown", Float.NaN, Float.POSITIVE_INFINITY, meta("unknown", "mystery"))
        )
        val result = engine.recommend(
            unknown,
            FrameColorStats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        )
        assertEquals(FilterIds.ORIGINAL, result.selectedFilterId)
        assertContract(result)
    }

    @Test
    fun `low model margin is explainable and disables auto preview`() {
        val hits = listOf(
            hit("a", 0.800f, "portrait", "closeup_portrait"),
            hit("b", 0.796f, "portrait", "closeup_portrait"),
            hit("c", 0.75f, "portrait", "closeup_portrait")
        )
        val result = engine.recommend(hits, normalStats)
        assertEquals(FilterIds.ORIGINAL, result.selectedFilterId)
        assertFalse(result.shouldAutoPreview)
        assertTrue(FilterReason.LOW_MODEL_MARGIN in candidate(result, FilterIds.ORIGINAL).reasons)
    }

    @Test
    fun `output obeys stable id top3 score and reason contract`() {
        val result = engine.recommend(
            consistentHits("landscape", "natural_landscape", "golden_hour"),
            normalStats
        )
        assertContract(result)
        assertTrue(result.selectedFilterId in FilterIds.ALL)
        assertTrue(result.candidates.any { it.filterId == result.selectedFilterId })
    }

    private fun assertSelected(photoType: String, category: String, expected: String) {
        val result = engine.recommend(consistentHits(photoType, category), normalStats)
        assertEquals(expected, result.selectedFilterId)
        assertTrue(result.candidates.first().reasons.isNotEmpty())
    }

    private fun consistentHits(
        photoType: String,
        category: String,
        timeOfDay: String = "unknown"
    ): List<SearchHit> = listOf(0.91f, 0.88f, 0.84f, 0.80f, 0.76f).mapIndexed { index, score ->
        hit("t$index", score, photoType, category, timeOfDay)
    }

    private fun hit(
        id: String,
        score: Float,
        photoType: String,
        category: String,
        timeOfDay: String = "unknown"
    ) = SearchHit(id, score, score, meta(photoType, category, timeOfDay))

    private fun meta(
        photoType: String,
        category: String,
        timeOfDay: String = "unknown"
    ) = TemplateMeta(
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
        cameraDistance = "medium",
        cameraOrientation = "portrait",
        visualSummary = "",
        lightingContrast = "medium",
        timeOfDay = timeOfDay
    )

    private fun scoreOf(result: FilterRecommendation, id: String): Float = candidate(result, id).score

    private fun candidate(result: FilterRecommendation, id: String): FilterCandidate =
        result.candidates.first { it.filterId == id }

    private fun assertContract(result: FilterRecommendation) {
        assertEquals(FilterContract.VERSION, result.contractVersion)
        assertTrue(result.confidence.isFinite() && result.confidence in 0f..1f)
        assertTrue(result.candidates.size in 1..3)
        assertEquals(result.candidates.map { it.filterId }.distinct(), result.candidates.map { it.filterId })
        assertEquals(result.candidates.sortedByDescending { it.score }, result.candidates)
        assertTrue(result.candidates.all {
            it.filterId in FilterIds.ALL && it.score.isFinite() && it.score in 0f..1f && it.reasons.isNotEmpty()
        })
    }
}
