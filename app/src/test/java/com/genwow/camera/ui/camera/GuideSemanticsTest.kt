package com.genwow.camera.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSemanticsTest {
    @Test
    fun `translation cues map to translation visuals and need anchors`() {
        listOf(
            GuidanceCue.MOVE_LEFT,
            GuidanceCue.MOVE_RIGHT,
            GuidanceCue.MOVE_UP,
            GuidanceCue.MOVE_DOWN,
        ).forEach { cue ->
            assertEquals(GuideVisualFamily.TRANSLATION, cue.visualFamily())
            assertTrue(cue.requiresAnchors())
        }
    }

    @Test
    fun `pitch and yaw cues map to axis rotation visuals`() {
        listOf(
            GuidanceCue.PITCH_UP,
            GuidanceCue.PITCH_DOWN,
            GuidanceCue.YAW_LEFT,
            GuidanceCue.YAW_RIGHT,
        ).forEach { cue ->
            assertEquals(GuideVisualFamily.AXIS_ROTATION, cue.visualFamily())
            assertFalse(cue.requiresAnchors())
        }
    }

    @Test
    fun `roll cues map to planar rotation visuals`() {
        listOf(
            GuidanceCue.ROLL_CLOCKWISE,
            GuidanceCue.ROLL_COUNTERCLOCKWISE,
        ).forEach { cue ->
            assertEquals(GuideVisualFamily.PLANAR_ROTATION, cue.visualFamily())
            assertFalse(cue.requiresAnchors())
        }
    }

    @Test
    fun `subject cues map to subject visuals and aligned helper only flags subject states`() {
        assertEquals(GuideVisualFamily.SUBJECT, GuidanceCue.SUBJECT_TARGET.visualFamily())
        assertEquals(GuideVisualFamily.SUBJECT, GuidanceCue.SUBJECT_ALIGNED.visualFamily())
        assertTrue(GuidanceCue.SUBJECT_TARGET.requiresAnchors())
        assertTrue(GuidanceCue.SUBJECT_ALIGNED.requiresAnchors())
        assertTrue(GuidanceCue.SUBJECT_TARGET.isAlignedSubjectCue())
        assertTrue(GuidanceCue.SUBJECT_ALIGNED.isAlignedSubjectCue())
        assertFalse(GuidanceCue.MOVE_LEFT.isAlignedSubjectCue())
    }
}
