package com.genwow.camera.ui.camera

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.genwow.camera.camera.CameraPreview
import com.genwow.camera.ui.theme.GenWowTheme

@Composable
fun CameraScreen(
    state: CameraUiState,
    cameraReady: Boolean,
    debugDemo: Boolean,
    selectedMode: CameraGuideMode,
    recentPhotoUri: Uri? = null,
    onPreviewReady: (PreviewView) -> Unit,
    onModeSelected: (CameraGuideMode) -> Unit,
    onOpenEntryFlow: () -> Unit,
    onCapture: () -> Unit,
    onSwitchLens: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            CameraTopControls(
                selectedMode = selectedMode,
                debugDemo = debugDemo,
                onModeSelected = onModeSelected,
                onOpenEntryFlow = onOpenEntryFlow,
            )
            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(5.dp))) {
                CameraPreview(modifier = Modifier.fillMaxSize(), onPreviewReady = onPreviewReady)
                CompositionGuideOverlay(state = state, modifier = Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) { GuidanceMessage(state) }
            }
            CameraBottomControls(
                state = state,
                cameraReady = cameraReady,
                recentPhotoUri = recentPhotoUri,
                onCapture = onCapture,
                onSwitchLens = onSwitchLens,
                onOpenFeed = onOpenFeed,
                onOpenProfile = onOpenProfile,
            )
        }
        AnimatedVisibility(
            visible = state.capturePhase == CapturePhase.SUCCESS,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CaptureFeedbackOverlay(Modifier.fillMaxSize())
        }
    }
}

@Preview(name = "Camera aligned", widthDp = 393, heightDp = 852)
@Composable
private fun CameraAlignedPreview() {
    GenWowTheme {
        CameraScreen(
            state = CameraUiState(
                guidance = GuidanceState(
                    cue = GuidanceCue.NONE,
                    phase = GuidancePhase.ALIGNED,
                    progress = 1f,
                ),
                analysisStatus = AnalysisStatus.TRACKING,
            ),
            cameraReady = true,
            debugDemo = true,
            selectedMode = CameraGuideMode.COFFEE,
            onPreviewReady = {},
            onModeSelected = {},
            onOpenEntryFlow = {},
            onCapture = {},
            onSwitchLens = {},
            onOpenFeed = {},
            onOpenProfile = {},
        )
    }
}

@Preview(name = "Small screen rotation", widthDp = 360, heightDp = 640)
@Composable
private fun CameraRotationPreview() {
    GenWowTheme {
        CameraScreen(
            state = CameraUiState(
                guidance = GuidanceState(
                    cue = GuidanceCue.YAW_RIGHT,
                    phase = GuidancePhase.ADJUST,
                    progress = .32f,
                ),
                analysisStatus = AnalysisStatus.TRACKING,
            ),
            cameraReady = true,
            debugDemo = true,
            selectedMode = CameraGuideMode.LANDSCAPE,
            onPreviewReady = {},
            onModeSelected = {},
            onOpenEntryFlow = {},
            onCapture = {},
            onSwitchLens = {},
            onOpenFeed = {},
            onOpenProfile = {},
        )
    }
}
