package com.example.aicamera.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameColorStatsExtractorTest {
    @Test
    fun `pure black has clipped shadows and zero saturation`() {
        val stats = solid(0xff000000.toInt())
        assertEquals(0f, stats.meanLuminance, EPSILON)
        assertEquals(0f, stats.luminanceStdDev, EPSILON)
        assertEquals(0f, stats.meanSaturation, EPSILON)
        assertEquals(1f, stats.shadowClipRatio, EPSILON)
        assertEquals(0f, stats.highlightClipRatio, EPSILON)
    }

    @Test
    fun `pure white has clipped highlights and zero saturation`() {
        val stats = solid(0xffffffff.toInt())
        assertEquals(1f, stats.meanLuminance, EPSILON)
        assertEquals(0f, stats.meanSaturation, EPSILON)
        assertEquals(0f, stats.shadowClipRatio, EPSILON)
        assertEquals(1f, stats.highlightClipRatio, EPSILON)
    }

    @Test
    fun `neutral gray is neutral and finite`() {
        val stats = solid(0xff808080.toInt())
        assertEquals(128f / 255f, stats.meanLuminance, EPSILON)
        assertEquals(0f, stats.meanSaturation, EPSILON)
        assertEquals(0f, stats.warmth, EPSILON)
        assertFiniteAndNormalized(stats)
    }

    @Test
    fun `red is warm and blue is cool`() {
        val red = solid(0xffff0000.toInt())
        val blue = solid(0xff0000ff.toInt())
        assertTrue(red.warmth > 0.9f)
        assertTrue(blue.warmth < -0.9f)
        assertEquals(1f, red.meanSaturation, EPSILON)
        assertEquals(1f, blue.meanSaturation, EPSILON)
    }

    @Test
    fun `empty and non finite values are safely normalized`() {
        val empty = FrameColorStatsExtractor.extractPixels(IntArray(0), 0, 0)
        assertEquals(FrameColorStats.NEUTRAL, empty)

        val dirty = FrameColorStats(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NaN,
            2f,
            -1f
        ).sanitized()
        assertFiniteAndNormalized(dirty)
        assertEquals(0f, dirty.warmth, EPSILON)
        assertEquals(1f, dirty.shadowClipRatio, EPSILON)
        assertEquals(0f, dirty.highlightClipRatio, EPSILON)
    }

    private fun solid(color: Int): FrameColorStats =
        FrameColorStatsExtractor.extractPixels(IntArray(16) { color }, 4, 4)

    private fun assertFiniteAndNormalized(stats: FrameColorStats) {
        val unitValues = listOf(
            stats.meanLuminance,
            stats.luminanceStdDev,
            stats.meanSaturation,
            stats.shadowClipRatio,
            stats.highlightClipRatio
        )
        assertTrue(unitValues.all { it.isFinite() && it in 0f..1f })
        assertTrue(stats.warmth.isFinite() && stats.warmth in -1f..1f)
    }

    companion object {
        private const val EPSILON = 0.002f
    }
}
