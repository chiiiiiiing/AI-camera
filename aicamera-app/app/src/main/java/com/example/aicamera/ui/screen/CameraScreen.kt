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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.aicamera.camera.CameraGestureDispatcher
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.filter.PresetFilter
import com.example.aicamera.filter.CustomFilterConfig
import com.example.aicamera.guidance.AlignmentFlashOverlay
import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.guidance.GuidancePhase
import com.example.aicamera.guidance.GuidancePresentation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.ui.components.CaptureWorkflowOverlay
import com.example.aicamera.ui.components.CaptureWorkflowState
import com.example.aicamera.ui.components.CaptureResultSheet
import com.example.aicamera.ui.components.FocusExposureOverlay
import com.example.aicamera.ui.components.CompactProPanel
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
    val renderInput by renderViewModel.renderInput.collectAsStateWithLifecycle()
    val pipelineState by pipelineViewModel.pipelineState.collectAsStateWithLifecycle()
    val sceneAnalysis by pipelineViewModel.sceneAnalysis.collectAsStateWithLifecycle()
    var sceneDisplayTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(sceneAnalysis) {
        sceneDisplayTime = android.os.SystemClock.elapsedRealtime()
        sceneAnalysis?.let {
            delay((it.sampledAtMs + 1501L - sceneDisplayTime).coerceAtLeast(1L))
            sceneDisplayTime = android.os.SystemClock.elapsedRealtime()
        }
    }
    val automaticScene = sceneAnalysis?.takeIf {
        it.isFresh(maxOf(sceneDisplayTime, android.os.SystemClock.elapsedRealtime()))
    }?.family ?: SceneFamily.UNKNOWN
    // ★ 统一构图会话控制器状态（替代旧双工作流）
    val sessionState by renderViewModel.sessionController.state.collectAsStateWithLifecycle()
    val sessionDecision by renderViewModel.sessionController.decision.collectAsStateWithLifecycle()
    var presentationTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(sessionDecision, sessionState) {
        presentationTime = android.os.SystemClock.elapsedRealtime()
        sessionDecision.displayEvidence?.let {
            delay((it.expiresAtMs - presentationTime + 1L).coerceAtLeast(1L))
            presentationTime = android.os.SystemClock.elapsedRealtime()
        }
    }
    val presentation = GuidancePresentation.from(sessionState, sessionDecision,
        maxOf(presentationTime, android.os.SystemClock.elapsedRealtime()))
    val workflowState = mapSessionState(presentation.state)
    val workflowCue = presentation.cue
    val workflowPhase = presentation.phase
    // 拍照后构图说明标签（null = 不显示）
    var compositionLabel by remember { mutableStateOf<String?>(null) }
    // 拍后结果确认页（不生成伪审美分数）
    var showCaptureResult by remember { mutableStateOf(false) }
    var workflowStartedAt by remember { mutableLongStateOf(0L) }
    var showTakeNow by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var showFilterEditor by remember { mutableStateOf(false) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val measuredBottomBarHeight = if (bottomBarHeightPx > 0) {
        with(density) { bottomBarHeightPx.toDp() }
    } else {
        152.dp
    }
    val floatingControlsBottom = measuredBottomBarHeight + 14.dp
    // 点击对焦位置与曝光条显隐
    var focusCenter by remember { mutableStateOf(Offset.Zero) }
    var showExposureSlider by remember { mutableStateOf(false) }
    val aiState by pipelineViewModel.aiState.collectAsStateWithLifecycle()
    val v9DegradedReason by pipelineViewModel.v9DegradedReason.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val communityViewModel: CommunityViewModel = hiltViewModel()
    val authRepo = remember { AuthRepository.getInstance(context) }
    val currentUser = authRepo.currentUsername ?: "用户"

    LaunchedEffect(Unit) {
        cameraViewModel.refreshImportedFilter()
    }

    // 社区发布 Sheet
    var showPublishSheet by remember { mutableStateOf(false) }
    var publishDescription by remember { mutableStateOf("") }

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
        pipelineViewModel.invalidateSceneRecognition(uiState.cameraFace == CameraFace.FRONT)
        delay(100)
        previewView?.display?.rotation?.let(cameraViewModel::updateTargetRotation)
    }
    val gestureDispatcher = remember {
        CameraGestureDispatcher(
            onFocusCircle = { cx, cy -> cameraViewModel.onFocusTapped(cx, cy) },
            onZoomChanged = {
                // 用户捏合变焦 = 手动介入：取消自动变焦（序列器与斜坡同时让权）
                cameraViewModel.cancelAutoZoom()
                renderViewModel.cancelAutoZoom()
            },
            onFocusLocked = { locked -> cameraViewModel.setFocusLocked(locked) }
        )
    }

    // 定时拍照（关 / 3s / 10s）
    var timerMode by remember { mutableStateOf(TimerMode.OFF) }
    var countdown by remember { mutableStateOf(0) }  // >0 = 倒计时进行中

    // 快门声（按设置开关）
    val shutterSound = remember { MediaActionSound() }
    DisposableEffect(Unit) {
        onDispose { shutterSound.release() }
    }

    // Feedback follows the controller's live Ready lease, never the legacy geometry phase.
    val haptic = LocalHapticFeedback.current
    var showAlignFlash by remember { mutableStateOf(false) }
    LaunchedEffect(presentation.ready) {
        if (presentation.ready) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showAlignFlash = true
        } else showAlignFlash = false
    }

    // 订阅 focusCircle → Compose 对焦框及曝光条（旧 View 叠加层已卸载）
    LaunchedEffect(Unit) {
        cameraViewModel.focusCircle.collect { focusData ->
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
            when (result) {
                is com.example.aicamera.camera.CameraCapture.CaptureResult.Success -> {
                    if (settings.soundEnabled) {
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    }
                    renderViewModel.sessionController.onCaptureComplete(result.uri.toString())
                    result.processingWarning?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                    delay(350)
                    showCaptureResult = true
                }
                is com.example.aicamera.camera.CameraCapture.CaptureResult.Error -> {
                    captureError = result.message
                    renderViewModel.sessionController.error(result.message)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
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
        renderViewModel.autoZoomTarget.collect { target ->
            cameraViewModel.rampZoomTo(target)
            // 斜坡 700ms + 余量后认为到位（用户打断时 cancelAutoZoom 已另走取消路径）
            delay(900)
            renderViewModel.notifyAutoZoomApplied()
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
        if (uiState.isCapturing ||
            renderViewModel.sessionController.state.value is CompositionSessionController.State.Capturing
        ) return

        showTakeNow = false
        showFilterPanel = false
        renderViewModel.sessionController.startCapture()
        scope.launch {
            compositionLabel = "正在对焦与测光"
            cameraViewModel.prepareForCapture(timeoutMs = 900L)
            compositionLabel = "按当前构图拍摄"
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
        showTakeNow = false
        showCaptureResult = false
        showFilterPanel = false
        if (settings.proModeEnabled) onSettingsChange(settings.copy(proModeEnabled = false))
        captureError = null

        if (timerMode != TimerMode.OFF && countdown == 0) {
            scope.launch {
                countdown = timerMode.seconds
                while (countdown > 0) { delay(1000); countdown-- }
                renderViewModel.sessionController.startAnalysis()
            }
        } else if (countdown > 0) {
            countdown = 0
        } else {
            renderViewModel.sessionController.startAnalysis()
        }
    }

    /** 从 AI 输出提取中文构图说明（如"三分构图 · 明亮场景"） */
    fun compositionDescription(): String {
        val strategy = renderInput.compositionStrategy
        val scene = renderInput.sceneType
        val strategyCN = when {
            strategy.contains("rule_of_thirds") || strategy.contains("thirds") -> "三分构图"
            strategy.contains("centered") || strategy.contains("symmetry") -> "对称构图"
            strategy.contains("leading") -> "引导线构图"
            strategy.contains("negative_space") -> "留白构图"
            strategy.contains("horizon") -> "水平构图"
            else -> ""
        }
        val sceneCN = when {
            scene.contains("bright") -> "明亮"
            scene.contains("dark") -> "暗调"
            scene.contains("normal") -> "自然光"
            else -> ""
        }
        return when {
            strategyCN.isNotEmpty() && sceneCN.isNotEmpty() -> "$strategyCN · $sceneCN"
            strategyCN.isNotEmpty() -> strategyCN
            sceneCN.isNotEmpty() -> sceneCN
            else -> "AI 构图"
        }
    }

    // ★ 统一会话控制器驱动：ANALYZING → 延迟后通知完成
    LaunchedEffect(sessionState) {
        when (sessionState) {
            is CompositionSessionController.State.Analyzing -> {
                // ★ 修复：不再强制重置用户 Zoom（尊重用户手动选择的焦段）
                // 分析阶段持续 800ms
                delay(800)
                renderViewModel.sessionController.onAnalysisComplete()
            }
            is CompositionSessionController.State.Ready -> {
                // Preserve verified framing; do not guess a new focal length after Ready.
                compositionLabel = "正在对焦与测光"
                cameraViewModel.prepareForCapture()
                // AF/AE may finish after a new bad frame or after observations expire.
                // Compose cancellation alone cannot close the state/recomposition race.
                if (!renderViewModel.sessionController.confirmReadyForCapture(
                        android.os.SystemClock.elapsedRealtime())) return@LaunchedEffect
                compositionLabel = compositionDescription()
                renderViewModel.sessionController.startCapture()
                cameraViewModel.takePhoto(saveToGallery = settings.saveOriginal)
                // 结果确认页在真实保存成功事件后显示，避免 UI 提前宣称保存完成。
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

    // 构图引导最多等待 8 秒，之后允许用户直接拍摄，不把“辅助”变成阻碍。
    LaunchedEffect(workflowStartedAt) {
        if (workflowStartedAt == 0L) return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - workflowStartedAt
        delay((8_000L - elapsed).coerceAtLeast(0L))
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
        if (!waiting) showTakeNow = false
    }

    // 切换摄像头 → 取消当前工作流
    LaunchedEffect(uiState.cameraFace) {
        val currentState = renderViewModel.sessionController.state.value
        if (currentState !is CompositionSessionController.State.Idle) {
            renderViewModel.sessionController.cancel()
        }
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

        // ── 第 3 层：只读自动识别状态与设置入口 ──
        if (uiState.isInitialized && !showCaptureResult) {
            CameraTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                family = automaticScene,
                onSettingsClick = onNavigateToSettings,
            )
        }

        // ── AI 引擎状态指示（单行小胶囊，替代旧多行调试 HUD） ──
        if (uiState.isInitialized &&
            (sessionState is CompositionSessionController.State.Idle ||
                sessionState is CompositionSessionController.State.Saved) &&
            !showCaptureResult
        ) {
            AiStatusChip(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 64.dp, start = 12.dp),
                status = aiState.status,
                inferenceType = pipelineState.inferenceType,
                degraded = v9DegradedReason != null
            )
        }

        // ── 滤镜小组件：默认收起，点击后才展开 ──
        if (uiState.isInitialized && !showCaptureResult) {
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
                onToggleCamera = {
                    pipelineViewModel.invalidateSceneRecognition()
                    cameraViewModel.toggleCamera()
                    pipelineViewModel.invalidateSceneRecognition(
                        cameraViewModel.uiState.value.cameraFace == CameraFace.FRONT)
                },
                onOpenGallery = onNavigateToGallery,
                zoomRatio = uiState.zoomRatio,
                onZoomChange = {
                    renderViewModel.cancelAutoZoom()
                    cameraViewModel.setZoom(it)
                },
                isCapturing = uiState.isCapturing,
                aligned = presentation.ready,
                proEnabled = settings.proModeEnabled,
                onTogglePro = {
                    showFilterPanel = false
                    onSettingsChange(settings.copy(proModeEnabled = !settings.proModeEnabled))
                },
                zoomRange = cameraViewModel.zoomRange()
            )
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
            val evidence = presentation.evidence
            CaptureWorkflowOverlay(
                state = workflowState,
                cue = workflowCue,
                phase = workflowPhase,
                paused = presentation.paused,
                verifying = presentation.verifying,
                feedbackHint = presentation.hint,
                targetNx = evidence?.target?.x,
                targetNy = evidence?.target?.y,
                horizonAngleDeg = evidence?.rollDegrees,
                subjectNx = evidence?.subject?.x,
                subjectNy = evidence?.subject?.y,
                showGrid = settings.showGrid,
                showSubjectBox = settings.showSubjectBox,
                showTargetZone = settings.showTargetZone,
                showHorizon = settings.showHorizon,
                overlayAlpha = settings.overlayAlpha,
                guideLines = presentation.guideLines,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 对焦框 + 曝光条 ──
        if (showExposureSlider && uiState.isInitialized) {
            val evIndex by cameraViewModel.evIndex.collectAsStateWithLifecycle()
            FocusExposureOverlay(
                focusX = focusCenter.x,
                focusY = focusCenter.y,
                evIndex = evIndex,
                evRange = cameraViewModel.evRange(),
                onEVChange = { cameraViewModel.setExposureCompensation(it) },
                visible = showExposureSlider
            )
        }

        // ── 构图说明标签（拍照后显示在上 1/3 处，2.5s 自动消失） ──
        AnimatedVisibility(
            visible = compositionLabel != null,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(500))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = compositionLabel ?: "",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // ── 拍后结果确认（不做分数评价） ──
        if (showCaptureResult) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .zIndex(11f)
            )
            CaptureResultSheet(
                imageUri = uiState.latestPhotoUri,
                strategy = renderInput.compositionStrategy,
                sceneType = renderInput.sceneType,
                onDone = {
                    showCaptureResult = false
                    compositionLabel = null
                    renderViewModel.sessionController.resetToIdle()
                },
                onRetake = {
                    showCaptureResult = false
                    compositionLabel = null
                    renderViewModel.sessionController.resetToIdle()
                },
                onShareToCommunity = {
                    showCaptureResult = false
                    compositionLabel = null
                    showPublishSheet = true
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(12f)
                    .navigationBarsPadding()
            )
        }

        if (showTakeNow && !uiState.isCapturing && !showCaptureResult) {
            Button(
                onClick = { captureNow() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(10f)
                    .padding(top = 68.dp, end = 12.dp)
                    .widthIn(max = 240.dp)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandPink,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("按当前画面立即拍摄", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "4 秒后自动收起 · 再按快门也可以",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        captureError?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(20.dp))
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
                    onDescriptionChange = { publishDescription = it },
                    onPublish = {
                        val imageKey = uiState.latestPhotoUri?.toString()
                        val body = publishDescription.takeIf { it.isNotBlank() }
                            ?: "AI 构图作品"
                        communityViewModel.publishPost(
                            author = currentUser,
                            body = body,
                            imageAssetKey = imageKey,
                            elapsedText = "刚刚"
                        ) {
                            showPublishSheet = false
                            publishDescription = ""
                            renderViewModel.sessionController.resetToIdle()
                            onNavigateToCommunity()
                            Toast.makeText(context, "已发布到社区", Toast.LENGTH_SHORT).show()
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

/** 将统一会话控制器状态映射到 UI Overlay 所需的状态枚举 */
private fun mapSessionState(
    state: CompositionSessionController.State,
    hasRealSubject: Boolean = false,
    expectsSubject: Boolean = false
): CaptureWorkflowState = when (state) {
    is CompositionSessionController.State.Idle -> CaptureWorkflowState.IDLE
    is CompositionSessionController.State.Analyzing -> CaptureWorkflowState.ANALYZING
    is CompositionSessionController.State.Adjusting -> {
        when (state.action) {
            is CompositionSessionController.GuidanceAction.RotateClockwise,
            is CompositionSessionController.GuidanceAction.RotateCounterClockwise,
            is CompositionSessionController.GuidanceAction.PitchUp,
            is CompositionSessionController.GuidanceAction.PitchDown,
            is CompositionSessionController.GuidanceAction.YawLeft,
            is CompositionSessionController.GuidanceAction.YawRight ->
                CaptureWorkflowState.ROTATING
            is CompositionSessionController.GuidanceAction.DeviceUpsideDown ->
                CaptureWorkflowState.UPSIDE_DOWN
            is CompositionSessionController.GuidanceAction.HoldStill -> {
                // 期望主体但没有真实主体 → 提示用户放入主体
                if (expectsSubject && !hasRealSubject) {
                    CaptureWorkflowState.NO_SUBJECT
                } else {
                    CaptureWorkflowState.GUIDING
                }
            }
            else -> CaptureWorkflowState.GUIDING
        }
    }
    is CompositionSessionController.State.Verifying -> CaptureWorkflowState.GUIDING
    is CompositionSessionController.State.Ready -> CaptureWorkflowState.READY
    is CompositionSessionController.State.Capturing -> CaptureWorkflowState.CAPTURING
    is CompositionSessionController.State.Saved -> CaptureWorkflowState.SAVED
    is CompositionSessionController.State.Error -> CaptureWorkflowState.IDLE
}

/**
 * AI 引擎状态胶囊 — 让用户一眼确认 AI 是否工作。
 *
 * READY（V9 推理在线）→ 绿点「AI 已就绪 · V9」
 * COPY/LOAD/IDLE      → 灰点「AI 引擎加载中…」
 * FAILED（ONNX 加载失败，本地引导兜底）→ 黄点「AI 演示模式」
 */
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
            // ★ Release 隐藏内部推理引擎名称（V9-ONNX/Fake）
            val name = if (com.example.aicamera.BuildConfig.DEBUG) inferenceType else "AI"
            GuideAligned to "AI 已就绪 · $name"
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
// 顶部显示自动识别结果；拍摄模式由有效观测决定，不接受用户覆盖。
// ────────────────────────────────────────────────────────────────

@Composable
private fun CameraTopBar(
    modifier: Modifier = Modifier,
    family: SceneFamily,
    onSettingsClick: () -> Unit,
) {
    val label = when (family) {
        SceneFamily.PORTRAIT -> "自动 · 人像"
        SceneFamily.FOOD, SceneFamily.STILL_LIFE, SceneFamily.PLANT -> "自动 · 静物"
        SceneFamily.NATURAL_LANDSCAPE, SceneFamily.URBAN_LANDSCAPE -> "自动 · 风景"
        SceneFamily.UNKNOWN -> "自动 · 识别中"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CameraChrome)
            .statusBarsPadding()
            .height(40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (family == SceneFamily.UNKNOWN) CameraControlMuted else CameraOnChrome,
            fontSize = 13.sp,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "相机设置",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
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
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit,
    isCapturing: Boolean,
    aligned: Boolean,
    proEnabled: Boolean,
    onTogglePro: () -> Unit,
    zoomRange: Pair<Float, Float>
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CameraChrome.copy(alpha = 0.6f))
            .padding(bottom = if (compact) 2.dp else 8.dp)
    ) {
        // ── 上层：缩略图 / 快门 / 翻转 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 22.dp else 36.dp,
                    vertical = if (compact) 4.dp else 10.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ① 最新照片缩略图 → App 内 Gallery
            Box(
                modifier = Modifier
                    .size(if (compact) 42.dp else 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenGallery
                    )
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                if (latestPhotoUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(latestPhotoUri)
                            .crossfade(true)
                            .size(96)
                            .build(),
                        contentDescription = "最新照片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else Box(Modifier.fillMaxSize().background(Color.Black))
            }

            // ② 快门（GenWow 规范：白外环 + 粉内圆，对齐时外环变绿）
            CaptureButton(
                aligned = aligned,
                enabled = !isCapturing,
                compact = compact,
                onClick = onTakePhoto
            )

            // ③ 翻转摄像头（队友 GenWow Canvas glyph）
            Box(
                modifier = Modifier
                    .size(if (compact) 42.dp else 48.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggleCamera)
                    .semantics { contentDescription = "切换前后摄像头" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = null,
                    tint = CameraControlMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // ── 下层：变焦档位（GenWow ZoomStrip）+ PRO 开关 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 18.dp else 30.dp,
                    vertical = if (compact) 1.dp else 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            ZoomStrip(
                zoomRatio = zoomRatio,
                zoomRange = zoomRange,
                onZoomChange = onZoomChange,
                modifier = Modifier.offset(x = (-6).dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // PRO 模式开关（不影响 AI 构图：只控制手动参数面板显隐）
            Box(
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (proEnabled) BrandPink else Color.White.copy(alpha = 0.06f))
                    .clickable(onClick = onTogglePro)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "PRO",
                    color = if (proEnabled) CameraChrome else CameraOnChrome.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * GenWow 快门按钮（队友 CameraControls.kt CaptureButton 规范）：
 * 78dp 触控区 / 72dp 画布；2.8dp 外环（对齐时绿色）+ 白色 16% 中环 +
 * BrandPink 27dp 内圆 + 白色 22% 核心；按下缩放 0.92。
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
            .size(if (compact) 64.dp else 78.dp)
            .scale(scale)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(if (compact) 58.dp else 72.dp)) {
            val ringColor = if (aligned) GuideAligned else Color.White
            // 外环
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 1.4.dp.toPx(),
                style = Stroke(width = 2.8.dp.toPx())
            )
            // 白色 16% 中环
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = size.minDimension / 2f - 7.dp.toPx()
            )
            // 粉色内圆 + 白色 22% 核心
            val innerRadius = if (compact) 22.dp.toPx() else 27.dp.toPx()
            drawCircle(color = BrandPink, radius = innerRadius)
            drawCircle(color = Color.White.copy(alpha = 0.22f), radius = innerRadius)
        }
    }
}

/** 1.0×–3.0× 连续滑动变焦，按 0.1× 吸附。 */
@Composable
private fun ZoomStrip(
    zoomRatio: Float,
    zoomRange: Pair<Float, Float>,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val minZoom = maxOf(1f, zoomRange.first)
    val maxZoom = minOf(3f, zoomRange.second).coerceAtLeast(minZoom)
    val snappedZoom = zoomRatio.coerceIn(minZoom, maxZoom)
    val intervalCount = (((maxZoom - minZoom) * 10f).toInt()).coerceAtLeast(1)
    Column(
        modifier = modifier
            .width(220.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${"%.1f".format(minZoom)}×", color = CameraOnChrome.copy(alpha = 0.55f), fontSize = 9.sp)
            Text("${"%.1f".format(snappedZoom)}×", color = BrandPink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("${"%.1f".format(maxZoom)}×", color = CameraOnChrome.copy(alpha = 0.55f), fontSize = 9.sp)
        }
        Slider(
            value = snappedZoom,
            onValueChange = {
                val snapped = (kotlin.math.round(it * 10f) / 10f).coerceIn(minZoom, maxZoom)
                onZoomChange(snapped)
            },
            valueRange = minZoom..maxZoom,
            steps = (intervalCount - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = BrandPink,
                activeTrackColor = BrandPink,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
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
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandPink,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("发布", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
