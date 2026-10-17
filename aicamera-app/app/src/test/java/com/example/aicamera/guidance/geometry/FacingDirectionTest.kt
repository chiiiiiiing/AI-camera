package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.NormalizedPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FacingDirectionTest {
    private val box = NormalizedBox(.2f, .15f, .8f, .85f)
    private val landmarks = listOf(
        NormalizedPoint(.38f, .4f), NormalizedPoint(.62f, .4f),
        NormalizedPoint(.55f, .52f), NormalizedPoint(.53f, .65f)
    )
    private fun estimate(points: List<NormalizedPoint?> = landmarks, yaw: Float? = 22f,
                         pitch: Float? = 0f, face: NormalizedBox = box,
                         width: Int = 480, height: Int = 480): Float? =
        FacingDirection.fromDisplayLandmarks(face, points[0], points[1], points[2], points[3],
            yaw, pitch, width, height)

    @Test fun `head direction follows displayed landmark geometry not yaw sign or eye naming`() {
        assertEquals(1f, estimate())
        assertEquals(1f, estimate(yaw = -22f))
        assertEquals(1f, estimate(listOf(landmarks[1], landmarks[0], landmarks[2], landmarks[3])))
        assertEquals(-1f, estimate(landmarks.map { NormalizedPoint(1f - it.x, it.y) }))
    }

    @Test fun `frontal near frontal extreme yaw and tilted head abstain`() {
        for (yaw in listOf(null, Float.NaN, 0f, 11.9f, 35.1f, 80f)) assertNull(estimate(yaw = yaw))
        for (pitch in listOf(null, Float.NaN, -18.1f, 45f)) assertNull(estimate(pitch = pitch))
        assertNull(estimate(landmarks.toMutableList().also { it[2] = NormalizedPoint(.5f, .52f) }))
        // Known yaw is still insufficient when the available image geometry is ambiguous.
        assertNull(estimate(landmarks.toMutableList().also { it[2] = NormalizedPoint(.52f, .52f) }))
    }

    @Test fun `missing occluded and corrupted landmark geometries abstain`() {
        for (missing in landmarks.indices) {
            assertNull(estimate(landmarks.mapIndexed { i, p -> p.takeIf { i != missing } }))
        }
        for (corrupt in listOf(NormalizedPoint(Float.NaN, .5f), NormalizedPoint(.99f, .5f),
            NormalizedPoint(.55f, .3f), NormalizedPoint(.55f, .79f))) {
            assertNull(estimate(landmarks.toMutableList().also { it[2] = corrupt }))
        }
        assertNull(estimate(landmarks.toMutableList().also { it[1] = it[0] }))
        assertNull(estimate(landmarks.toMutableList().also { it[3] = NormalizedPoint(.53f, .42f) }))
    }

    @Test fun `low resolution clipped and invalid faces never produce craft direction`() {
        assertNull(estimate(width = 100, height = 100))
        assertNull(estimate(width = 0))
        assertNull(estimate(face = box.copy(left = .001f)))
        assertNull(estimate(face = box.copy(right = Float.NaN)))
        assertNull(estimate(face = box.copy(right = .1f)))
    }

    @Test fun `pixel geometry is stable across portrait and landscape normalized aspect ratios`() {
        // Identical pixel landmarks embedded into frames of different widths.
        for (width in listOf(360, 480, 640)) {
            val scale = 480f / width
            val scaled = landmarks.map { NormalizedPoint((it.x - .5f) * scale + .5f, it.y) }
            val scaledBox = NormalizedBox((box.left - .5f) * scale + .5f, box.top,
                (box.right - .5f) * scale + .5f, box.bottom)
            assertEquals(1f, estimate(scaled, face = scaledBox, width = width))
        }
    }

    @Test fun `eye axis projection tolerates small head roll without confusing roll for yaw`() {
        fun rotate(p: NormalizedPoint, degrees: Float): NormalizedPoint {
            val radians = Math.toRadians(degrees.toDouble())
            val x = p.x - .5f; val y = p.y - .5f
            return NormalizedPoint((x * cos(radians) - y * sin(radians) + .5).toFloat(),
                (x * sin(radians) + y * cos(radians) + .5).toFloat())
        }
        for (roll in listOf(-15f, 0f, 15f)) assertEquals(1f, estimate(landmarks.map { rotate(it, roll) }))
        assertNull(estimate(landmarks.map { rotate(it, 30f) }))
    }

    @Test fun `four sensor rotations and front mirroring obey upright display coordinate contract`() {
        fun rotate(p: NormalizedPoint, degrees: Int): NormalizedPoint = when ((degrees + 360) % 360) {
            0 -> p
            90 -> NormalizedPoint(1f - p.y, p.x)
            180 -> NormalizedPoint(1f - p.x, 1f - p.y)
            else -> NormalizedPoint(p.y, 1f - p.x)
        }
        // Contract simulation: real detection happens AFTER the bitmap is rotated
        // upright and mirrored. This does not claim to exercise Android's Bitmap.
        for (rotation in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val sensor = landmarks.map { rotate(it, -rotation) }
            val display = sensor.map { rotate(it, rotation) }.map {
                if (front) NormalizedPoint(1f - it.x, it.y) else it
            }
            assertEquals(if (front) -1f else 1f, estimate(display))
        }
    }

    @Test fun `two fresh consistent tracked samples required and caches cannot accumulate`() {
        val stable = FacingDirection.Stabilizer()
        assertNull(stable.observe(1f, 7, 1000, 1))
        assertNull(stable.observe(1f, 7, 1000, 1))
        assertNull(stable.observe(1f, 7, 900, 1))
        assertEquals(1f, stable.observe(1f, 7, 1250, 1))
        assertNull(stable.observe(-1f, 7, 1500, 1))
        assertEquals(-1f, stable.observe(-1f, 7, 1750, 1))
    }

    @Test fun `lost identity changed viewport and stale evidence reset orientation stability`() {
        val stable = FacingDirection.Stabilizer()
        assertNull(stable.observe(1f, 7, 1000, 1))
        assertNull(stable.observe(1f, 8, 1250, 1))
        assertNull(stable.observe(1f, 8, 1500, 2))
        assertNull(stable.observe(1f, 8, 3101, 2))
        assertNull(stable.observe(null, 8, 3351, 2))
        assertNull(stable.observe(1f, 8, 3601, 2))
        assertEquals(1f, stable.observe(1f, 8, 3851, 2))
        assertNull(stable.observe(1f, null, 4101, 2))
        assertNull(stable.observe(1f, 8, 4351, 2))
        stable.reset()
        assertNull(stable.observe(1f, 8, 4601, 2))
    }
}
