package com.genwow.camera.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.genwow.camera.BuildConfig
import com.genwow.camera.camera.CameraController
import com.genwow.camera.camera.GuidanceSample
import com.genwow.camera.ui.theme.SystemBarsAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

@Composable
fun CameraRoute(
    onOpenFeed: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenEntryFlow: () -> Unit = {},
    /** Production AI output. When present it replaces the Debug demonstration stream. */
    guidanceSamples: Flow<GuidanceSample>? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { CameraController(context.applicationContext) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var bindKey by remember { mutableIntStateOf(0) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var recentPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMode by remember { mutableStateOf(CameraGuideMode.COFFEE) }
    SystemBarsAppearance(light = !permissionGranted || cameraError != null)
    var uiState by remember { mutableStateOf(CameraUiState()) }
    val stateMachine = remember { GuidanceStateMachine() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequested = true
        permissionGranted = granted
    }

    LaunchedEffect(permissionGranted, previewView, bindKey) {
        val view = previewView ?: return@LaunchedEffect
        if (!permissionGranted) return@LaunchedEffect
        cameraReady = false
        cameraError = null
        runCatching { controller.bind(lifecycleOwner, view) }
            .onSuccess { cameraReady = true }
            .onFailure { cameraError = it.message ?: "相机初始化失败" }
    }

    // The repository has no model or output protocol yet. Debug builds cycle through
    // clearly labelled demo guidance so every interaction state remains reviewable.
    LaunchedEffect(cameraReady, guidanceSamples, BuildConfig.DEBUG, selectedMode) {
        if (!cameraReady) return@LaunchedEffect
        if (guidanceSamples != null) {
            uiState = stateMachine.reduce(uiState, CameraEvent.AnalysisStarted)
            try {
                guidanceSamples.collect { sample ->
                    uiState = stateMachine.reduce(uiState, sample.toCameraEvent())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                uiState = stateMachine.reduce(
                    uiState,
                    CameraEvent.AnalysisFailed(error.message ?: "AI 分析失败"),
                )
            }
            return@LaunchedEffect
        }
        if (!BuildConfig.DEBUG) return@LaunchedEffect
        val cues = when (selectedMode) {
            CameraGuideMode.COFFEE -> listOf(
                GuidanceCue.MOVE_LEFT,
                GuidanceCue.MOVE_UP,
                GuidanceCue.MOVE_RIGHT,
            )
            CameraGuideMode.LANDSCAPE -> listOf(
                GuidanceCue.PITCH_UP,
                GuidanceCue.YAW_RIGHT,
                GuidanceCue.ROLL_CLOCKWISE,
            )
            CameraGuideMode.PORTRAIT -> emptyList()
        }
        while (true) {
            uiState = stateMachine.reduce(uiState, CameraEvent.AnalysisStarted)
            delay(900)
            if (selectedMode != CameraGuideMode.PORTRAIT) {
                cues.forEach { cue ->
                    uiState = stateMachine.reduce(uiState, CameraEvent.FrameEvaluated(cue, 0.28f))
                    delay(1_600)
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.FrameEvaluated(
                            cue = cue,
                            progress = 0.78f,
                            anchors = debugTranslationAnchors(cue = cue, progress = 0.78f),
                        ),
                    )
                    delay(1_400)
                }
                if (selectedMode == CameraGuideMode.LANDSCAPE) {
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.WrongDirectionDetected(GuidanceCue.YAW_LEFT, 0.34f),
                    )
                    delay(1_100)
                }
            }
            if (selectedMode == CameraGuideMode.PORTRAIT) {
                repeat(5) { frame ->
                    val progress = 0.28f + frame * 0.12f
                    val shift = -0.12f + frame * 0.03f
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.FrameEvaluated(
                            cue = GuidanceCue.SUBJECT_TARGET,
                            progress = progress,
                            anchors = debugSubjectAnchors(
                                horizontalShift = shift,
                                stance = 0.2f,
                            ),
                        ),
                    )
                    delay(340)
                }
                repeat(5) { frame ->
                    val progress = 0.42f + frame * 0.11f
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.FrameEvaluated(
                            cue = GuidanceCue.SUBJECT_TARGET,
                            progress = progress,
                            subjectOutline = debugGeneratedSubjectOutline(
                                horizontalShift = 0f,
                                stance = frame / 4f,
                            ),
                            anchors = debugSubjectAnchors(
                                horizontalShift = 0f,
                                stance = frame / 4f,
                            ),
                        ),
                    )
                    delay(240)
                }
                repeat(4) {
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.FrameEvaluated(
                            cue = GuidanceCue.SUBJECT_TARGET,
                            progress = 0.98f,
                            subjectOutline = debugGeneratedSubjectOutline(
                                horizontalShift = 0f,
                                stance = 1f,
                            ),
                            anchors = debugSubjectAnchors(
                                horizontalShift = 0f,
                                stance = 1f,
                            ),
                        ),
                    )
                    delay(180)
                }
            } else {
                repeat(7) { frame ->
                    val progress = 0.4f + frame * 0.075f
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.FrameEvaluated(
                            cue = GuidanceCue.SUBJECT_TARGET,
                            progress = progress,
                            subjectOutline = debugGeneratedSubjectOutline(
                                horizontalShift = -0.08f + frame * 0.012f,
                                stance = frame / 6f,
                            ),
                            anchors = debugSubjectAnchors(
                                horizontalShift = -0.08f + frame * 0.012f,
                                stance = frame / 6f,
                            ),
                        ),
                    )
                    delay(220)
                }
                repeat(5) {
                    uiState = stateMachine.reduce(
                        uiState,
                        CameraEvent.FrameEvaluated(
                            cue = GuidanceCue.SUBJECT_TARGET,
                            progress = 0.98f,
                            subjectOutline = debugGeneratedSubjectOutline(
                                horizontalShift = 0f,
                                stance = 1f,
                            ),
                            anchors = debugSubjectAnchors(
                                horizontalShift = 0f,
                                stance = 1f,
                            ),
                        ),
                    )
                    delay(160)
                }
            }
            delay(1_700)
            uiState = stateMachine.reduce(uiState, CameraEvent.SubjectLost)
            delay(1_200)
        }
    }

    LaunchedEffect(uiState.capturePhase) {
        if (uiState.capturePhase == CapturePhase.SUCCESS ||
            uiState.capturePhase == CapturePhase.FAILURE
        ) {
            delay(650)
            uiState = stateMachine.reduce(uiState, CameraEvent.CaptureFeedbackConsumed)
        }
    }

    DisposableEffect(Unit) { onDispose(controller::release) }

    when {
        !permissionGranted -> PermissionStateScreen(
            permanentlyDenied = permissionRequested &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    context as Activity,
                    Manifest.permission.CAMERA,
                ),
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )

        cameraError != null -> ErrorStateScreen(
            message = cameraError.orEmpty(),
            onRetry = { bindKey++ },
        )

        else -> CameraScreen(
            state = uiState,
            cameraReady = cameraReady,
            debugDemo = BuildConfig.DEBUG,
            recentPhotoUri = recentPhotoUri,
            onPreviewReady = { previewView = it },
            selectedMode = selectedMode,
            onModeSelected = { selectedMode = it },
            onOpenEntryFlow = onOpenEntryFlow,
            onCapture = {
                uiState = stateMachine.reduce(uiState, CameraEvent.CaptureRequested)
                controller.capture(
                    onSuccess = { uri ->
                        recentPhotoUri = uri
                        uiState = stateMachine.reduce(uiState, CameraEvent.CaptureSucceeded)
                    },
                    onError = {
                        Log.e("GenWowCapture", "Image capture failed", it)
                        uiState = stateMachine.reduce(
                            uiState,
                            CameraEvent.CaptureFailed(it.message ?: "拍摄失败"),
                        )
                    },
                )
            },
            onSwitchLens = {
                if (controller.toggleLens()) bindKey++
            },
            onOpenFeed = onOpenFeed,
            onOpenProfile = onOpenProfile,
        )
    }
}

internal fun GuidanceSample.toCameraEvent(): CameraEvent = when {
    wrongDirection -> CameraEvent.WrongDirectionDetected(cue, progress)
    !hasSubject && cue == GuidanceCue.SUBJECT_TARGET -> CameraEvent.SubjectLost
    else -> CameraEvent.FrameEvaluated(cue, progress, subjectOutline, anchors)
}

private fun debugTranslationAnchors(
    cue: GuidanceCue,
    progress: Float,
): GuidanceAnchors? {
    if (cue !in setOf(
            GuidanceCue.MOVE_LEFT,
            GuidanceCue.MOVE_RIGHT,
            GuidanceCue.MOVE_UP,
            GuidanceCue.MOVE_DOWN,
        )
    ) {
        return null
    }
    val distance = (1f - progress.coerceIn(0f, 1f)) * 0.2f + 0.02f
    val target = NormalizedPoint(x = 0.5f, y = 0.44f)
    val current = when (cue) {
        GuidanceCue.MOVE_LEFT -> target.copy(x = target.x + distance)
        GuidanceCue.MOVE_RIGHT -> target.copy(x = target.x - distance)
        GuidanceCue.MOVE_UP -> target.copy(y = target.y + distance)
        GuidanceCue.MOVE_DOWN -> target.copy(y = target.y - distance)
        else -> target
    }
    return GuidanceAnchors(current = current, target = target)
}

private fun debugSubjectAnchors(
    horizontalShift: Float,
    stance: Float,
): GuidanceAnchors {
    val currentCenter = NormalizedPoint(
        x = (0.5f + horizontalShift).coerceIn(0.1f, 0.9f),
        y = (0.59f - (stance * 0.015f)).coerceIn(0.1f, 0.9f),
    )
    return GuidanceAnchors(
        current = currentCenter,
        target = NormalizedPoint(0.5f, 0.57f),
    )
}

/**
 * Local Debug-only sample generator used because this repository has no model implementation.
 * Production contours arrive through [guidanceSamples] and never use these generated points.
 */
private fun debugGeneratedSubjectOutline(
    horizontalShift: Float,
    stance: Float,
): SubjectOutline {
    val shift = horizontalShift.coerceIn(-0.16f, 0.16f)
    val settled = stance.coerceIn(0f, 1f)
    val centerX = 0.5f + shift
    val head = (0 until 16).map { index ->
        val angle = index * (Math.PI * 2.0 / 16.0)
        NormalizedPoint(
            x = centerX + kotlin.math.cos(angle).toFloat() * 0.055f,
            y = 0.31f + kotlin.math.sin(angle).toFloat() * 0.075f,
        )
    }
    val bodyLean = (1f - settled) * 0.03f
    val shoulderDrop = (1f - settled) * 0.025f
    val torso = listOf(
        NormalizedPoint(centerX - 0.055f, 0.40f),
        NormalizedPoint(centerX - 0.07f + bodyLean, 0.52f),
        NormalizedPoint(centerX - 0.045f, 0.66f),
        NormalizedPoint(centerX + 0.045f, 0.66f),
        NormalizedPoint(centerX + 0.07f - bodyLean, 0.52f),
        NormalizedPoint(centerX + 0.055f, 0.40f),
    )
    val leftArm = listOf(
        NormalizedPoint(centerX - 0.055f, 0.42f + shoulderDrop),
        NormalizedPoint(centerX - 0.13f - bodyLean, 0.50f),
        NormalizedPoint(centerX - 0.15f - bodyLean, 0.64f),
    )
    val rightArm = listOf(
        NormalizedPoint(centerX + 0.055f, 0.42f),
        NormalizedPoint(centerX + 0.13f - bodyLean, 0.49f),
        NormalizedPoint(centerX + 0.16f - bodyLean, 0.63f),
    )
    val leftLeg = listOf(
        NormalizedPoint(centerX - 0.02f, 0.66f),
        NormalizedPoint(centerX - 0.055f, 0.80f),
        NormalizedPoint(centerX - 0.07f, 0.92f),
    )
    val rightLeg = listOf(
        NormalizedPoint(centerX + 0.02f, 0.66f),
        NormalizedPoint(centerX + 0.06f, 0.79f),
        NormalizedPoint(centerX + 0.075f, 0.92f),
    )
    val hips = listOf(
        NormalizedPoint(centerX - 0.038f, 0.67f),
        NormalizedPoint(centerX + 0.038f, 0.67f),
    )
    return SubjectOutline(
        contours = listOf(
            SubjectContour(points = head, closed = true),
            SubjectContour(points = torso, closed = true),
            SubjectContour(points = leftArm),
            SubjectContour(points = rightArm),
            SubjectContour(points = hips),
            SubjectContour(points = leftLeg),
            SubjectContour(points = rightLeg),
        ),
        confidence = 0.96f,
    )
}
