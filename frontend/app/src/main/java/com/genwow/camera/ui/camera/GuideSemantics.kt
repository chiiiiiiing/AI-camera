package com.genwow.camera.ui.camera

/**
 * One visual family per AI cue.
 *
 * This indirection keeps production analyzer output and debug/demo output aligned to the exact
 * same rendering contract: every cue must map to one, and only one, overlay family.
 */
enum class GuideVisualFamily {
    NONE,
    TRANSLATION,
    AXIS_ROTATION,
    PLANAR_ROTATION,
    SUBJECT,
}

internal fun GuidanceCue.visualFamily(): GuideVisualFamily = when (this) {
    GuidanceCue.NONE -> GuideVisualFamily.NONE
    GuidanceCue.MOVE_LEFT,
    GuidanceCue.MOVE_RIGHT,
    GuidanceCue.MOVE_UP,
    GuidanceCue.MOVE_DOWN,
    -> GuideVisualFamily.TRANSLATION

    GuidanceCue.PITCH_UP,
    GuidanceCue.PITCH_DOWN,
    GuidanceCue.YAW_LEFT,
    GuidanceCue.YAW_RIGHT,
    -> GuideVisualFamily.AXIS_ROTATION

    GuidanceCue.ROLL_CLOCKWISE,
    GuidanceCue.ROLL_COUNTERCLOCKWISE,
    -> GuideVisualFamily.PLANAR_ROTATION

    GuidanceCue.SUBJECT_TARGET,
    GuidanceCue.SUBJECT_ALIGNED,
    -> GuideVisualFamily.SUBJECT
}

internal fun GuidanceCue.requiresAnchors(): Boolean = when (visualFamily()) {
    GuideVisualFamily.TRANSLATION,
    GuideVisualFamily.SUBJECT,
    -> true

    else -> false
}

internal fun GuidanceCue.isAlignedSubjectCue(): Boolean =
    this == GuidanceCue.SUBJECT_TARGET || this == GuidanceCue.SUBJECT_ALIGNED
