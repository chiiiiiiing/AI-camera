package com.genwow.camera.ui.camera

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Rule
import org.junit.Test

class CameraScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun alignedStateShowsStableMessageAndEnabledCapture() {
        composeRule.setContent {
            GenWowTheme {
                CameraScreen(
                    state = CameraUiState(
                        guidance = GuidanceState(
                            phase = GuidancePhase.ALIGNED,
                            progress = 1f,
                        ),
                        analysisStatus = AnalysisStatus.TRACKING,
                    ),
                    cameraReady = true,
                    debugDemo = false,
                    selectedMode = CameraGuideMode.COFFEE,
                    onPreviewReady = {},
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                    onCapture = {},
                    onSwitchLens = {},
                    onZoomRatioSelected = {},
                    onOpenFeed = {},
                    onOpenProfile = {},
                )
            }
        }

        composeRule.onNodeWithText("保持，构图已对齐").assertExists()
        composeRule.onNodeWithContentDescription("拍摄照片").assertIsEnabled()
    }

    @Test
    fun captureIsDisabledUntilCameraIsReady() {
        composeRule.setContent {
            GenWowTheme {
                CameraScreen(
                    state = CameraUiState(),
                    cameraReady = false,
                    debugDemo = false,
                    selectedMode = CameraGuideMode.COFFEE,
                    onPreviewReady = {},
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                    onCapture = {},
                    onSwitchLens = {},
                    onZoomRatioSelected = {},
                    onOpenFeed = {},
                    onOpenProfile = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("拍摄照片").assertIsNotEnabled()
    }

    @Test
    fun modeSelectorChangesSelection() {
        composeRule.setContent { GenWowTheme { ModeSelector() } }

        composeRule.onNodeWithText("人像").performClick().assertIsSelected()
    }

    @Test
    fun figmaCameraShellExposesBottomControlsAndMainDestinations() {
        composeRule.setContent {
            GenWowTheme {
                CameraScreen(
                    state = CameraUiState(),
                    cameraReady = true,
                    debugDemo = false,
                    selectedMode = CameraGuideMode.COFFEE,
                    onPreviewReady = {},
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                    onCapture = {},
                    onSwitchLens = {},
                    onZoomRatioSelected = {},
                    onOpenFeed = {},
                    onOpenProfile = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("最近照片").assertExists()
        composeRule.onNodeWithContentDescription("切换前后摄像头").assertExists()
        composeRule.onNodeWithContentDescription("打开相机").assertExists()
        composeRule.onNodeWithContentDescription("打开推荐").assertExists()
        composeRule.onNodeWithContentDescription("打开个人主页").assertExists()
    }

    @Test
    fun wrongDirectionShowsNonColorWarningMessage() {
        composeRule.setContent {
            GenWowTheme {
                CameraScreen(
                    state = CameraUiState(
                        guidance = GuidanceState(
                            cue = GuidanceCue.YAW_LEFT,
                            phase = GuidancePhase.WRONG_DIRECTION,
                            progress = 0.32f,
                        ),
                        analysisStatus = AnalysisStatus.TRACKING,
                    ),
                    cameraReady = true,
                    debugDemo = false,
                    selectedMode = CameraGuideMode.LANDSCAPE,
                    onPreviewReady = {},
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                    onCapture = {},
                    onSwitchLens = {},
                    onZoomRatioSelected = {},
                    onOpenFeed = {},
                    onOpenProfile = {},
                )
            }
        }

        composeRule.onNodeWithText("方向相反，稍微回一点").assertExists()
    }

    @Test
    fun subjectTargetExposesDynamicOutlineAndMessage() {
        // The target state intentionally owns the single repeating guidance clock. Freezing the
        // test clock lets this test inspect the static accessibility state without waiting for an
        // animation that is designed to continue until the AI sends a new state.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            GenWowTheme {
                CameraScreen(
                    state = CameraUiState(
                        guidance = GuidanceState(
                            cue = GuidanceCue.SUBJECT_TARGET,
                            phase = GuidancePhase.ADJUST,
                            progress = 0.4f,
                            subjectOutline = SubjectOutline(
                                contours = listOf(
                                    SubjectContour(
                                        points = listOf(
                                            NormalizedPoint(0.35f, 0.25f),
                                            NormalizedPoint(0.5f, 0.8f),
                                            NormalizedPoint(0.65f, 0.25f),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        analysisStatus = AnalysisStatus.TRACKING,
                    ),
                    cameraReady = true,
                    debugDemo = false,
                    selectedMode = CameraGuideMode.PORTRAIT,
                    onPreviewReady = {},
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                    onCapture = {},
                    onSwitchLens = {},
                    onZoomRatioSelected = {},
                    onOpenFeed = {},
                    onOpenProfile = {},
                )
            }
        }

        composeRule.onNodeWithText("将人物移入轮廓").assertExists()
        composeRule.onNodeWithContentDescription("AI人物轮廓：目标").assertExists()
    }

    @Test
    fun subjectAlignedExposesStableOutlineAndMessage() {
        composeRule.setContent {
            GenWowTheme {
                CameraScreen(
                    state = CameraUiState(
                        guidance = GuidanceState(
                            cue = GuidanceCue.SUBJECT_ALIGNED,
                            phase = GuidancePhase.ALIGNED,
                            progress = 0.98f,
                            subjectOutline = SubjectOutline(
                                contours = listOf(
                                    SubjectContour(
                                        points = listOf(
                                            NormalizedPoint(0.4f, 0.2f),
                                            NormalizedPoint(0.5f, 0.82f),
                                            NormalizedPoint(0.6f, 0.2f),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        analysisStatus = AnalysisStatus.TRACKING,
                    ),
                    cameraReady = true,
                    debugDemo = false,
                    selectedMode = CameraGuideMode.PORTRAIT,
                    onPreviewReady = {},
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                    onCapture = {},
                    onSwitchLens = {},
                    onZoomRatioSelected = {},
                    onOpenFeed = {},
                    onOpenProfile = {},
                )
            }
        }

        composeRule.onNodeWithText("保持，人物构图已对齐").assertExists()
        composeRule.onNodeWithContentDescription("AI人物轮廓：已对齐").assertExists()
    }

    @Test
    fun topModeControlsReflectSelectionAndAreClickable() {
        composeRule.setContent {
            GenWowTheme {
                CameraTopControls(
                    selectedMode = CameraGuideMode.LANDSCAPE,
                    debugDemo = false,
                    onModeSelected = {},
                    onOpenEntryFlow = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("风景构图模式").assertIsSelected()
        composeRule.onNodeWithContentDescription("静物构图模式").performClick()
    }
}
