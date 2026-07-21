package com.genwow.camera.ui.camera

import com.genwow.camera.camera.GuidanceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GuidanceStateMachineTest {
    private val machine = GuidanceStateMachine(
        GuidanceStateMachineConfig(
            nearThreshold = 0.7f,
            alignedThreshold = 0.9f,
            framesToAlign = 3,
            framesToExitAligned = 2,
        ),
    )

    @Test
    fun `all directional cues enter adjust phase`() {
        val cues = listOf(
            GuidanceCue.MOVE_LEFT,
            GuidanceCue.MOVE_RIGHT,
            GuidanceCue.MOVE_UP,
            GuidanceCue.MOVE_DOWN,
            GuidanceCue.PITCH_UP,
            GuidanceCue.PITCH_DOWN,
            GuidanceCue.YAW_LEFT,
            GuidanceCue.YAW_RIGHT,
            GuidanceCue.ROLL_CLOCKWISE,
            GuidanceCue.ROLL_COUNTERCLOCKWISE,
        )

        cues.forEach { cue ->
            val result = machine.reduce(
                CameraUiState(),
                CameraEvent.FrameEvaluated(cue = cue, progress = 0.35f),
            )

            assertEquals(cue, result.guidance.cue)
            assertEquals(GuidancePhase.ADJUST, result.guidance.phase)
            assertEquals(AnalysisStatus.TRACKING, result.analysisStatus)
        }
    }

    @Test
    fun `wrong direction uses a distinct phase without changing requested cue`() {
        val result = machine.reduce(
            CameraUiState(),
            CameraEvent.WrongDirectionDetected(GuidanceCue.YAW_LEFT, 0.36f),
        )

        assertEquals(GuidanceCue.YAW_LEFT, result.guidance.cue)
        assertEquals(GuidancePhase.WRONG_DIRECTION, result.guidance.phase)
        assertEquals(0.36f, result.guidance.progress, 0f)
        assertEquals(AnalysisStatus.TRACKING, result.analysisStatus)
    }

    @Test
    fun `normal frame exits wrong direction feedback immediately`() {
        val wrong = machine.reduce(
            CameraUiState(),
            CameraEvent.WrongDirectionDetected(GuidanceCue.ROLL_CLOCKWISE, 0.3f),
        )
        val recovered = machine.reduce(
            wrong,
            CameraEvent.FrameEvaluated(GuidanceCue.ROLL_CLOCKWISE, 0.45f),
        )

        assertEquals(GuidancePhase.ADJUST, recovered.guidance.phase)
        assertEquals(GuidanceCue.ROLL_CLOCKWISE, recovered.guidance.cue)
    }

    @Test
    fun `subject target becomes subject aligned after stable frames`() {
        var state = CameraUiState()
        val outline = SubjectOutline(
            contours = listOf(
                SubjectContour(
                    points = listOf(
                        NormalizedPoint(0.35f, 0.2f),
                        NormalizedPoint(0.48f, 0.8f),
                        NormalizedPoint(0.62f, 0.25f),
                    ),
                ),
            ),
        )

        repeat(3) {
            state = machine.reduce(
                state,
                CameraEvent.FrameEvaluated(
                    cue = GuidanceCue.SUBJECT_TARGET,
                    progress = 0.96f,
                    subjectOutline = outline,
                ),
            )
        }

        assertEquals(GuidancePhase.ALIGNED, state.guidance.phase)
        assertEquals(GuidanceCue.SUBJECT_ALIGNED, state.guidance.cue)
        assertEquals(outline, state.guidance.subjectOutline)
    }

    @Test
    fun `each analyzed frame replaces the previous subject outline`() {
        val first = SubjectOutline(
            listOf(SubjectContour(listOf(NormalizedPoint(0.2f, 0.2f), NormalizedPoint(0.3f, 0.8f)))),
        )
        val second = SubjectOutline(
            listOf(SubjectContour(listOf(NormalizedPoint(0.55f, 0.15f), NormalizedPoint(0.7f, 0.85f)))),
        )
        var state = machine.reduce(
            CameraUiState(),
            CameraEvent.FrameEvaluated(GuidanceCue.SUBJECT_TARGET, 0.4f, first),
        )
        state = machine.reduce(
            state,
            CameraEvent.FrameEvaluated(GuidanceCue.SUBJECT_TARGET, 0.5f, second),
        )

        assertEquals(second, state.guidance.subjectOutline)
    }

    @Test
    fun `ai sample maps its live outline into a frame event`() {
        val outline = SubjectOutline(
            listOf(SubjectContour(listOf(NormalizedPoint(0.25f, 0.2f), NormalizedPoint(0.7f, 0.8f)))),
        )
        val anchors = GuidanceAnchors(
            current = NormalizedPoint(0.4f, 0.45f),
            target = NormalizedPoint(0.5f, 0.44f),
        )

        val event = GuidanceSample(
            hasSubject = true,
            cue = GuidanceCue.SUBJECT_TARGET,
            progress = 0.64f,
            subjectOutline = outline,
            anchors = anchors,
        ).toCameraEvent() as CameraEvent.FrameEvaluated

        assertEquals(outline, event.subjectOutline)
        assertEquals(GuidanceCue.SUBJECT_TARGET, event.cue)
        assertEquals(anchors, event.anchors)
    }

    @Test
    fun `ai wrong direction sample maps to warning event`() {
        val event = GuidanceSample(
            hasSubject = true,
            cue = GuidanceCue.PITCH_DOWN,
            progress = 0.38f,
            wrongDirection = true,
        ).toCameraEvent()

        assertEquals(
            CameraEvent.WrongDirectionDetected(GuidanceCue.PITCH_DOWN, 0.38f),
            event,
        )
    }

    @Test
    fun `no cue with low confidence remains neutral`() {
        val result = machine.reduce(
            CameraUiState(),
            CameraEvent.FrameEvaluated(GuidanceCue.NONE, 0.4f),
        )

        assertEquals(GuidancePhase.NEUTRAL, result.guidance.phase)
    }

    @Test
    fun `progress selects adjust and near phases`() {
        val adjust = machine.reduce(
            CameraUiState(),
            CameraEvent.FrameEvaluated(GuidanceCue.MOVE_LEFT, 0.69f),
        )
        val near = machine.reduce(
            adjust,
            CameraEvent.FrameEvaluated(GuidanceCue.MOVE_LEFT, 0.7f),
        )

        assertEquals(GuidancePhase.ADJUST, adjust.guidance.phase)
        assertEquals(GuidancePhase.NEAR, near.guidance.phase)
    }

    @Test
    fun `progress is clamped and non finite values become zero`() {
        val above = evaluateProgress(1.4f)
        val below = evaluateProgress(-0.2f)
        val nan = evaluateProgress(Float.NaN)
        val positiveInfinity = evaluateProgress(Float.POSITIVE_INFINITY)

        assertEquals(1f, above, 0f)
        assertEquals(0f, below, 0f)
        assertEquals(0f, nan, 0f)
        assertEquals(0f, positiveInfinity, 0f)
    }

    @Test
    fun `alignment requires consecutive stable frames`() {
        var state = CameraUiState()

        repeat(2) {
            state = machine.reduce(
                state,
                CameraEvent.FrameEvaluated(GuidanceCue.ROLL_CLOCKWISE, 0.95f),
            )
            assertEquals(GuidancePhase.NEAR, state.guidance.phase)
        }

        state = machine.reduce(
            state,
            CameraEvent.FrameEvaluated(GuidanceCue.ROLL_CLOCKWISE, 0.95f),
        )
        assertEquals(GuidancePhase.ALIGNED, state.guidance.phase)
        assertEquals(GuidanceCue.NONE, state.guidance.cue)
        assertEquals(3, state.guidance.stableFrameCount)
    }

    @Test
    fun `unstable frame resets alignment candidate count`() {
        var state = CameraUiState()
        state = machine.reduce(state, CameraEvent.FrameEvaluated(GuidanceCue.YAW_LEFT, 0.95f))
        state = machine.reduce(state, CameraEvent.FrameEvaluated(GuidanceCue.YAW_LEFT, 0.95f))
        state = machine.reduce(state, CameraEvent.FrameEvaluated(GuidanceCue.YAW_LEFT, 0.4f))
        state = machine.reduce(state, CameraEvent.FrameEvaluated(GuidanceCue.YAW_LEFT, 0.95f))

        assertEquals(GuidancePhase.NEAR, state.guidance.phase)
        assertEquals(1, state.guidance.stableFrameCount)
    }

    @Test
    fun `aligned phase tolerates one bad frame and exits on second`() {
        var state = alignedState()

        state = machine.reduce(
            state,
            CameraEvent.FrameEvaluated(GuidanceCue.PITCH_DOWN, 0.5f),
        )
        assertEquals(GuidancePhase.ALIGNED, state.guidance.phase)
        assertEquals(1, state.guidance.unstableFrameCount)

        state = machine.reduce(
            state,
            CameraEvent.FrameEvaluated(GuidanceCue.PITCH_DOWN, 0.5f),
        )
        assertEquals(GuidancePhase.ADJUST, state.guidance.phase)
        assertEquals(GuidanceCue.PITCH_DOWN, state.guidance.cue)
    }

    @Test
    fun `subject loss clears hysteresis and returns to neutral`() {
        val result = machine.reduce(alignedState(), CameraEvent.SubjectLost)

        assertEquals(GuidanceState(), result.guidance)
        assertEquals(AnalysisStatus.ANALYZING, result.analysisStatus)
    }

    @Test
    fun `capture request is ignored while capture is in progress`() {
        val capturing = machine.reduce(CameraUiState(), CameraEvent.CaptureRequested)
        val duplicate = machine.reduce(capturing, CameraEvent.CaptureRequested)

        assertEquals(CapturePhase.CAPTURING, capturing.capturePhase)
        assertSame(capturing, duplicate)
    }

    @Test
    fun `stale capture callbacks do not overwrite state`() {
        val idle = CameraUiState()

        assertSame(idle, machine.reduce(idle, CameraEvent.CaptureSucceeded))
        assertSame(idle, machine.reduce(idle, CameraEvent.CaptureFailed("late callback")))
    }

    @Test
    fun `capture can restart after feedback is consumed`() {
        var state = machine.reduce(CameraUiState(), CameraEvent.CaptureRequested)
        state = machine.reduce(state, CameraEvent.CaptureSucceeded)
        assertEquals(CapturePhase.SUCCESS, state.capturePhase)

        state = machine.reduce(state, CameraEvent.CaptureFeedbackConsumed)
        state = machine.reduce(state, CameraEvent.CaptureRequested)

        assertEquals(CapturePhase.CAPTURING, state.capturePhase)
    }

    private fun evaluateProgress(progress: Float): Float = machine.reduce(
        CameraUiState(),
        CameraEvent.FrameEvaluated(GuidanceCue.MOVE_RIGHT, progress),
    ).guidance.progress

    private fun alignedState(): CameraUiState {
        var state = CameraUiState()
        repeat(3) {
            state = machine.reduce(
                state,
                CameraEvent.FrameEvaluated(GuidanceCue.MOVE_UP, 0.96f),
            )
        }
        return state
    }
}
