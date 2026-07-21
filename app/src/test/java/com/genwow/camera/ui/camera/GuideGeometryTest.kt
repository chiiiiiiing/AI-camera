package com.genwow.camera.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GuideGeometryTest {
    @Test
    fun `ellipse point stays inside normalized bounds`() {
        val point = ellipsePoint(
            centerX = 120f,
            centerY = 80f,
            radiusX = 60f,
            radiusY = 24f,
            angleDegrees = 37f,
        )

        assertTrue(point.x in 60f..180f)
        assertTrue(point.y in 56f..104f)
    }

    @Test
    fun `ellipse tangent is perpendicular to ellipse normal`() {
        val angle = 63f
        val radiusX = 72f
        val radiusY = 30f
        val tangent = ellipseTangent(radiusX, radiusY, angle, clockwise = true)
        val point = ellipsePoint(0f, 0f, radiusX, radiusY, angle)
        val normalX = point.x / (radiusX * radiusX)
        val normalY = point.y / (radiusY * radiusY)

        assertEquals(0f, tangent.x * normalX + tangent.y * normalY, 0.0001f)
    }

    @Test
    fun `fading axis alpha is symmetric and strongest at center`() {
        assertEquals(fadingAxisAlpha(-0.65f), fadingAxisAlpha(0.65f), 0f)
        assertTrue(fadingAxisAlpha(0f) > fadingAxisAlpha(0.65f))
        assertEquals(0f, fadingAxisAlpha(1f), 0f)
    }

    @Test
    fun `zero radii produce finite geometry`() {
        val point = ellipsePoint(20f, 30f, 0f, 0f, Float.NaN)
        val tangent = ellipseTangent(0f, 0f, Float.NaN, clockwise = false)

        assertTrue(point.x.isFinite() && point.y.isFinite())
        assertTrue(tangent.x.isFinite() && tangent.y.isFinite())
        assertTrue(abs(tangent.getDistance() - 1f) < 0.0001f)
    }

    @Test
    fun `near phase reduces motion amplitude to forty five percent`() {
        assertEquals(0.45f, guideMotionScale(GuidancePhase.NEAR), 0f)
        assertEquals(1f, guideMotionScale(GuidancePhase.ADJUST), 0f)
    }

    @Test
    fun `subject contour points are scaled from each ai frame`() {
        val first = SubjectOutline(
            listOf(SubjectContour(listOf(NormalizedPoint(0.2f, 0.25f), NormalizedPoint(0.4f, 0.75f)))),
        )
        val second = SubjectOutline(
            listOf(SubjectContour(listOf(NormalizedPoint(0.55f, 0.2f), NormalizedPoint(0.8f, 0.8f)))),
        )

        val firstPoints = scaledSubjectContours(first, width = 200f, height = 400f)
        val secondPoints = scaledSubjectContours(second, width = 200f, height = 400f)

        assertEquals(40f, firstPoints.single().first().x, 0f)
        assertEquals(110f, secondPoints.single().first().x, 0f)
        assertTrue(firstPoints != secondPoints)
    }

    @Test
    fun `invalid ai outline points are discarded and valid points are clamped`() {
        val outline = SubjectOutline(
            listOf(
                SubjectContour(
                    listOf(
                        NormalizedPoint(Float.NaN, 0.4f),
                        NormalizedPoint(-0.2f, 0.25f),
                        NormalizedPoint(1.3f, 0.75f),
                    ),
                ),
            ),
        )

        val points = scaledSubjectContours(outline, width = 100f, height = 200f).single()

        assertEquals(2, points.size)
        assertEquals(0f, points.first().x, 0f)
        assertEquals(100f, points.last().x, 0f)
    }
}
