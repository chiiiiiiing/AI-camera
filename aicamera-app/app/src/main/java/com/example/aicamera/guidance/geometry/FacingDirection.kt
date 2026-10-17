package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Conservative horizontal HEAD orientation evidence for look-room composition.
 * All landmarks must already be upright and mirrored exactly like the preview.
 * The sign comes from image geometry, never ML Kit Euler-Y's camera convention.
 * This is not eye gaze, a pose command, or a probability of aesthetic improvement.
 *
 * ML Kit does not expose landmark confidence. Require both eyes, nose and bottom
 * mouth, plausible geometry, sufficient pixels and moderate absolute head pose.
 * Missing landmarks, invalid geometry, strong profiles and frontal inputs
 * abstain instead of guessing a side. These gates cannot prove absence of occlusion.
 */
object FacingDirection {
    fun fromDisplayLandmarks(
        face: NormalizedBox,
        firstEye: NormalizedPoint?,
        secondEye: NormalizedPoint?,
        nose: NormalizedPoint?,
        mouthBottom: NormalizedPoint?,
        yawDegrees: Float?,
        pitchDegrees: Float?,
        imageWidth: Int,
        imageHeight: Int
    ): Float? {
        if (imageWidth <= 0 || imageHeight <= 0 ||
            !listOf(face.left, face.top, face.right, face.bottom).all { it.isFinite() && it in 0f..1f } ||
            face.width <= 0f || face.height <= 0f ||
            face.width * imageWidth < 80f || face.height * imageHeight < 80f ||
            face.left <= .01f || face.top <= .01f || face.right >= .99f || face.bottom >= .99f ||
            yawDegrees == null || !yawDegrees.isFinite() || abs(yawDegrees) !in 12f..35f ||
            pitchDegrees == null || !pitchDegrees.isFinite() || abs(pitchDegrees) > 18f
        ) return null

        val points = listOf(firstEye, secondEye, nose, mouthBottom)
        if (points.any { p -> p == null || !p.x.isFinite() || !p.y.isFinite() ||
                p.x !in face.left..face.right || p.y !in face.top..face.bottom }) return null
        // Sorting by screen X makes detector landmark naming irrelevant to mirroring.
        val eyes = listOf(firstEye!!, secondEye!!).sortedBy { it.x }
        val dx = (eyes[1].x - eyes[0].x) * imageWidth
        val dy = (eyes[1].y - eyes[0].y) * imageHeight
        val distance = hypot(dx, dy)
        if (distance < 18f || distance / (face.width * imageWidth) !in .2f.. .8f ||
            dx <= 0f || abs(dy / dx) > .40f) return null // Eye-axis roll under about 22 degrees.
        val eyeX = (eyes[0].x + eyes[1].x) * imageWidth * .5f
        val eyeY = (eyes[0].y + eyes[1].y) * imageHeight * .5f
        // Project onto the eye axis and its downward normal; pixel units avoid
        // different behaviour in portrait and landscape aspect ratios.
        fun along(p: NormalizedPoint): Float =
            ((p.x * imageWidth - eyeX) * dx + (p.y * imageHeight - eyeY) * dy) / (distance * distance)
        fun down(p: NormalizedPoint): Float =
            (-(p.x * imageWidth - eyeX) * dy + (p.y * imageHeight - eyeY) * dx) / (distance * distance)
        val noseSide = along(nose!!)
        val noseDown = down(nose)
        val mouthSide = along(mouthBottom!!)
        val mouthDown = down(mouthBottom)
        if (abs(noseSide) !in .13f.. .55f || noseDown !in .15f..1.1f ||
            mouthDown !in .5f..1.8f || mouthDown - noseDown !in .15f..1.0f ||
            abs(mouthSide) > .7f || abs(noseSide - mouthSide) > .6f) return null
        return if (noseSide < 0f) -1f else 1f
    }

    /** Two new consistent tracked samples; caches, a lost face and context changes never accumulate. */
    class Stabilizer {
        private var lastDirection: Float? = null
        private var lastTrackingId: Int? = null
        private var lastViewport = -1L
        private var lastTime = -1L

        @Synchronized
        fun observe(direction: Float?, trackingId: Int?, sampledAtMs: Long, viewportVersion: Long): Float? {
            if ((direction != -1f && direction != 1f) || trackingId == null || sampledAtMs < 0L) {
                reset()
                return null
            }
            if (sampledAtMs <= lastTime) return null
            val stable = direction == lastDirection && trackingId == lastTrackingId &&
                viewportVersion == lastViewport && sampledAtMs - lastTime in 100L..1500L
            lastDirection = direction
            lastTrackingId = trackingId
            lastViewport = viewportVersion
            lastTime = sampledAtMs
            return direction.takeIf { stable }
        }

        @Synchronized
        fun reset() {
            lastDirection = null
            lastTrackingId = null
            lastViewport = -1L
            lastTime = -1L
        }
    }
}
