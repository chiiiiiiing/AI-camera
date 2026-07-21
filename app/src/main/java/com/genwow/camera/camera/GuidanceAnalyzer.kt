package com.genwow.camera.camera

import com.genwow.camera.ui.camera.GuidanceCue
import com.genwow.camera.ui.camera.GuidanceAnchors
import com.genwow.camera.ui.camera.SubjectOutline

/**
 * Boundary for the future on-device composition model.
 *
 * The repository currently contains neither a model nor an output schema, so production code does
 * not fabricate inference. A real analyzer can implement this contract without changing the UI.
 */
fun interface GuidanceAnalyzer {
    suspend fun analyze(frame: CameraFrame): GuidanceSample
}

data class CameraFrame(
    val timestampNanos: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
)

data class GuidanceSample(
    val hasSubject: Boolean,
    /**
     * Semantic movement requested by the analyzer.
     *
     * The UI maps this cue into exactly one overlay family:
     * - MOVE_* -> translation target (crosshair + target circle)
     * - PITCH_* / YAW_* -> axis + perspective arc
     * - ROLL_* -> in-plane phone rotation
     * - SUBJECT_* -> live contour / stance guidance
     */
    val cue: GuidanceCue,
    /** Normalized progress toward the target composition, where 1f means aligned. */
    val progress: Float,
    val wrongDirection: Boolean = false,
    /** Latest normalized contours from the AI. Null for non-subject guidance. */
    val subjectOutline: SubjectOutline? = null,
    /**
     * Optional AI-provided current/target anchors.
     *
     * Translation cues use these for current-vs-target framing markers. Subject cues use them for
     * body placement targets. Rotation cues may omit them.
     */
    val anchors: GuidanceAnchors? = null,
)
