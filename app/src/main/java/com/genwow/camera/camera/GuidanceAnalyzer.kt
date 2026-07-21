package com.genwow.camera.camera

import com.genwow.camera.ui.camera.GuidanceCue
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
    val cue: GuidanceCue,
    val progress: Float,
    val wrongDirection: Boolean = false,
    /** Latest normalized contours from the AI. Null for non-subject guidance. */
    val subjectOutline: SubjectOutline? = null,
)
