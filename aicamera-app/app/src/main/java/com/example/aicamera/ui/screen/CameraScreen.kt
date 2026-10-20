package com.example.aicamera.ui.screen

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aicamera.R
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.camera.CameraGestureDispatcher
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.filter.PresetFilter
import com.example.aicamera.filter.CustomFilterConfig
import com.example.aicamera.guidance.AlignmentFlashOverlay
import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.guidance.GuidancePhase
import com.example.aicamera.ui.components.CaptureWorkflowOverlay
import com.example.aicamera.ui.components.CaptureWorkflowState
import com.example.aicamera.ui.components.CaptureResultSheet
import com.example.aicamera.ui.components.FocusExposureOverlay
import com.example.aicamera.ui.components.CompactProPanel
import com.example.aicamera.ui.components.compositionLabelText
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPurple
import com.example.aicamera.ui.theme.CameraChrome
import com.example.aicamera.ui.theme.CameraControlMuted
import com.example.aicamera.ui.theme.CameraOnChrome
import com.example.aicamera.ui.theme.GenWowMotion
import com.example.aicamera.ui.theme.GuideAligned
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.CommunityViewModel
import com.example.aicamera.viewmodel.PipelineViewModel
import com.example.aicamera.viewmodel.RenderViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 相机拍摄页 — 顶部功能栏 + 中间预览画布 + 底部工具栏
 *
 * ViewModel 由外部传入（在 Activity 层创建，跨 Tab 保持）。
 *
 * ## 布局（层叠顺序）
 * ```
 * Box
 *   ├── PreviewView              (相机实时画面)
 *   ├── CaptureWorkflowOverlay  (Compose 三分线、主体框、目标区、水平与姿态引导)
 *   ├── FocusExposureOverlay    (对焦框与曝光控制)
 *   ├── CompactProPanel         (可选，Pro 手动控制)
 *   ├── TopBar / BottomBar / 步骤指示 / 引导文案 / 倒计时
 * ```
 *
 * 预览使用真机画面，工作流叠加层根据真实观测绘制；静态验收站只复现其 UI 状态。
 */

/**
 * ANALYZING 状态的让位延迟。
 *
 * 旧值固定 800ms 空等，是拍摄提速的主要瓶颈之一。
 * 压到 150ms —— 仍留一帧的余量让状态文案可感知，但不再成为阻碍。
 */
private const val ANALYZE_SETTLE_MS = 100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    cameraViewModel: CameraViewModel,
    pipelineViewModel: PipelineViewModel,
    renderViewModel: RenderViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToCommunity: () -> Unit = {},
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
    val selectedFilter by cameraViewModel.selectedFilter.collectAsStateWithLifecycle()
    val selectedCustom by cameraViewModel.selectedCustomFilter.collectAsStateWithLifecycle()
    val customFilters by cameraViewModel.customFilters.collectAsStateWithLifecycle()
    val customActive by cameraViewModel.customFilterActive.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val guidanceState by renderViewModel.guidanceState.collectAsStateWithLifecycle()
    val guidanceStep by renderViewModel.guidanceStep.collectAsStateWithLifecycle()
    val stepHint by renderViewModel.stepHint.collectAsStateWithLifecycle()
    val renderInput by renderViewModel.renderInput.collectAsStateWithLifecycle()
    val pipelineState by pipelineViewModel.pipelineState.collectAsStateWithLifecycle()
    // ★ 统一构图会话控制器状态（替代旧双工作流）
    val sessionState by renderViewModel.sessionController.state.collectAsStateWithLifecycle()
    val sessionDecision by renderViewModel.sessionController.decision.collectAsStateWithLifecycle()
    // 一旦进入自动拍摄流程就锁存，后续帧的抖动不能把界面打回引导。
    var captureCommitted by remember { mutableStateOf(false) }
    // Every visible composition instruction and capture verdict comes from the session.
    // Capturing / Saved 是终态，任何引导状态都不能覆盖正在拍摄的页面。
    val activeCue = guidanceState.cue
    val workflowState = when {
        captureCommitted ||
            sessionState is CompositionSessionController.State.Capturing -> CaptureWorkflowState.CAPTURING
        sessionState is CompositionSessionController.State.Saved -> CaptureWorkflowState.SAVED
        sessionState is CompositionSessionController.State.Idle -> CaptureWorkflowState.IDLE
        sessionState is CompositionSessionController.State.Analyzing -> CaptureWorkflowState.ANALYZING
        guidanceStep == com.example.aicamera.guidance.GuidanceStepSequencer.GuidanceStep.ZOOM ->
            CaptureWorkflowState.ANALYZING
        else -> when (activeCue) {
            GuidanceCue.DEVICE_UPSIDE_DOWN -> CaptureWorkflowState.UPSIDE_DOWN
            GuidanceCue.PITCH_UP, GuidanceCue.PITCH_DOWN,
            GuidanceCue.YAW_LEFT, GuidanceCue.YAW_RIGHT,
            GuidanceCue.ROLL_CLOCKWISE, GuidanceCue.ROLL_COUNTERCLOCKWISE ->
                CaptureWorkflowState.ROTATING
            GuidanceCue.MOVE_LEFT, GuidanceCue.MOVE_RIGHT,
            GuidanceCue.MOVE_UP, GuidanceCue.MOVE_DOWN,
            GuidanceCue.MOVE_UP_LEFT, GuidanceCue.MOVE_UP_RIGHT,
            GuidanceCue.MOVE_DOWN_LEFT, GuidanceCue.MOVE_DOWN_RIGHT,
            GuidanceCue.SUBJECT_TARGET -> CaptureWorkflowState.GUIDING
            else -> if (sessionState is CompositionSessionController.State.Ready)
                CaptureWorkflowState.READY else CaptureWorkflowState.GUIDING
        }
    }
    val workflowCue = when {
        sessionState is CompositionSessionController.State.Capturing ||
            sessionState is CompositionSessionController.State.Saved ||
            sessionState is CompositionSessionController.State.Analyzing ||
            guidanceStep == com.example.aicamera.guidance.GuidanceStepSequencer.GuidanceStep.ZOOM ->
            GuidanceCue.NONE
        sessionState is CompositionSessionController.State.Ready ->
            GuidanceCue.SUBJECT_ALIGNED
        else -> activeCue
    }
    val workflowPhase = when {
        sessionState is CompositionSessionController.State.Ready -> GuidancePhase.ALIGNED
        sessionState is CompositionSessionController.State.Verifying -> GuidancePhase.NEAR
        sessionState is CompositionSessionController.State.Analyzing -> GuidancePhase.NEUTRAL
        activeCue != GuidanceCue.NONE -> GuidancePhase.ADJUST
        else -> GuidancePhase.NEUTRAL
    }
    // 拍后结果确认页（不生成伪审美分数）
    var showCaptureResult by remember { mutableStateOf(false) }
    // 拍摄瞬间的真实 AI 参考说明，用于保存后的结果页。
    var capturedAiMethod by remember { mutableStateOf<String?>(null) }
    var workflowStartedAt by remember { mutableLongStateOf(0L) }
    var showTakeNow by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    // 拍摄进行中的同步防重入标志（isCapturing 是异步 StateFlow，Ready 自动拍摄也可能绕过）
    var captureInFlight by remember { mutableStateOf(false) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var showFilterEditor by remember { mutableStateOf(false) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val measuredBottomBarHeight = if (bottomBarHeightPx > 0) {
        with(density) { bottomBarHeightPx.toDp() }
    } else {
        196.dp
    }
    val floatingControlsBottom = measuredBottomBarHeight + 14.dp
    // 点击对焦位置与曝光条显隐
    var focusCenter by remember { mutableStateOf(Offset.Zero) }
    var showExposureSlider by remember { mutableStateOf(false) }
    var focusRequestId by remember { mutableLongStateOf(0L) }

    // 返回键关闭手写覆盖层（拍后结果页 / 拍摄失败弹层），而不是退出相机
    BackHandler(enabled = showCaptureResult || captureError != null) {
        if (showCaptureResult) {
            showCaptureResult = false
            renderViewModel.sessionController.resetToIdle()
        } else {
            captureError = null
        }
    }
    val aiState by pipelineViewModel.aiState.collectAsStateWithLifecycle()
    val v9DegradedReason by pipelineViewModel.v9DegradedReason.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val communityViewModel: CommunityViewModel = hiltViewModel()
    val authRepo = remember { AuthRepository.getInstance(context) }
    val activeUser by authRepo.activeUser.collectAsState()
    val currentUser = activeUser?.username?.takeIf { it.isNotBlank() } ?: authRepo.displayUsername

    LaunchedEffect(Unit) {
        cameraViewModel.refreshImportedFilter()
    }

    // 社区发布 Sheet
    var showPublishSheet by remember { mutableStateOf(false) }
    var publishDescription by remember { mutableStateOf("") }
    var isPublishing by remember { mutableStateOf(false) }

    // ── 权限 ──
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val needsStoragePermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
    var hasStoragePermission by remember {
        mutableStateOf(
            !needsStoragePermission ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val allPermissionsGranted = hasCameraPermission && hasStoragePermission

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasStoragePermission = results[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: hasStoragePermission
        if (!hasCameraPermission) {
            Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_LONG).show()
        }
    }

    // ── 相机初始化 ──
    var cameraInitialized by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    LaunchedEffect(configuration.orientation, uiState.cameraBindVersion) {
        delay(100)
        previewView?.display?.rotation?.let(cameraViewModel::updateTargetRotation)
    }
    var zoomInteractionTick by remember { mutableIntStateOf(0) }
    val gestureDispatcher = remember {
        CameraGestureDispatcher(
            onFocusCircle = { cx, cy ->
                cameraViewModel.onFocusTapped(cx, cy)
                if (renderViewModel.needsSubjectSelection()) previewView?.takeIf { it.width > 0 && it.height > 0 }?.let { view ->
                    cameraViewModel.cancelAutoZoom()
                    renderViewModel.cancelAutoZoom()
                    pipelineViewModel.selectRegion(cx / view.width, cy / view.height)
                }
            },
            onZoomChanged = { ratio ->
                // 用户捏合变焦 = 手动介入：同步 UI 倍率显示，并取消自动变焦
                // （序列器与斜坡同时让权）。此前只取消自动变焦、不回写倍率，
                // 导致捏合放大时底部倍率条不动。
                cameraViewModel.onPinchZoom(ratio)
                renderViewModel.cancelAutoZoom()
                zoomInteractionTick++
            },
            onFocusLocked = { locked -> cameraViewModel.setFocusLocked(locked) }
        )
    }

    // Subject selection is automatic, including the front camera.
    LaunchedEffect(Unit) { renderViewModel.setPortraitGuide(false) }

    // 定时拍照（关 / 3s / 10s）
    var timerMode by remember { mutableStateOf(TimerMode.OFF) }
    var countdown by remember { mutableStateOf(0) }  // >0 = 倒计时进行中

    // 快门声（按设置开关）
    val shutterSound = remember { MediaActionSound() }
    DisposableEffect(Unit) {
        onDispose { shutterSound.release() }
    }

    // ── 构图达标反馈：会话真正 Ready 的瞬间 → 震动 + 绿边闪烁 ──
    val haptic = LocalHapticFeedback.current
    var showAlignFlash by remember { mutableStateOf(false) }
    val sessionReady = sessionState is CompositionSessionController.State.Ready
    val captureReady = sessionReady
    LaunchedEffect(captureReady) {
        if (captureReady) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showAlignFlash = true
        }
    }

    // 订阅 focusCircle → Compose 对焦框及曝光条（旧 View 叠加层已卸载）
    LaunchedEffect(Unit) {
        cameraViewModel.focusCircle.collect { focusData ->
            focusRequestId++
            focusCenter = Offset(focusData.centerX, focusData.centerY)
            showExposureSlider = true
        }
    }

    // 长按时隐藏曝光条
    LaunchedEffect(uiState.isFocusLocked) {
        if (uiState.isFocusLocked) showExposureSlider = false
    }

    // 订阅拍照结果 → 快门声 + 提示
    LaunchedEffect(Unit) {
        cameraViewModel.captureResult.collect { result ->
            captureInFlight = false
            captureCommitted = false
            when (result) {
                is com.example.aicamera.camera.CameraCapture.CaptureResult.Success -> {
                    if (settings.soundEnabled) {
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    }
                    renderViewModel.sessionController.onCaptureComplete(result.uri.toString())
                    result.processingWarning?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                    delay(200)
                    showCaptureResult = true
                }
                is com.example.aicamera.camera.CameraCapture.CaptureResult.Error -> {
                    captureError = result.message
                    renderViewModel.sessionController.error(result.message)
                }
            }
        }
    }

    // 管线连接（★ connect() 自身有 collectJob 防重入，不额外检查 isRunning，
    // 否则 MainScreen 的 LaunchedEffect(selectedTab) 先调 resume() 启动管线后，
    // 此处 !isRunning 会永为 false，帧处理链路永远无法建立）
    LaunchedEffect(uiState.isInitialized) {
        if (uiState.isInitialized) {
            pipelineViewModel.connect(cameraViewModel.frameFlow)
            renderViewModel.connect(pipelineViewModel.pipelineResults, pipelineViewModel::requestReplan)
        }
    }

    // 刷新最新照片缩略图
    LaunchedEffect(uiState.isInitialized) {
        if (uiState.isInitialized) {
            cameraViewModel.refreshLatestPhoto()
        }
    }

    // ★ 手势重挂：Camera 实例每次绑定/重绑都会更换
    LaunchedEffect(uiState.cameraBindVersion) {
        val view = previewView ?: return@LaunchedEffect
        val camera = cameraViewModel.getCamera() ?: return@LaunchedEffect
        gestureDispatcher.detach(view)
        gestureDispatcher.attach(view, camera)
    }

    // ── ★ 分步引导：自动变焦接线 ──
    // 推送当前倍率/范围给序列器
    LaunchedEffect(uiState.zoomRatio) {
        renderViewModel.onZoomRatioChanged(uiState.zoomRatio)
    }
    LaunchedEffect(uiState.cameraBindVersion) {
        val (minZ, maxZ) = cameraViewModel.zoomRange()
        renderViewModel.onZoomRangeAvailable(minZ, maxZ)
    }
    // 序列器发出变焦目标 → 执行平滑变焦 → 到位回调
    LaunchedEffect(Unit) {
        renderViewModel.autoZoomTarget.collect { request ->
            if (!renderViewModel.isCurrentAutoZoom(request)) return@collect
            cameraViewModel.rampZoomTo(request.ratio)
            // 斜坡 700ms + 余量后认为到位（用户打断时 cancelAutoZoom 已另走取消路径）
            delay(900)
            renderViewModel.notifyAutoZoomApplied(request)
        }
    }
    // 切前后摄 → 重新开始分步引导
    LaunchedEffect(uiState.cameraFace) {
        renderViewModel.resetGuidanceSteps()
    }

    // 权限检查
    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            val permissionsToRequest = buildList {
                if (!hasCameraPermission) add(Manifest.permission.CAMERA)
                if (needsStoragePermission && !hasStoragePermission) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.toTypedArray()
            if (permissionsToRequest.isNotEmpty()) {
                permissionLauncher.launch(permissionsToRequest)
            }
        }
    }

    // ── ★ 统一构图-拍摄工作流（由 CompositionSessionController 驱动） ──

    fun captureNow() {
        if (captureInFlight ||
            uiState.isCapturing ||
            renderViewModel.sessionController.state.value is CompositionSessionController.State.Capturing
        ) return

        captureInFlight = true
        cameraViewModel.cancelAutoZoom()
        renderViewModel.cancelAutoZoom()
        showTakeNow = false
        showFilterPanel = false
        renderViewModel.sessionController.startCapture()
        val aiMethodAtCapture = renderInput.aiCompositionMethod
        scope.launch {
            cameraViewModel.prepareForCapture(timeoutMs = 900L)
            capturedAiMethod = aiMethodAtCapture
            cameraViewModel.takePhoto(saveToGallery = settings.saveOriginal)
        }
    }

    fun triggerCapture() {
        if (uiState.isCapturing) return

        val currentState = renderViewModel.sessionController.state.value

        // 引导期间再次按快门，按当前画面直接拍摄。
        if (currentState is CompositionSessionController.State.Analyzing ||
            currentState is CompositionSessionController.State.Adjusting ||
            currentState is CompositionSessionController.State.Verifying ||
            currentState is CompositionSessionController.State.Ready
        ) {
            captureNow()
            return
        }
        if (currentState is CompositionSessionController.State.Capturing) return
        if (currentState is CompositionSessionController.State.Saved ||
            currentState is CompositionSessionController.State.Error
        ) renderViewModel.sessionController.resetToIdle()

        workflowStartedAt = System.currentTimeMillis()
        cameraViewModel.cancelAutoZoom()
        captureCommitted = false
        showTakeNow = false
        showCaptureResult = false
        capturedAiMethod = null
        showFilterPanel = false
        if (settings.proModeEnabled) onSettingsChange(settings.copy(proModeEnabled = false))
        captureError = null

        if (timerMode != TimerMode.OFF && countdown == 0) {
            scope.launch {
                countdown = timerMode.seconds
                while (countdown > 0) { delay(1000); countdown-- }
                renderViewModel.resetGuidanceSteps()
                renderViewModel.sessionController.startAnalysis()
            }
        } else if (countdown > 0) {
            countdown = 0
        } else {
            renderViewModel.resetGuidanceSteps()
            renderViewModel.sessionController.startAnalysis()
        }
    }

    // Face and object coordinates use the same observation as the controller;
    // never fall back to a stale face target when the object detector loses data.
    fun liveTargetCenter(): Pair<Float, Float>? = guidanceState.anchors?.target?.let { it.x to it.y }
    fun liveSubjectCenter(): Pair<Float, Float>? = guidanceState.anchors?.current?.let { it.x to it.y }

    // ★ 统一会话控制器驱动：ANALYZING → 延迟后通知完成
    LaunchedEffect(sessionState) {
        when (sessionState) {
            is CompositionSessionController.State.Analyzing -> {
                // ★ 修复：不再强制重置用户 Zoom（尊重用户手动选择的焦段）
                // 分析阶段：快速让位给引导，不再固定空等 800ms
                delay(ANALYZE_SETTLE_MS)
                renderViewModel.sessionController.onAnalysisComplete()
            }
            is CompositionSessionController.State.Error -> {
                // 异常处理
                Toast.makeText(context,
                    (sessionState as CompositionSessionController.State.Error).reason,
                    Toast.LENGTH_SHORT).show()
                delay(1500)
                renderViewModel.sessionController.resetToIdle()
            }
            else -> { /* 其他状态由控制器内部处理 */ }
        }
    }

    // The session's Ready is the only composition gate.
    LaunchedEffect(captureReady) {
        if (captureReady) {
            captureCommitted = true
            showTakeNow = false
        }
    }
    // 锁存后不再跟随 captureReady 变化而取消，确保“正在拍摄”页面不回退。
    LaunchedEffect(captureCommitted) {
        if (!captureCommitted || captureInFlight || uiState.isCapturing) return@LaunchedEffect
        captureInFlight = true
        var started = false
        try {
            cameraViewModel.prepareForCapture(timeoutMs = 900L)
            // AF/AE is asynchronous: a detector miss during this wait must pause
            // auto capture, even though the old Ready event already started it.
            if (!renderViewModel.sessionController.confirmReadyForCapture(android.os.SystemClock.elapsedRealtime())) {
                return@LaunchedEffect
            }
            // 一旦进入自动拍摄流程就不再回退到确认/引导态；
            // AF/AE 完成直接拍摄，避免“正在确认构图”额外拖时间。
            capturedAiMethod = renderInput.aiCompositionMethod
            renderViewModel.sessionController.startCapture()
            cameraViewModel.takePhoto(saveToGallery = settings.saveOriginal)
            started = true
        } finally {
            if (!started) {
                captureInFlight = false
                captureCommitted = false
            }
        }
        // 结果确认页在真实保存成功事件后显示，避免 UI 提前宣称保存完成。
    }

    // 构图引导最多等待 6 秒，之后允许用户直接拍摄，不把“辅助”变成阻碍。
    LaunchedEffect(workflowStartedAt) {
        if (workflowStartedAt == 0L) return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - workflowStartedAt
        delay((6_000L - elapsed).coerceAtLeast(0L))
        val latest = renderViewModel.sessionController.state.value
        showTakeNow = latest is CompositionSessionController.State.Adjusting ||
            latest is CompositionSessionController.State.Verifying
        if (showTakeNow) {
            delay(4_000L)
            showTakeNow = false
        }
    }

    // 每帧进度变化不能重启倒计时；这里只负责在离开引导阶段时立即收起。
    LaunchedEffect(sessionState) {
        val waiting = sessionState is CompositionSessionController.State.Analyzing ||
            sessionState is CompositionSessionController.State.Adjusting ||
            sessionState is CompositionSessionController.State.Verifying
        pipelineViewModel.setContinuousTrackingEnabled(waiting || sessionState is CompositionSessionController.State.Ready)
        if (!waiting) showTakeNow = false
    }

    // 切换摄像头 → 取消当前工作流
    LaunchedEffect(uiState.cameraFace) {
        val currentState = renderViewModel.sessionController.state.value
        cameraViewModel.cancelAutoZoom()
        if (currentState !is CompositionSessionController.State.Idle) {
            renderViewModel.sessionController.cancel()
        }
        captureCommitted = false
        renderViewModel.resetGuidanceSteps()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraChrome)
    ) {
        // ── 第 1 层：相机预览 ──
        if (allPermissionsGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this

                        if (!cameraInitialized) {
                            cameraViewModel.initialize(this, lifecycleOwner) { success ->
                                cameraInitialized = success
                            }
                        }
                    }
                },
                update = { view ->
                    val matrix = if (customActive) selectedCustom?.colorMatrix()
                        else selectedFilter.colorMatrix
                    if ((!customActive && selectedFilter == PresetFilter.Original) || matrix == null) {
                        view.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            colorFilter = ColorMatrixColorFilter(matrix)
                        }
                        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                    }
                }
            )

            // ── 旧辅助线叠加层 — v2 工作流不再使用，永不渲染 ──

            // ── 第 2.6 层：达标绿闪（进入 ALIGNED 相位瞬间闪一次） ──
            if (showAlignFlash) {
                AlignmentFlashOverlay(
                    onFinished = { showAlignFlash = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // 无权限提示
            Box(
                modifier = Modifier.fillMaxSize().background(CameraChrome),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (!hasCameraPermission) "需要相机权限" else "需要存储权限",
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        val permissionsToRequest = buildList {
                            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
                            if (needsStoragePermission && !hasStoragePermission)
                                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }.toTypedArray()
                        if (permissionsToRequest.isNotEmpty()) {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    }) {
                        Text("授予权限")
                    }
                }
            }
        }

        // ── 第 3 层：顶部构图模式选择器（队友 GenWow 设计） ──
        if (uiState.isInitialized && !showCaptureResult) {
            CameraTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                aiStatus = aiState.status,
                inferenceType = pipelineState.inferenceType,
                degraded = v9DegradedReason != null,
                onSettingsClick = onNavigateToSettings,
            )
        }

        // ── AI 构图建议（状态胶囊已收进顶栏，避免多个浮层堆叠） ──
        if (uiState.isInitialized &&
            (sessionState is CompositionSessionController.State.Idle ||
                sessionState is CompositionSessionController.State.Saved) &&
            !showCaptureResult
        ) {
            if (aiState.status == com.example.aicamera.pipeline.AiEngineManager.Status.READY &&
                renderInput.sceneType != "unknown"
            ) {
                AiCompositionChip(
                    label = compositionLabelText(
                        renderInput.compositionStrategy,
                        renderInput.sceneType
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 88.dp, start = 14.dp)
                )
            }
        }

        // ── 滤镜小组件：默认收起，点击后才展开；分析/拍摄时让位给画面 ──
        if (uiState.isInitialized && !showCaptureResult &&
            workflowState != CaptureWorkflowState.ANALYZING &&
            workflowState != CaptureWorkflowState.CAPTURING
        ) {
            FilterDock(
                selected = selectedFilter,
                selectedCustom = selectedCustom,
                customFilters = customFilters,
                customActive = customActive,
                expanded = showFilterPanel,
                onToggle = {
                    val opening = !showFilterPanel
                    showFilterPanel = opening
                    if (opening && settings.proModeEnabled) {
                        onSettingsChange(settings.copy(proModeEnabled = false))
                    }
                },
                onSelect = { cameraViewModel.selectFilter(it) },
                onSelectCustom = { cameraViewModel.selectCustomFilter(it) },
                onEditCustom = {
                    showFilterPanel = false
                    if (settings.proModeEnabled) onSettingsChange(settings.copy(proModeEnabled = false))
                    showFilterEditor = true
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .zIndex(6f)
                    .padding(start = 16.dp, end = 16.dp, bottom = floatingControlsBottom)
            )
        }

        // ── 第 3 层：底部拍摄工具栏 ──
        if (uiState.isInitialized && !showCaptureResult) {
            CameraBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
                    .onSizeChanged { bottomBarHeightPx = it.height },
                latestPhotoUri = uiState.latestPhotoUri,
                onTakePhoto = { triggerCapture() },
                onToggleCamera = { cameraViewModel.toggleCamera() },
                onOpenGallery = onNavigateToGallery,
                isCapturing = uiState.isCapturing,
                aligned = captureReady,
                proEnabled = settings.proModeEnabled,
                onTogglePro = {
                    showFilterPanel = false
                    onSettingsChange(settings.copy(proModeEnabled = !settings.proModeEnabled))
                },
            )

            // ── 右侧变焦罗盘（浮在底栏上方，替代横向滑杆） ──
            // Pro 面板和滤镜面板自带变焦/参数控件，此时隐藏罗盘避免两套控件重叠。
            if (!settings.proModeEnabled && !showFilterPanel && !captureCommitted &&
                sessionState !is CompositionSessionController.State.Capturing
            ) {
                ZoomDial(
                    zoomRatio = uiState.zoomRatio,
                    zoomRange = cameraViewModel.zoomRange(),
                    onZoomChange = {
                        renderViewModel.cancelAutoZoom()
                        cameraViewModel.setZoom(it)
                    },
                    interactionTick = zoomInteractionTick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .zIndex(6f)
                        .padding(end = 4.dp, bottom = floatingControlsBottom),
                )
            }
        }

        // ── Pro 模式面板（AI 构图叠加层不受影响，独立开关） ──
        if (uiState.isInitialized && settings.proModeEnabled && !showCaptureResult) {
            val evIndex by cameraViewModel.evIndex.collectAsStateWithLifecycle()
            val isoValue by cameraViewModel.isoValue.collectAsStateWithLifecycle()
            val awbMode by cameraViewModel.awbMode.collectAsStateWithLifecycle()
            val exposureTimeNs by cameraViewModel.exposureTimeNs.collectAsStateWithLifecycle()
            val focusDistance by cameraViewModel.focusDistance.collectAsStateWithLifecycle()
            CompactProPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(7f)
                    .padding(horizontal = 10.dp)
                    .padding(bottom = floatingControlsBottom),
                isVisible = true,
                evValue = evIndex,
                evRange = cameraViewModel.evRange(),
                onEvChange = { cameraViewModel.setExposureCompensation(it) },
                isoValue = isoValue ?: 0,
                onIsoChange = { cameraViewModel.setProIso(if (it == 0) null else it) },
                awbMode = awbMode ?: android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO,
                onAwbChange = {
                    cameraViewModel.setProAwb(
                        if (it == android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO) null else it
                    )
                },
                exposureTimeNs = exposureTimeNs,
                onExposureTimeChange = { cameraViewModel.setProExposureTime(it) },
                focusDistance = focusDistance,
                onFocusDistanceChange = { cameraViewModel.setProFocusDistance(it) },
                zoomRatio = uiState.zoomRatio,
                zoomRange = cameraViewModel.zoomRange(),
                onZoomChange = { cameraViewModel.setZoom(it) },
                onDismiss = { onSettingsChange(settings.copy(proModeEnabled = false)) }
            )
        }

        // ── ★ 构图-拍摄工作流叠加层（目标点每帧实时更新） ──
        if (uiState.isInitialized) {
            val horizonLine = renderInput.guideLines.find { it.type.name == "HORIZON_LEVEL" }
            val horizonAngle = (horizonLine?.params?.get("angle_degrees") as? Number)?.toFloat()
            val targetCenter = liveTargetCenter()
            val subjectCenter = liveSubjectCenter()
            // 有明确动作指令时不再让质量反馈抢占文案；只有当前没有方向可给时才提示状态。
            val hasActionCue = workflowCue != GuidanceCue.NONE &&
                workflowCue != GuidanceCue.SUBJECT_ALIGNED
            // 只保留真正需要用户回退的质量提示；确认中/请稍候等过渡文案全部去掉。
            val feedbackHint = if (hasActionCue) null else when (sessionDecision.feedback) {
                com.example.aicamera.guidance.GuidanceFeedback.WORSENING -> when {
                    "observed_subject_margin_regressed" in sessionDecision.reasons -> "主体太靠近画面边缘，先回一点"
                    "subject_sharpness_regressed" in sessionDecision.reasons -> "主体清晰度下降，先回一点"
                    "subject_highlights_regressed" in sessionDecision.reasons -> "主体亮部细节减少，先回一点"
                    else -> "刚才的调整没有改善，先回一点"
                }
                else -> if (!hasActionCue) stepHint.takeIf { it.isNotBlank() } else null
            }
            CaptureWorkflowOverlay(
                state = workflowState,
                cue = workflowCue,
                phase = workflowPhase,
                analysisHint = stepHint.takeIf { it.isNotBlank() },
                feedbackHint = feedbackHint,
                targetNx = targetCenter?.first,
                targetNy = targetCenter?.second,
                horizonAngleDeg = horizonAngle,
                subjectNx = subjectCenter?.first,
                subjectNy = subjectCenter?.second,
                showGrid = settings.showGrid,
                showSubjectBox = settings.showSubjectBox,
                showTargetZone = settings.showTargetZone,
                showHorizon = settings.showHorizon,
                overlayAlpha = settings.overlayAlpha,
                guideLines = renderInput.guideLines,
                // The detector supplies a face box, not a verified full-body pose.
                poseGuide = false,
                bottomBarHeight = measuredBottomBarHeight,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 对焦框 + 曝光条 ──
        if (showExposureSlider && uiState.isInitialized && !showCaptureResult && !captureCommitted && !uiState.isCapturing) {
            val evIndex by cameraViewModel.evIndex.collectAsStateWithLifecycle()
            FocusExposureOverlay(
                focusX = focusCenter.x,
                focusY = focusCenter.y,
                evIndex = evIndex,
                evRange = cameraViewModel.evRange(),
                onEVChange = { cameraViewModel.setExposureCompensation(it) },
                visible = showExposureSlider,
                focusRequestId = focusRequestId,
                modifier = Modifier.fillMaxSize().zIndex(8f)
            )
        }

        // ── 拍后结果确认（不做分数评价） ──
        if (showCaptureResult) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showCaptureResult = false
                        renderViewModel.sessionController.resetToIdle()
                    }
                    .zIndex(11f)
            )
            CaptureResultSheet(
                imageUri = uiState.latestPhotoUri,
                strategy = renderInput.compositionStrategy,
                sceneType = renderInput.sceneType,
                aiMethod = capturedAiMethod,
                onDone = {
                    showCaptureResult = false
                    renderViewModel.sessionController.resetToIdle()
                },
                onRetake = {
                    showCaptureResult = false
                    renderViewModel.sessionController.resetToIdle()
                },
                onShareToCommunity = {
                    showCaptureResult = false
                    showPublishSheet = true
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(12f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .navigationBarsPadding()
            )
        }

        if (showTakeNow && !captureCommitted && !uiState.isCapturing && !showCaptureResult) {
            Button(
                onClick = { captureNow() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(10f)
                    .padding(top = 96.dp, end = 12.dp)
                    .widthIn(max = 180.dp)
                    .heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandPink,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("先拍一张", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "不用等引导完成",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 9.sp
                    )
                }
            }
        }

        captureError?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { captureError = null }
                    .zIndex(13f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("拍摄失败", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(message, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { captureError = null }) { Text("取消") }
                        Button(onClick = {
                            captureError = null
                            renderViewModel.sessionController.resetToIdle()
                            triggerCapture()
                        }) { Text("重试") }
                    }
                }
            }
        }

        // ── 社区发布 Sheet ──
        if (showPublishSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showPublishSheet = false
                    renderViewModel.sessionController.resetToIdle()
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFFFFF1F5)
            ) {
                PublishToCommunitySheet(
                    imageUri = uiState.latestPhotoUri,
                    description = publishDescription,
                    isPublishing = isPublishing,
                    onDescriptionChange = { publishDescription = it },
                    onPublish = {
                        val imageKey = uiState.latestPhotoUri
                        if (imageKey != null && !isPublishing) {
                            isPublishing = true
                            val body = publishDescription.takeIf { it.isNotBlank() }
                                ?: "AI 构图作品"
                            communityViewModel.publishPost(
                                author = currentUser,
                                body = body,
                                imageAssetKey = imageKey.toString(),
                                elapsedText = "刚刚"
                            ) {
                                isPublishing = false
                                showPublishSheet = false
                                publishDescription = ""
                                renderViewModel.sessionController.resetToIdle()
                                onNavigateToCommunity()
                                Toast.makeText(context, "已发布到社区", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCancel = {
                        showPublishSheet = false
                        renderViewModel.sessionController.resetToIdle()
                    }
                )
            }
        }

        // ── 定时拍照倒计时 ──
        if (countdown > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$countdown",
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showFilterEditor) {
            val selectedCustom by cameraViewModel.selectedCustomFilter.collectAsStateWithLifecycle()
            FilterEditorScreen(
                initial = selectedCustom,
                onBack = { showFilterEditor = false },
                onSave = {
                    cameraViewModel.selectCustomFilter(it)
                    showFilterEditor = false
                    showFilterPanel = true
                }
            )
        }

        // ── 加载遮罩 ──
        if (!uiState.isInitialized) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }
    }

    // ── 生命周期 ──
    DisposableEffect(Unit) {
        onDispose {
            previewView?.let { gestureDispatcher.detach(it) }
            pipelineViewModel.pause()
            cameraViewModel.onPause()
        }
    }
}

/** 定时拍照模式 */
enum class TimerMode(val seconds: Int) {
    OFF(0), THREE(3), TEN(10)
}

/**
 * AI 引擎状态胶囊 — 让用户一眼确认 AI 是否工作。
 *
 * READY（V9 推理在线）→ 绿点「AI 已就绪 · V9」
 * COPY/LOAD/IDLE      → 灰点「AI 引擎加载中…」
 * FAILED（ONNX 加载失败，本地引导兜底）→ 黄点「AI 演示模式」
 */
@Composable
private fun AiCompositionChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "AI 构图 · $label",
            color = CameraOnChrome.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AiStatusChip(
    modifier: Modifier = Modifier,
    status: com.example.aicamera.pipeline.AiEngineManager.Status,
    inferenceType: String,
    degraded: Boolean = false
) {
    val (dotColor, label) = when {
        degraded ->
            com.example.aicamera.ui.theme.GuideWarning to "AI 演示模式"
        status == com.example.aicamera.pipeline.AiEngineManager.Status.READY -> {
            // ★ Release 隐藏内部推理引擎名称（V9-ONNX/Fake），避免出现「AI 已就绪 · AI」
            GuideAligned to if (com.example.aicamera.BuildConfig.DEBUG) {
                "AI 已就绪 · $inferenceType"
            } else {
                "AI 已就绪"
            }
        }
        status == com.example.aicamera.pipeline.AiEngineManager.Status.FAILED ->
            com.example.aicamera.ui.theme.GuideWarning to "AI 演示模式"
        else ->
            CameraControlMuted to "AI 引擎加载中…"
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp
        )
    }
}

// ────────────────────────────────────────────────────────────────
// 顶部构图模式选择器（队友 CameraControls.kt CameraTopControls）
// 三种模式：静物 / 风景 / 人像 + 右侧设置入口
// ────────────────────────────────────────────────────────────────

/** 顶部 Icon glyph 标识 */
private enum class TopGlyph { COFFEE, LANDSCAPE, PERSON, SETTINGS }

@Composable
private fun CameraTopBar(
    modifier: Modifier = Modifier,
    aiStatus: com.example.aicamera.pipeline.AiEngineManager.Status,
    inferenceType: String,
    degraded: Boolean,
    onSettingsClick: () -> Unit,
) {
    // 顶部黑带对齐参考机：状态栏 + 56dp 控制行，功能键靠左、设置靠右。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CameraChrome)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiStatusChip(
            status = aiStatus,
            inferenceType = inferenceType,
            degraded = degraded,
        )
        Spacer(Modifier.weight(1f))
        TopModeControl(
            description = "相机设置",
            selected = false,
            glyph = TopGlyph.SETTINGS,
            onClick = onSettingsClick,
        )
    }
}

@Composable
private fun TopModeControl(
    description: String,
    selected: Boolean,
    glyph: TopGlyph,
    onClick: () -> Unit,
) {
    val iconColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) BrandPink else Color.White,
        animationSpec = androidx.compose.animation.core.tween(GenWowMotion.ModeChangeMillis),
        label = "top mode color",
    )
    val iconRes = when (glyph) {
        TopGlyph.COFFEE -> R.drawable.ic_top_coffee
        TopGlyph.LANDSCAPE -> R.drawable.ic_top_sunrise
        TopGlyph.PERSON -> R.drawable.ic_top_person
        TopGlyph.SETTINGS -> R.drawable.ic_top_settings
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .padding(8.dp)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (selected) .9f else .5f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

// ────────────────────────────────────────────────────────────────
// 底部拍摄工具栏（GenWow 设计规范）
// ────────────────────────────────────────────────────────────────

@Composable
private fun CameraBottomBar(
    modifier: Modifier = Modifier,
    latestPhotoUri: Uri?,
    onTakePhoto: () -> Unit,
    onToggleCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    isCapturing: Boolean,
    aligned: Boolean,
    proEnabled: Boolean,
    onTogglePro: () -> Unit,
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val modeRowHeight = if (compact) 36.dp else 48.dp
    val shutterRowHeight = if (compact) 84.dp else 112.dp

    // 参考机式的黑色底栏：上方模式条 + 下方大快门行，倍率移到右侧罗盘。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CameraChrome)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(modeRowHeight)
                .padding(horizontal = if (compact) 12.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 自动识别", color = CameraOnChrome, fontSize = 13.sp, modifier = Modifier.weight(3f))
            ModeStripItem(
                label = "PRO",
                selected = proEnabled,
                onClick = onTogglePro,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(shutterRowHeight)
                .padding(horizontal = if (compact) 18.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 相册缩略图
            Box(
                modifier = Modifier
                    .size(if (compact) 44.dp else 52.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenGallery
                    )
                    .semantics { contentDescription = "打开相册" },
                contentAlignment = Alignment.Center,
            ) {
                if (latestPhotoUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(latestPhotoUri)
                            .crossfade(true)
                            .size(160)
                            .build(),
                        contentDescription = "最新照片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = CameraOnChrome.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            CaptureButton(
                aligned = aligned,
                enabled = !isCapturing,
                compact = compact,
                onClick = onTakePhoto
            )

            // 翻转摄像头
            Box(
                modifier = Modifier
                    .size(if (compact) 44.dp else 52.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable(onClick = onToggleCamera)
                    .semantics { contentDescription = "切换前后摄像头" },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_camera_flip),
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 22.dp else 24.dp),
                    colorFilter = ColorFilter.tint(CameraOnChrome),
                )
            }
        }
        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
    }
}

@Composable
private fun ModeStripItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = label
                this.selected = selected
                role = Role.Button
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else CameraControlMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(4.dp)
                .background(if (selected) BrandPink else Color.Transparent, CircleShape)
        )
    }
}

/**
 * 大号快门：参考机 84dp 触控区 / 76dp 画布；白外环 + 粉色内圆。
 * 对齐时外环变绿；按下缩放 0.92。
 */
@Composable
private fun CaptureButton(
    aligned: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = androidx.compose.animation.core.tween(GenWowMotion.PressMillis),
        label = "capture press"
    )
    Box(
        modifier = Modifier
            .size(if (compact) 72.dp else 84.dp)
            .scale(scale)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = "拍照"
                role = Role.Button
                stateDescription = when {
                    !enabled -> "拍摄进行中，暂不可用"
                    aligned -> "构图已对齐"
                    else -> "构图未对齐"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(if (compact) 64.dp else 76.dp)) {
            val ringColor = if (aligned) GuideAligned else Color.White
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 1.6.dp.toPx(),
                style = Stroke(width = 3.2.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = size.minDimension / 2f - 9.dp.toPx()
            )
            val innerRadius = if (compact) 22.dp.toPx() else 26.dp.toPx()
            drawCircle(color = BrandPink, radius = innerRadius)
            drawCircle(color = Color.White.copy(alpha = 0.22f), radius = innerRadius)
        }
    }
}

/**
 * 侧边球冠变焦罗盘。
 *
 * - 球半径很大，只在屏幕右侧露出一段球冠弧线（宽度适中）；
 * - 只在用户捏合 / 拖动 / 点预设时出现，1.8 秒无操作自动淡出；
 * - 未显示罗盘时右侧只保留 1× / 2× / 3× 预设；
 * - 罗盘出现时预设整体隐藏，罗盘内部显示当前倍率，二者不重叠。
 */
@Composable
private fun ZoomDial(
    zoomRatio: Float,
    zoomRange: Pair<Float, Float>,
    onZoomChange: (Float) -> Unit,
    interactionTick: Int,
    modifier: Modifier = Modifier,
) {
    val minZoom = zoomRange.first.coerceAtLeast(0.1f)
    val maxZoom = zoomRange.second.coerceAtLeast(minZoom)
    val shownZoom = zoomRatio.coerceIn(minZoom, maxZoom)
    val presets = remember(minZoom, maxZoom) {
        listOf(1f, 2f, 3f).filter { it >= minZoom - 0.001f && it <= maxZoom + 0.001f }
    }
    var dialVisible by remember { mutableStateOf(false) }
    var hideToken by remember { mutableIntStateOf(0) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var selectedPreset by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val dragState = rememberUpdatedState(shownZoom)
    val minState = rememberUpdatedState(minZoom)
    val maxState = rememberUpdatedState(maxZoom)
    val haptic = LocalHapticFeedback.current
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun revealDial() {
        dialVisible = true
        hideToken++
    }

    fun animateToPreset(target: Float) {
        animationJob?.cancel()
        selectedPreset = target
        revealDial()
        val start = shownZoom
        val clamped = target.coerceIn(minZoom, maxZoom)
        animationJob = scope.launch {
            val steps = 12
            repeat(steps) { step ->
                val p = (step + 1f) / steps
                onZoomChange(start + (clamped - start) * p)
                delay(16L)
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(interactionTick) {
        if (interactionTick > 0) {
            selectedPreset = null
            revealDial()
        }
    }
    LaunchedEffect(dialVisible, hideToken) {
        if (dialVisible) {
            delay(1_800L)
            dialVisible = false
        }
    }

    Box(
        modifier = modifier
            .width(if (compact) 78.dp else 96.dp)
            .height(if (compact) 188.dp else 224.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 空闲态：只有预设按键，贴右侧排列。与罗盘互斥，绝不重叠。
        if (!dialVisible) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                presets.forEach { preset ->
                    val selected = kotlin.math.abs(shownZoom - preset) < 0.05f
                    ZoomPresetChip(
                        label = formatZoomLabel(preset),
                        selected = selected,
                        onClick = { animateToPreset(preset) },
                    )
                }
            }
        }

        // 变焦态：球冠刻度盘；下方只保留当前选中的预设，另外两个隐藏。
        if (dialVisible) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 148.dp else 180.dp)
                    .pointerInput(Unit) {
                        var dragStart = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                animationJob?.cancel()
                                selectedPreset = null
                                dragStart = dragState.value
                                revealDial()
                            },
                            onDragEnd = {
                                val nearest = presets.minByOrNull { kotlin.math.abs(it - dragState.value) }
                                if (nearest != null && kotlin.math.abs(nearest - dragState.value) <= 0.08f) {
                                    onZoomChange(nearest)
                                    selectedPreset = nearest
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            revealDial()
                            val span = maxState.value - minState.value
                            val next = (dragStart - dragAmount / 320f * span)
                                .coerceIn(minState.value, maxState.value)
                            dragStart = next
                            onZoomChange(kotlin.math.round(next * 10f) / 10f)
                        }
                    }
                    .semantics { contentDescription = "球冠变焦罗盘，上下拖动调整倍率" },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // 大半径球冠：右侧一条轻微向左凸的弧线。
                    fun point(t: Float): Offset {
                        val y = h * (0.92f - 0.84f * t)
                        val u = (y / h).coerceIn(0f, 1f)
                        val x = (1 - u) * (1 - u) * w * 0.94f +
                            2f * (1 - u) * u * w * 0.18f +
                            u * u * w * 0.94f
                        return Offset(x, y)
                    }

                    val track = Path()
                    for (i in 0..64) {
                        val p = point(i / 64f)
                        if (i == 0) track.moveTo(p.x, p.y) else track.lineTo(p.x, p.y)
                    }
                    drawPath(
                        track,
                        color = Color.White.copy(alpha = 0.26f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )

                    val fraction = if (maxZoom > minZoom) {
                        ((shownZoom - minZoom) / (maxZoom - minZoom)).coerceIn(0f, 1f)
                    } else 0f
                    if (fraction > 0f) {
                        val active = Path()
                        val steps = 48
                        for (i in 0..steps) {
                            val p = point(fraction * i / steps)
                            if (i == 0) active.moveTo(p.x, p.y) else active.lineTo(p.x, p.y)
                        }
                        drawPath(
                            active,
                            color = Color.White.copy(alpha = 0.95f),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }

                    // 刻度：沿弧线法线方向绘制，长刻度对应 1×/2×/3×。
                    val majorTicks = presets.map {
                        if (maxZoom > minZoom) ((it - minZoom) / (maxZoom - minZoom)).coerceIn(0f, 1f)
                        else 0f
                    }.toSet()
                    repeat(37) { i ->
                        val t = i / 36f
                        val p = point(t)
                        val p2 = point((t + 0.008f).coerceAtMost(1f))
                        val dx = p2.x - p.x
                        val dy = p2.y - p.y
                        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
                        val nx = -dy / len
                        val ny = dx / len
                        val major = majorTicks.any { kotlin.math.abs(it - t) < 0.02f } || i % 6 == 0
                        val tickLen = if (major) 10.dp.toPx() else 5.dp.toPx()
                        drawLine(
                            color = Color.White.copy(alpha = if (major) 0.78f else 0.30f),
                            start = p,
                            end = Offset(p.x + nx * tickLen, p.y + ny * tickLen),
                            strokeWidth = if (major) 1.8.dp.toPx() else 1.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }

                    // 当前倍率黄色指针。
                    val knob = point(fraction)
                    drawCircle(Color.Black.copy(alpha = 0.38f), 10.dp.toPx(), knob)
                    drawCircle(Color(0xFFFFD54F), 4.5.dp.toPx(), knob)

                    // 弧线上的 1×/2×/3× 刻度文字。
                    val labelPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = 0xCCFFFFFF.toInt()
                        textSize = 9.sp.toPx()
                    }
                    presets.forEach { preset ->
                        val t = if (maxZoom > minZoom) {
                            ((preset - minZoom) / (maxZoom - minZoom)).coerceIn(0f, 1f)
                        } else 0f
                        val p = point(t)
                        drawContext.canvas.nativeCanvas.drawText(
                            formatZoomLabel(preset),
                            p.x - 26.dp.toPx(),
                            p.y + 3.dp.toPx(),
                            labelPaint,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatZoomLabel(shownZoom),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "变焦",
                        color = CameraOnChrome.copy(alpha = 0.62f),
                        fontSize = 9.sp,
                    )
                }
            }
                // 只显示当前选中的预设，另外两个在罗盘出现时隐去。
                val shownPreset = selectedPreset ?: presets.minByOrNull {
                    kotlin.math.abs(it - shownZoom)
                }
                if (shownPreset != null) {
                    ZoomPresetChip(
                        label = formatZoomLabel(shownPreset),
                        selected = true,
                        onClick = { animateToPreset(shownPreset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .border(
                1.dp,
                if (selected) BrandPink.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.20f),
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "变焦 $label"
                this.selected = selected
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) BrandPink else Color.White.copy(alpha = 0.90f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 倍率标签：整数值显示「2×」，小数值显示「0.8×」 */
private fun formatZoomLabel(value: Float): String {
    val rounded = kotlin.math.round(value)
    return if (kotlin.math.abs(value - rounded) < 0.05f) "${rounded.toInt()}×"
    else "${"%.1f".format(value)}×"
}

// ── 系统预设滤镜选择条 ────────────────────────────────────────────

@Composable
private fun FilterDock(
    selected: PresetFilter,
    selectedCustom: CustomFilterConfig?,
    customFilters: List<CustomFilterConfig>,
    customActive: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (PresetFilter) -> Unit,
    onSelectCustom: (CustomFilterConfig) -> Unit,
    onEditCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = remember { PresetFilter.ALL.filterNotNull() }
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(Color.Black.copy(alpha = .58f), RoundedCornerShape(18.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                filters.forEach { filter ->
                    FilterCover(
                        title = stringResource(filter.nameRes),
                        imageRes = com.example.aicamera.R.drawable.community_cafe_portrait,
                        colorFilter = remember(filter) {
                            ColorFilter.colorMatrix(ComposeColorMatrix(filter.colorMatrix.array))
                        },
                        selected = !customActive && filter == selected,
                        onClick = { onSelect(filter) }
                    )
                }
                customFilters.forEach { filter ->
                    FilterCover(
                        title = filter.name,
                        imageRes = com.example.aicamera.R.drawable.community_cafe_portrait,
                        colorFilter = remember(filter) {
                            ColorFilter.colorMatrix(ComposeColorMatrix(filter.colorMatrix().array))
                        },
                        selected = customActive && selectedCustom?.id == filter.id,
                        onClick = { onSelectCustom(filter) },
                        onLongClickLabel = "调参",
                        onLongClick = onEditCustom
                    )
                }
                FilterCover(
                    title = "+ 新建",
                    imageRes = com.example.aicamera.R.drawable.community_cafe_portrait,
                    selected = false,
                    onClick = onEditCustom
                )
            }
            Text(
                "实时取景仅显示基础色彩；高光、阴影、锐化、暗角在成片中完整应用。",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = onToggle,
            color = Color.Black.copy(alpha = .58f),
            contentColor = Color.White,
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(
                text = "◐  " + if (customActive) (selectedCustom?.name ?: "自定义") else stringResource(selected.nameRes),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FilterCover(
    title: String,
    imageRes: Int,
    colorFilter: ColorFilter? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClickLabel: String? = null,
    onLongClick: () -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(68.dp, 84.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) BrandPink else Color.White.copy(alpha = .12f))
                .clickable(onClick = onClick).padding(if (selected) 2.dp else 0.dp)
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = title,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter
            )
        }
        Text(title, color = if (selected) BrandPink else Color.White, fontSize = 10.sp,
            maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        if (onLongClickLabel != null) {
            Text(onLongClickLabel, color = BrandPink, fontSize = 9.sp, modifier = Modifier.clickable(onClick = onLongClick))
        }
    }
}

// ── 发布到社区 Sheet ────────────────────────────────────────────

@Composable
private fun PublishToCommunitySheet(
    imageUri: Uri?,
    description: String,
    isPublishing: Boolean = false,
    onDescriptionChange: (String) -> Unit,
    onPublish: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "发布到社区",
            color = BrandOnSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(14.dp))
        if (imageUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "待发布图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无图片", color = BrandOnSurfaceMuted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("添加描述...", fontSize = 13.sp) },
            textStyle = LocalTextStyle.current.copy(color = BrandOnSurface, fontSize = 14.sp),
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BrandPink,
                unfocusedBorderColor = BrandOnSurfaceMuted.copy(alpha = 0.3f),
            )
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.6f),
                    contentColor = BrandOnSurface,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("取消", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onPublish,
                enabled = imageUri != null && !isPublishing,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandPink,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (imageUri == null) "暂无图片" else "发布",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
