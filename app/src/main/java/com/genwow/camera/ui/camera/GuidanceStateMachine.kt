package com.genwow.camera.ui.camera

data class GuidanceStateMachineConfig(
    val nearThreshold: Float = 0.72f,
    val alignedThreshold: Float = 0.92f,
    val framesToAlign: Int = 3,
    val framesToExitAligned: Int = 2,
) {
    init {
        require(nearThreshold in 0f..1f) { "nearThreshold must be between 0 and 1" }
        require(alignedThreshold in nearThreshold..1f) {
            "alignedThreshold must be between nearThreshold and 1"
        }
        require(framesToAlign > 0) { "framesToAlign must be positive" }
        require(framesToExitAligned > 0) { "framesToExitAligned must be positive" }
    }
}

/**
 * Deterministic reducer for camera guidance and capture feedback.
 *
 * Keeping frame hysteresis here prevents transient model noise from restarting Compose animations.
 * The reducer has no Android dependency and can be owned by a ViewModel or tested on the JVM.
 */
class GuidanceStateMachine(
    private val config: GuidanceStateMachineConfig = GuidanceStateMachineConfig(),
) {
    fun reduce(state: CameraUiState, event: CameraEvent): CameraUiState = when (event) {
        CameraEvent.AnalysisStarted -> state.copy(
            guidance = GuidanceState(),
            analysisStatus = AnalysisStatus.ANALYZING,
            errorMessage = null,
        )

        is CameraEvent.FrameEvaluated -> state.copy(
            guidance = reduceFrame(state.guidance, event),
            analysisStatus = AnalysisStatus.TRACKING,
            errorMessage = null,
        )

        is CameraEvent.WrongDirectionDetected -> state.copy(
            guidance = GuidanceState(
                cue = event.cue,
                phase = GuidancePhase.WRONG_DIRECTION,
                progress = event.progress.normalizedProgress(),
            ),
            analysisStatus = AnalysisStatus.TRACKING,
            errorMessage = null,
        )

        CameraEvent.SubjectLost -> state.copy(
            guidance = GuidanceState(),
            analysisStatus = AnalysisStatus.ANALYZING,
            errorMessage = null,
        )

        is CameraEvent.AnalysisFailed -> state.copy(
            guidance = GuidanceState(),
            analysisStatus = AnalysisStatus.FAILED,
            errorMessage = event.message,
        )

        CameraEvent.CameraRecovered -> state.copy(
            guidance = GuidanceState(),
            analysisStatus = AnalysisStatus.ANALYZING,
            capturePhase = if (state.capturePhase == CapturePhase.FAILURE) {
                CapturePhase.IDLE
            } else {
                state.capturePhase
            },
            errorMessage = null,
        )

        CameraEvent.CaptureRequested -> {
            if (state.capturePhase == CapturePhase.CAPTURING) {
                state
            } else {
                state.copy(capturePhase = CapturePhase.CAPTURING, errorMessage = null)
            }
        }

        CameraEvent.CaptureSucceeded -> {
            if (state.capturePhase == CapturePhase.CAPTURING) {
                state.copy(capturePhase = CapturePhase.SUCCESS, errorMessage = null)
            } else {
                state
            }
        }

        is CameraEvent.CaptureFailed -> {
            if (state.capturePhase == CapturePhase.CAPTURING) {
                state.copy(capturePhase = CapturePhase.FAILURE, errorMessage = event.message)
            } else {
                state
            }
        }

        CameraEvent.CaptureFeedbackConsumed -> {
            if (state.capturePhase == CapturePhase.CAPTURING) {
                state
            } else {
                state.copy(capturePhase = CapturePhase.IDLE, errorMessage = null)
            }
        }
    }

    private fun reduceFrame(
        current: GuidanceState,
        event: CameraEvent.FrameEvaluated,
    ): GuidanceState {
        val progress = event.progress.normalizedProgress()
        val isStable = progress >= config.alignedThreshold

        if (isStable) {
            val stableFrames = (current.stableFrameCount + 1).coerceAtMost(config.framesToAlign)
            val phase = if (
                current.phase == GuidancePhase.ALIGNED || stableFrames >= config.framesToAlign
            ) {
                GuidancePhase.ALIGNED
            } else {
                GuidancePhase.NEAR
            }
            return GuidanceState(
                cue = when {
                    phase != GuidancePhase.ALIGNED -> event.cue
                    event.cue == GuidanceCue.SUBJECT_TARGET ||
                        event.cue == GuidanceCue.SUBJECT_ALIGNED -> GuidanceCue.SUBJECT_ALIGNED
                    else -> GuidanceCue.NONE
                },
                phase = phase,
                progress = progress,
                subjectOutline = event.subjectOutline,
                stableFrameCount = stableFrames,
            )
        }

        if (current.phase == GuidancePhase.ALIGNED) {
            val unstableFrames = current.unstableFrameCount + 1
            if (unstableFrames < config.framesToExitAligned) {
                return current.copy(unstableFrameCount = unstableFrames)
            }
        }

        return GuidanceState(
            cue = event.cue,
            phase = phaseFor(progress = progress, cue = event.cue),
            progress = progress,
            subjectOutline = event.subjectOutline,
            unstableFrameCount = if (current.phase == GuidancePhase.ALIGNED) {
                config.framesToExitAligned
            } else {
                0
            },
        )
    }

    private fun phaseFor(progress: Float, cue: GuidanceCue): GuidancePhase = when {
        progress >= config.nearThreshold -> GuidancePhase.NEAR
        cue == GuidanceCue.NONE -> GuidancePhase.NEUTRAL
        else -> GuidancePhase.ADJUST
    }

}

private fun Float.normalizedProgress(): Float =
    if (isFinite()) coerceIn(0f, 1f) else 0f
