package com.example.aicamera.pipeline

import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.guidance.geometry.GuidanceGeometry
import org.junit.Assert.*
import org.junit.Test

class AnalysisCropTest {
    @Test
    fun `3 by 4 buffer becomes 9 by 16 viewport with matching normalized subject location`() {
        val crop = AnalysisCrop(1200, 1600, 150, 0, 1050, 1600)
        assertEquals(9f / 16f, crop.displayAspectRatio(0), 0f)
        assertEquals(.5f to .5f, crop.pointInDisplay(600f, 800f, 0, false))
        assertEquals(0f to .5f, crop.pointInDisplay(150f, 800f, 0, false))
        assertEquals(1f to .5f, crop.pointInDisplay(1050f, 800f, 0, false))
        assertTrue(crop.pointInDisplay(100f, 800f, 0, false).first < 0f)
    }

    @Test
    fun `cropping precedes rotation and front camera mirror is applied only once`() {
        val crop = AnalysisCrop(1600, 1200, 0, 150, 1600, 1050)
        assertEquals(9f / 16f, crop.displayAspectRatio(90), 0f)
        assertEquals(1f to 0f, crop.pointInDisplay(0f, 150f, 90, false))
        assertEquals(0f to 0f, crop.pointInDisplay(0f, 150f, 90, true))
        assertEquals(.5f to .5f, crop.pointInDisplay(800f, 600f, 270, true))
    }

    @Test
    fun `circle accepts equal pixel offsets on either axis after 3 by 4 to 9 by 16 crop`() {
        val crop = AnalysisCrop(1200, 1600, 150, 0, 1050, 1600)
        val aspect = crop.displayAspectRatio(0)
        // radius=.08 on the 900px short side means 72px, in either direction.
        for ((dx, dy) in listOf(60f to 0f, 0f to 60f)) {
            val p = crop.pointInDisplay(600f + dx, 800f + dy, 0, false)
            assertTrue(GuidanceGeometry.isSubjectAligned(p.first, p.second, .5f, .5f, .08f, aspect))
        }
        val outside = crop.pointInDisplay(600f, 880f, 0, false)
        assertFalse(GuidanceGeometry.isSubjectAligned(outside.first, outside.second, .5f, .5f, .08f, aspect))
        assertEquals(GuidanceCue.MOVE_DOWN, GuidanceGeometry.guidanceCue(outside.first, outside.second, true,
            targetTolerance = .08f, coordinateAspectRatio = aspect))
    }

    @Test
    fun `invalid viewport cannot silently fall back to full frame`() {
        for (bounds in listOf(intArrayOf(-1, 0, 100, 100), intArrayOf(0, 0, 121, 100),
            intArrayOf(10, 0, 10, 100))) {
            try {
                AnalysisCrop(120, 160, bounds[0], bounds[1], bounds[2], bounds[3])
                fail("Expected invalid viewport to be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
