package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.GuidanceCue
import org.junit.Assert.*
import org.junit.Test

class GuidanceVerificationTest {
    @Test
    fun `level phone cannot certify missing model or portrait`() {
        assertEquals(0f, GuidanceGeometry.verifiedProgress(GuidanceCue.NONE, 1f, false, false, false), 0f)
        assertEquals(0f, GuidanceGeometry.verifiedProgress(GuidanceCue.SUBJECT_TARGET, 1f, true, false), 0f)
        assertEquals(0f, GuidanceGeometry.verifiedProgress(GuidanceCue.SUBJECT_ALIGNED, 1f, true, true, false), 0f)
        assertTrue(GuidanceGeometry.verifiedProgress(GuidanceCue.NONE, 1f, false, false,
            hasCompositionTarget = false) < .72f)
    }

    @Test
    fun `missing and corrupt signals remain unverified`() {
        assertEquals(0f, GuidanceGeometry.verifiedProgress(GuidanceCue.NONE, null, false, false), 0f)
        assertEquals(0f, GuidanceGeometry.verifiedProgress(GuidanceCue.SUBJECT_ALIGNED, Float.NaN, true, true), 0f)
        assertNull(GuidanceGeometry.compositionProgress(Float.NaN, 0.5f, horizonAngleDeg = Float.NaN,
            pitchAngleDeg = Float.POSITIVE_INFINITY, yawAngleDeg = Float.NaN, yawTargetDeg = 0f))
        assertEquals(GuidanceCue.SUBJECT_TARGET, GuidanceGeometry.guidanceCue(Float.NaN, 0.5f, true,
            pitchAngleDeg = Float.POSITIVE_INFINITY))
    }

    @Test
    fun `outstanding adjustment cannot reach aligned threshold`() {
        for (cue in listOf(GuidanceCue.MOVE_LEFT, GuidanceCue.PITCH_UP, GuidanceCue.YAW_LEFT)) {
            assertTrue(GuidanceGeometry.verifiedProgress(cue, 0.99f, true, true) < 0.92f)
        }
        assertTrue(GuidanceGeometry.verifiedProgress(GuidanceCue.SUBJECT_ALIGNED, 0.75f, true, true) >= 0.92f)
    }
}
