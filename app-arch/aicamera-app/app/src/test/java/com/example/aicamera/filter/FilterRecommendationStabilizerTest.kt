package com.example.aicamera.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRecommendationStabilizerTest {
    @Test
    fun `challenger must lead twice before switching`() {
        val stabilizer = FilterRecommendationStabilizer()
        assertEquals(FilterIds.PORTRAIT_SOFT, stabilizer.stabilize(rec(FilterIds.PORTRAIT_SOFT)).selectedFilterId)
        assertEquals(FilterIds.PORTRAIT_SOFT, stabilizer.stabilize(rec(FilterIds.FOOD_VIVID)).selectedFilterId)
        val switched = stabilizer.stabilize(rec(FilterIds.FOOD_VIVID))
        assertEquals(FilterIds.FOOD_VIVID, switched.selectedFilterId)
        assertTrue(switched.isStable)
    }

    @Test
    fun `low candidate margin keeps incumbent`() {
        val stabilizer = FilterRecommendationStabilizer()
        stabilizer.stabilize(rec(FilterIds.PORTRAIT_SOFT))
        repeat(3) {
            val result = stabilizer.stabilize(rec(FilterIds.FOOD_VIVID, topScore = 0.62f, secondScore = 0.59f))
            assertEquals(FilterIds.PORTRAIT_SOFT, result.selectedFilterId)
        }
    }

    @Test
    fun `unsafe frame immediately returns original`() {
        val stabilizer = FilterRecommendationStabilizer()
        stabilizer.stabilize(rec(FilterIds.URBAN_CRISP))
        val unsafe = FilterRecommendations.original(FilterReason.LOW_LIGHT_SAFETY)
        val result = stabilizer.stabilize(unsafe)
        assertEquals(FilterIds.ORIGINAL, result.selectedFilterId)
        assertTrue(result.isStable)
        assertFalse(result.shouldAutoPreview)
    }

    @Test
    fun `missing evidence immediately returns original`() {
        val stabilizer = FilterRecommendationStabilizer()
        stabilizer.stabilize(rec(FilterIds.LANDSCAPE_CLEAR))
        val result = stabilizer.stabilize(FilterRecommendations.original())
        assertEquals(FilterIds.ORIGINAL, result.selectedFilterId)
        assertFalse(result.shouldAutoPreview)
    }

    @Test
    fun `reset lets next high confidence recommendation apply immediately`() {
        val stabilizer = FilterRecommendationStabilizer()
        stabilizer.stabilize(rec(FilterIds.PORTRAIT_SOFT))
        stabilizer.reset()
        assertEquals(FilterIds.FOOD_VIVID, stabilizer.stabilize(rec(FilterIds.FOOD_VIVID)).selectedFilterId)
    }

    private fun rec(
        selected: String,
        topScore: Float = 0.82f,
        secondScore: Float = 0.40f
    ): FilterRecommendation {
        val other = if (selected == FilterIds.PORTRAIT_SOFT) FilterIds.FOOD_VIVID else FilterIds.PORTRAIT_SOFT
        return FilterRecommendation(
            selectedFilterId = selected,
            confidence = 0.8f,
            candidates = listOf(
                FilterCandidate(selected, topScore, listOf(FilterReason.FALLBACK)),
                FilterCandidate(other, secondScore, listOf(FilterReason.FALLBACK)),
                FilterCandidate(FilterIds.ORIGINAL, 0.1f, listOf(FilterReason.FALLBACK))
            ),
            sourceTemplateId = "source",
            isStable = false,
            shouldAutoPreview = true
        )
    }
}
