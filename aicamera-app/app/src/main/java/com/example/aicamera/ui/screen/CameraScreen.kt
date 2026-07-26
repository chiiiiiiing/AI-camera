package com.example.aicamera.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.camera.CameraGestureDispatcher
import com.example.aicamera.guidance.AlignmentFlashOverlay
import com.example.aicamera.guidance.ComposeGuidanceOverlay
import com.example.aicamera.guidance.GuidanceMessage
import com.example.aicamera.guidance.GuidancePhase
import com.example.aicamera.overlay.CompositionOverlayView
import com.example.aicamera.overlay.model.GuideLineType
import com.example.aicamera.overlay.model.RenderInput
import com.example.aicamera.ui.components.ProModePanel
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.PipelineViewModel
import com.example.aicamera.viewmodel.RenderViewModel

/**
 * 相机拍摄页 — 顶部功能栏 + 中间预览画布 + 底部工具栏
 *
 * ViewModel 由外部传入（在 Activity 层创建，跨 Tab 保持）。
 *
 * ## 布局（层叠顺序）
 * ```
 * Box
 *   ├── PreviewView              (相机实时画面)
 *   ├── CompositionOverlayView   (AI 动态辅助线：目标区/主体框/水平仪/箭头 + 对焦圈)
 *   ├── ComposeGuidanceOverlay   (引导层：静态三分线 + 目标框 + 方向指令动画)
 *   ├── ProModePanel             (可选，Pro 手动控制)
 *   ├── TopBar / BottomBar / HUD / 引导文案
 * ```
 */
@Composable
fun CameraScreen(
    cameraViewModel: CameraViewModel,
    pipelineViewModel: PipelineViewModel,
    renderViewModel: RenderViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    settings: SettingsState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
    val pipelineState by pipelineViewModel.pipelineState.collectAsStateWithLifecycle()
    val aiState by pipelineViewModel.aiState.collectAsStateWithLifecycle()
    val renderStats by renderViewModel.renderStats.collectAsStateWithLifecycle()
    val guidanceState by renderViewModel.guidanceState.collectAsStateWithLifecycle()

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
    var overlayView by remember { mutableStateOf<CompositionOverlayView?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val gestureDispatcher = remember { CameraGestureDispatcher(
        onFocusCircle = { cx, cy -> cameraViewModel.onFocusTapped(cx, cy) },
        onZoomChanged = { /* zoom 状态由 CameraControls 统一管理 */ },
        onFocusLocked = { locked -> cameraViewModel.setFocusLocked(locked) }
    ) }

    // 快门声（按设置开关）
    val shutterSound = remember { MediaActionSound() }
    DisposableEffect(Unit) {
        onDispose { shutterSound.release() }
    }

    // ── 构图达标反馈：进入 ALIGNED 相位瞬间 → 震动 + 绿边闪烁 ──
    // 判定复用 GuidanceStateMachine 滞回（连续 3 帧稳定达标才进 ALIGNED），
    // 只在此刻触发一次；退出 ALIGNED 后重新武装，不会连续轰炸。
    val haptic = LocalHapticFeedback.current
    var showAlignFlash by remember { mutableStateOf(false) }
    var prevGuidancePhase by remember { mutableStateOf(GuidancePhase.NEUTRAL) }
    LaunchedEffect(guidanceState.phase) {
        val entered = guidanceState.phase == GuidancePhase.ALIGNED &&
                prevGuidancePhase != GuidancePhase.ALIGNED
        if (entered) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showAlignFlash = true
        }
        prevGuidancePhase = guidanceState.phase
    }

    // ★ P1-9 修复：AI 推理结果 → 动态辅助线 overlay（修复数据流断裂，
    //   旧代码收到 RenderInput 后什么也不做，AI 产出只停留在 HUD 文字）
    LaunchedEffect(settings.showSubjectBox, settings.showTargetZone, settings.showHorizon) {
        renderViewModel.renderInput.collect { input ->
            overlayView?.update(input.filteredBy(settings), animate = true)
        }
    }

    // 辅助线整体透明度（设置项）
    LaunchedEffect(settings.overlayAlpha) {
        overlayView?.alpha = settings.overlayAlpha
    }

    // 订阅 focusCircle → Overlay 绘制对焦圆圈
    LaunchedEffect(Unit) {
        cameraViewModel.focusCircle.collect { focusData ->
            overlayView?.showFocusCircle(focusData.centerX, focusData.centerY)
        }
    }

    // 订阅拍照结果 → 快门声 + 提示
    LaunchedEffect(Unit) {
        cameraViewModel.captureResult.collect { result ->
            when (result) {
                is com.example.aicamera.camera.CameraCapture.CaptureResult.Success -> {
                    if (settings.soundEnabled) {
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    }
                    Toast.makeText(context, "照片已保存", Toast.LENGTH_SHORT).show()
                }
                is com.example.aicamera.camera.CameraCapture.CaptureResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 管线连接
    LaunchedEffect(uiState.isInitialized) {
        if (uiState.isInitialized && !pipelineViewModel.pipelineState.value.isRunning) {
            pipelineViewModel.connect(cameraViewModel.frameFlow)
            renderViewModel.connect(pipelineViewModel.pipelineResults)
        }
    }

    // ★ 刷新最新照片缩略图（相机就绪后加载一次，拍照后自动更新）
    LaunchedEffect(uiState.isInitialized) {
        if (uiState.isInitialized) {
            cameraViewModel.refreshLatestPhoto()
        }
    }

    // ★ 手势重挂：Camera 实例每次绑定/重绑都会更换（切前后摄、后台恢复、
    //   Pro 参数变更），旧代码只在首次 init 时 attach 一次，之后手势全部
    //   作用在失效的旧 Camera 上。
    LaunchedEffect(uiState.cameraBindVersion) {
        val view = previewView ?: return@LaunchedEffect
        val camera = cameraViewModel.getCamera() ?: return@LaunchedEffect
        gestureDispatcher.detach(view)
        gestureDispatcher.attach(view, camera)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this

                        if (!cameraInitialized) {
                            cameraViewModel.initialize(this, lifecycleOwner) { success ->
                                cameraInitialized = success
                            }
                        }
                    }
                }
            )

            // ── 第 2 层：AI 动态辅助线叠加 (Android Canvas) ──
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CompositionOverlayView(ctx).also { view ->
                        view.alpha = settings.overlayAlpha
                        overlayView = view
                    }
                }
            )

            // ── 第 2.5 层：Compose 引导 Overlay (队友设计规范 - 主引导层) ──
            ComposeGuidanceOverlay(
                state = guidanceState,
                showGrid = settings.showGrid,
                showTargetFrame = settings.showTargetZone,
                modifier = Modifier.fillMaxSize()
            )

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
                modifier = Modifier.fillMaxSize().background(Color.Black),
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

        // ── 第 3 层：顶部功能栏 ──
        if (uiState.isInitialized) {
            CameraTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                onSettingsClick = onNavigateToSettings
            )
        }

        // ── 第 3 层：底部拍摄工具栏 ──
        if (uiState.isInitialized) {
            CameraBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                latestPhotoUri = uiState.latestPhotoUri,
                onTakePhoto = { cameraViewModel.takePhoto(saveToGallery = settings.saveOriginal) },
                onToggleCamera = { cameraViewModel.toggleCamera() },
                onOpenGallery = onNavigateToGallery,
                zoomRatio = uiState.zoomRatio,
                onZoomChange = { cameraViewModel.setZoom(it) },
                isCapturing = uiState.isCapturing,
                flashMode = uiState.flashMode,
                onCycleFlash = { cameraViewModel.cycleFlash() }
            )
        }

        // ── Pro 模式面板（设置中开启） ──
        if (uiState.isInitialized && settings.proModeEnabled) {
            val evIndex by cameraViewModel.evIndex.collectAsStateWithLifecycle()
            val isoValue by cameraViewModel.isoValue.collectAsStateWithLifecycle()
            val awbMode by cameraViewModel.awbMode.collectAsStateWithLifecycle()
            ProModePanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 150.dp),
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
                zoomRatio = uiState.zoomRatio,
                zoomRange = 1f to (cameraViewModel.getCamera()?.cameraInfo?.zoomState?.value?.maxZoomRatio
                    ?: CameraConfig.MAX_ZOOM_RATIO),
                onZoomChange = { cameraViewModel.setZoom(it) },
                onDismiss = { /* 由设置页开关控制显隐 */ }
            )
        }

        // ── 性能 HUD（左上角调试信息） ──
        if (uiState.isInitialized) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 52.dp, start = 12.dp)
            ) {
                Text(
                    text = "场景: ${renderStats.currentScene} (${"%.2f".format(renderStats.confidence)})",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "引擎: ${pipelineState.inferenceType} | AI: ${aiState.status}",
                    color = if (aiState.status == com.example.aicamera.pipeline.AiEngineManager.Status.READY)
                        Color(0xFF69F0AE) else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "帧: ${pipelineState.processedFrames} | 丢弃: ${pipelineState.droppedFrames}",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "推理: ${pipelineState.avgInferenceMs.toInt()}ms | 管线: ${pipelineState.avgPipelineMs.toInt()}ms",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // ── 引导提示文案（底部居中） ──
        if (uiState.isInitialized) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp)) {
                GuidanceMessage(state = guidanceState, isDemo = false)
            }
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

/**
 * 按设置过滤辅助线（设置页的 主体框/目标区/水平仪 开关在这里生效）。
 */
private fun RenderInput.filteredBy(s: SettingsState): RenderInput {
    val filtered = guideLines.filter { line ->
        when (line.type) {
            GuideLineType.SUBJECT_BOX -> s.showSubjectBox
            GuideLineType.TARGET_ZONE -> s.showTargetZone
            GuideLineType.HORIZON_LEVEL -> s.showHorizon
            else -> true
        }
    }
    return copy(guideLines = filtered)
}

// ────────────────────────────────────────────────────────────────
// 顶部功能栏
// ────────────────────────────────────────────────────────────────

@Composable
private fun CameraTopBar(
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // ① 相册
        IconButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "相册", tint = Color.White)
        }
        // ② 分享
        IconButton(onClick = { /* TODO: 分享面板 */ }) {
            Icon(Icons.Default.Share, contentDescription = "分享", tint = Color.White)
        }
        // ③ 个人主页
        IconButton(onClick = { /* TODO: 跳转个人 Tab */ }) {
            Icon(Icons.Default.Person, contentDescription = "个人", tint = Color.White)
        }
        // ④ 设置
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

// ────────────────────────────────────────────────────────────────
// 底部拍摄工具栏
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
    flashMode: CameraConfig.FlashMode,
    onCycleFlash: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(bottom = 8.dp)
    ) {
        // ── 上层：拍摄操作栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ① 左侧：真实照片缩略图入口（Coil 加载）→ 点击打开 App 内 Gallery
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenGallery
                    )
                    .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (latestPhotoUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(latestPhotoUri)
                            .crossfade(true)
                            .size(80)
                            .build(),
                        contentDescription = "最新照片",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = "相册",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ② 中间：快门按钮
            Button(
                onClick = onTakePhoto,
                enabled = !isCapturing,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B9D),
                    disabledContainerColor = Color.Gray
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0xFFFF6B9D), CircleShape)
                    )
                }
            }

            // ③ 右侧：翻转摄像头
            IconButton(onClick = onToggleCamera) {
                Icon(
                    Icons.Default.FlipCameraAndroid,
                    contentDescription = "翻转摄像头",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // ── 变焦条 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("W", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Slider(
                modifier = Modifier
                    .width(120.dp)
                    .padding(horizontal = 8.dp),
                value = zoomRatio,
                onValueChange = onZoomChange,
                valueRange = 1f..4f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White
                )
            )
            Text("T", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)

            Spacer(modifier = Modifier.width(16.dp))

            // 闪光灯快捷切换
            IconButton(onClick = onCycleFlash) {
                val flashIcon = when (flashMode) {
                    CameraConfig.FlashMode.AUTO -> Icons.Default.FlashAuto
                    CameraConfig.FlashMode.ON -> Icons.Default.FlashOn
                    CameraConfig.FlashMode.OFF -> Icons.Default.FlashOff
                    CameraConfig.FlashMode.TORCH -> Icons.Default.FlashlightOn
                }
                val flashTint = when (flashMode) {
                    CameraConfig.FlashMode.AUTO -> Color.White.copy(alpha = 0.7f)
                    CameraConfig.FlashMode.ON -> Color(0xFFFFD700)
                    CameraConfig.FlashMode.OFF -> Color.White.copy(alpha = 0.4f)
                    CameraConfig.FlashMode.TORCH -> Color(0xFFFFD700)
                }
                Icon(
                    imageVector = flashIcon,
                    contentDescription = "闪光灯: $flashMode",
                    tint = flashTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
