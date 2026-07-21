package com.genwow.camera.ui.camera

/** A single, unambiguous movement requested from the user. */
enum class GuidanceCue {
    NONE,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    PITCH_UP,
    PITCH_DOWN,
    YAW_LEFT,
    YAW_RIGHT,
    ROLL_CLOCKWISE,
    ROLL_COUNTERCLOCKWISE,
    /** The AI supplied a per-frame subject contour that the user should match. */
    SUBJECT_TARGET,
    /** The latest AI contour is stable at the recommended composition. */
    SUBJECT_ALIGNED,
}

/** Visual intensity of the composition guidance. */
enum class GuidancePhase {
    NEUTRAL,
    ADJUST,
    NEAR,
    WRONG_DIRECTION,
    ALIGNED,
}

/** A point from the AI output, normalized to the analyzed frame's 0..1 bounds. */
data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

/** One open or closed contour returned by the AI for the current frame. */
data class SubjectContour(
    val points: List<NormalizedPoint>,
    val closed: Boolean = false,
)

/**
 * Per-frame subject geometry produced by the AI boundary.
 *
 * This deliberately stores geometry rather than a fixed drawable path. Every analyzed frame can
 * replace these contours, allowing the Compose overlay to follow the detected subject in real time.
 */
data class SubjectOutline(
    val contours: List<SubjectContour>,
    val confidence: Float = 1f,
)

/**
 * Optional AI-provided anchors for guidance visuals.
 *
 * `current` usually represents the subject center or current framing center, while `target`
 * represents the recommended position in the same normalized coordinate space.
 *
 * Translation cues interpret these points as "current frame center vs recommended frame center".
 * Subject cues interpret them as "current detected body center vs recommended body center".
 */
data class GuidanceAnchors(
    val current: NormalizedPoint? = null,
    val target: NormalizedPoint? = null,
)

enum class AnalysisStatus {
    IDLE,
    ANALYZING,
    TRACKING,
    FAILED,
}

enum class CapturePhase {
    IDLE,
    CAPTURING,
    SUCCESS,
    FAILURE,
}

data class GuidanceState(
    val cue: GuidanceCue = GuidanceCue.NONE,
    val phase: GuidancePhase = GuidancePhase.NEUTRAL,
    val progress: Float = 0f,
    val subjectOutline: SubjectOutline? = null,
    val anchors: GuidanceAnchors? = null,
    /** State-machine bookkeeping. Presentation code should not animate this value. */
    val stableFrameCount: Int = 0,
    /** Prevents a single noisy frame from dismissing the aligned state. */
    val unstableFrameCount: Int = 0,
)

data class CameraUiState(
    val guidance: GuidanceState = GuidanceState(),
    val analysisStatus: AnalysisStatus = AnalysisStatus.IDLE,
    val capturePhase: CapturePhase = CapturePhase.IDLE,
    val errorMessage: String? = null,
) {
    val isCaptureInProgress: Boolean
        get() = capturePhase == CapturePhase.CAPTURING
}

sealed interface CameraEvent {
    data object AnalysisStarted : CameraEvent

    data class FrameEvaluated(
        val cue: GuidanceCue,
        val progress: Float,
        val subjectOutline: SubjectOutline? = null,
        val anchors: GuidanceAnchors? = null,
    ) : CameraEvent

    data class WrongDirectionDetected(
        val cue: GuidanceCue,
        val progress: Float,
    ) : CameraEvent

    data object SubjectLost : CameraEvent

    data class AnalysisFailed(val message: String) : CameraEvent

    data object CameraRecovered : CameraEvent

    data object CaptureRequested : CameraEvent

    data object CaptureSucceeded : CameraEvent

    data class CaptureFailed(val message: String) : CameraEvent

    data object CaptureFeedbackConsumed : CameraEvent
}
