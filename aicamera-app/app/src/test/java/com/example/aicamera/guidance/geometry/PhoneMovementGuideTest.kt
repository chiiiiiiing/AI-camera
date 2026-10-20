package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.GuidanceCue
import org.junit.Assert.*
import org.junit.Test

class PhoneMovementGuideTest {
    @Test fun `phone arrow is opposite the rear preview subject displacement in all eight directions`() {
        for ((dx, dy) in listOf(-.3f to 0f, .3f to 0f, 0f to -.3f, 0f to .3f,
            -.3f to -.3f, -.3f to .3f, .3f to -.3f, .3f to .3f)) {
            val cue = GuidanceGeometry.guidanceCue(.5f + dx, .5f + dy, true)
            val arrow = GuidanceGeometry.phoneMovementVector(cue)!!
            assertEquals(kotlin.math.sign(dx), arrow.first, 0f)
            assertEquals(kotlin.math.sign(dy), arrow.second, 0f)
        }
    }
    @Test fun `mirrored front preview reverses only horizontal phone motion`() {
        val cue = GuidanceCue.MOVE_DOWN_RIGHT
        assertEquals(1f to 1f, GuidanceGeometry.phoneMovementVector(GuidanceGeometry.displayPhoneCue(cue, false)))
        assertEquals(-1f to 1f, GuidanceGeometry.phoneMovementVector(GuidanceGeometry.displayPhoneCue(cue, true)))
        assertNull(GuidanceGeometry.phoneMovementVector(GuidanceCue.SUBJECT_ALIGNED))
    }
}
