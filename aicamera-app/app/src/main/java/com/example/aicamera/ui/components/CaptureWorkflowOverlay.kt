package com.example.aicamera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.guidance.GuidancePhase
import com.example.aicamera.guidance.geometry.HorizonPolicy
import com.example.aicamera.overlay.model.GuideLine
import com.example.aicamera.overlay.model.GuideLineParser
import com.example.aicamera.overlay.model.GuideLineType
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.BrandPurple
import com.example.aicamera.ui.theme.GuideAligned
import com.example.aicamera.ui.theme.GuidePrimary
import com.example.aicamera.ui.theme.GuideWarning

/**
 * 构图-拍摄工作流状态。
 *
 * IDLE      → 正常预览
 * ANALYZING → 快门按下后正在分析画面（波纹 + 文字，自动缩到最小焦段）
 * ROTATING  → 分析完成，显示旋转指引 → 用户端平手机
 * GUIDING   → 旋转到位：双点连线指引 → 用户平移对齐
 * READY     → 对齐完成，自动变焦，显示构图说明，自动拍照
 */
enum class CaptureWorkflowState {
    IDLE, ANALYZING, ROTATING, GUIDING, READY, CAPTURING, SAVED, UPSIDE_DOWN, NO_SUBJECT
}

/**
 * 构图-拍摄工作流叠加层。
 *
 * ## 视觉
 * - ANALYZING：粉紫渐变波纹从画面中下部扩散 + "正在分析构图，请保持画面稳定"
 * - GUIDING：画面中心粉色圆点 ─ 虚线箭头 → 构图目标圆点（白/绿），指引平移
 * - READY：绿色脉冲 ✓ + 构图说明
 *
 * @param targetNx / targetNy 构图目标在归一化坐标 (0-1) 中的位置；
 *          null 表示 AI 未输出目标位置，沿用旧箭头文字模式。
 */
@Composable
fun CaptureWorkflowOverlay(
    state: CaptureWorkflowState,
    cue: GuidanceCue?,
    phase: GuidancePhase,
    feedbackHint: String? = null,
    targetNx: Float? = null,
    targetNy: Float? = null,
    horizonAngleDeg: Float? = null,
    /** 真实主体中心点（归一化），null 时不绘制主体或位置连线。 */
    subjectNx: Float? = null,
    subjectNy: Float? = null,
    showGrid: Boolean = true,
    showSubjectBox: Boolean = true,
    showTargetZone: Boolean = true,
    showHorizon: Boolean = true,
    overlayAlpha: Float = 1f,
    guideLines: List<GuideLine> = emptyList(),
    /** 人像 / 自拍模式：把主体框画成虚线人形轮廓（姿势引导），而不是矩形。 */
    poseGuide: Boolean = false,
    /** 底部工具栏高度，用于抬高底部对齐的引导文案，避免被工具栏遮挡。 */
    bottomBarHeight: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // IDLE 也绘制辅助线：AI/几何检测到的目标区、主体框应当在取景时就能看到，
        // 而不是必须按下快门进入引导后才出现（否则用户会觉得 AI 没有任何作用）。
        if (state != CaptureWorkflowState.CAPTURING && state != CaptureWorkflowState.SAVED) {
            WorkflowGuideLines(state, cue, horizonAngleDeg, guideLines,
                showGrid, showSubjectBox, showTargetZone, showHorizon, overlayAlpha, poseGuide)
        }
        if (state != CaptureWorkflowState.IDLE) {
            WorkflowProgress(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 108.dp)
            )
        }

        // ── 阶段 1：分析中 ──
        AnimatedVisibility(
            visible = state == CaptureWorkflowState.ANALYZING,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300))
        ) {
            AnalyzingOverlay()
        }

        // ── 阶段 2：手动旋转 ──
        AnimatedVisibility(
            visible = state == CaptureWorkflowState.ROTATING,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            RotatingOverlay(horizonAngleDeg = horizonAngleDeg, cue = cue)
        }

        // ── 阶段 2.5：设备倒置 ──
        AnimatedVisibility(
            visible = state == CaptureWorkflowState.UPSIDE_DOWN,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            UpsideDownOverlay()
        }

        // ── 阶段 3：中心点平移指引 ──
        AnimatedVisibility(
            visible = state == CaptureWorkflowState.GUIDING,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            if (targetNx != null && targetNy != null && subjectNx != null && subjectNy != null) {
                CenterPointGuidance(
                    targetNx = targetNx, targetNy = targetNy,
                    subjectNx = subjectNx, subjectNy = subjectNy,
                    cue = cue,
                    feedbackHint = feedbackHint,
                    aligned = phase == GuidancePhase.ALIGNED ||
                        cue == GuidanceCue.SUBJECT_ALIGNED,
                    bottomBarHeight = bottomBarHeight
                )
            } else {
                FallbackGuidance(cue = cue, phase = phase, feedbackHint = feedbackHint)
            }
        }

        // ── 阶段 4：就绪 ──
        AnimatedVisibility(
            visible = state == CaptureWorkflowState.READY,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            ReadyOverlay(targetNx = targetNx, targetNy = targetNy)
        }

        AnimatedVisibility(
            visible = state == CaptureWorkflowState.CAPTURING,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180))
        ) {
            CaptureStatusOverlay(saved = false)
        }

        AnimatedVisibility(
            visible = state == CaptureWorkflowState.SAVED,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(260))
        ) {
            CaptureStatusOverlay(saved = true)
        }

        // ── 阶段 5：未识别到主体 ──
        AnimatedVisibility(
            visible = state == CaptureWorkflowState.NO_SUBJECT,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            NoSubjectOverlay()
        }
    }
}

/** The older View overlay is no longer mounted; draw the actual workflow guide lines here. */
@Composable
private fun WorkflowGuideLines(
    state: CaptureWorkflowState, cue: GuidanceCue?, rollDeg: Float?,
    guideLines: List<GuideLine>, showGrid: Boolean, showSubjectBox: Boolean,
    showTargetZone: Boolean, showHorizon: Boolean, overlayAlpha: Float,
    poseGuide: Boolean = false
) {
    Canvas(Modifier.fillMaxSize()) {
        val opacity = overlayAlpha.coerceIn(0.25f, 1f)
        if (showGrid) {
            val gridColor = Color.White.copy(alpha = 0.34f * opacity)
            listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                drawLine(gridColor, Offset(size.width * fraction, 0f),
                    Offset(size.width * fraction, size.height), 1.dp.toPx())
                drawLine(gridColor, Offset(0f, size.height * fraction),
                    Offset(size.width, size.height * fraction), 1.dp.toPx())
            }
        }
        if (state in listOf(
                CaptureWorkflowState.IDLE,
                CaptureWorkflowState.GUIDING,
                CaptureWorkflowState.READY,
                CaptureWorkflowState.NO_SUBJECT,
                CaptureWorkflowState.ANALYZING
            )) {
            guideLines.filter { it.visible }.forEach { line ->
                when (line.type) {
                    GuideLineType.SUBJECT_BOX -> if (showSubjectBox) {
                        val box = GuideLineParser.parseSubjectBox(line.params)
                        // 对齐设计稿「人形虚线轮廓」：改用虚线描边，不再是实线矩形框
                        drawRoundRect(
                            color = (if (box.aligned) GuideAligned else Color.White).copy(alpha = opacity),
                            topLeft = Offset(box.x * size.width, box.y * size.height),
                            size = Size(box.width * size.width, box.height * size.height),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))
                            )
                        )
                    }
                    GuideLineType.TARGET_ZONE -> if (showTargetZone) {
                        // Figma 平移目标：粉色虚线方框 + 中心点
                        val zone = GuideLineParser.parseTargetZone(line.params)
                        val cx = zone.centerX * size.width
                        val cy = zone.centerY * size.height
                        val half = zone.radius * size.minDimension
                        drawRoundRect(
                            color = BrandPink.copy(alpha = opacity),
                            topLeft = Offset(cx - half, cy - half),
                            size = Size(half * 2, half * 2),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                            )
                        )
                        drawCircle(BrandPink.copy(alpha = opacity), 3.dp.toPx(), Offset(cx, cy))
                    }
                    else -> Unit
                }
            }
        }
        // 推荐取景范围：只要进入引导阶段就画出来，不依赖是否存在目标圈。
        // 风景等无主体场景没有 target_zone，但用户仍需要看到"推荐的画面范围"。
        if (showTargetZone &&
            state in listOf(CaptureWorkflowState.GUIDING, CaptureWorkflowState.READY)
        ) {
            val left = size.width * 0.10f
            val top = size.height * 0.12f
            val right = size.width * 0.90f
            val bottom = size.height * 0.80f
            drawRoundRect(
                color = Color.White.copy(alpha = opacity * 0.45f),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(10.dp.toPx(), 8.dp.toPx())
                    )
                )
            )
        }
        if (showHorizon && state == CaptureWorkflowState.ROTATING &&
            cue in listOf(GuidanceCue.ROLL_CLOCKWISE, GuidanceCue.ROLL_COUNTERCLOCKWISE) &&
            rollDeg != null) {
            val center = Offset(size.width / 2f, size.height * 0.46f)
            val half = size.width * 0.37f
            val angle = Math.toRadians(rollDeg.coerceIn(-20f, 20f).toDouble())
            val dx = (half * kotlin.math.cos(angle)).toFloat()
            val dy = (half * kotlin.math.sin(angle)).toFloat()
            drawLine(Color.White.copy(alpha = 0.75f * opacity), Offset(center.x - half, center.y),
                Offset(center.x + half, center.y), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 8.dp.toPx())))
            drawLine((if (kotlin.math.abs(rollDeg) <= ROTATION_TOLERANCE_DEG) GuideAligned else BrandPink).copy(alpha = opacity),
                Offset(center.x - dx, center.y - dy), Offset(center.x + dx, center.y + dy),
                3.dp.toPx(), StrokeCap.Round)
        }

        // 人像 / 自拍姿势引导：画一个虚线人形目标轮廓。
        // 人脸框只框住脸，不能直接当全身轮廓用，所以按画面比例画固定的
        // 人形目标（对齐设计稿「白色虚线人形」），让用户把人放进轮廓里。
        if (poseGuide && state in listOf(
                CaptureWorkflowState.GUIDING,
                CaptureWorkflowState.NO_SUBJECT,
                CaptureWorkflowState.READY
            )) {
            drawPoseTargetSilhouette(
                color = (if (state == CaptureWorkflowState.READY) GuideAligned else Color.White)
                    .copy(alpha = opacity * 0.92f)
            )
        }
    }
}

/** 虚线人形目标轮廓（人像/自拍姿势引导） */
private fun DrawScope.drawPoseTargetSilhouette(color: Color) {
    // 控制轮廓落在取景区内：底部工具栏约占画面下方 20%，
    // 人形轮廓的脚不能画到工具栏下面。
    val h = size.height * 0.46f
    val bottom = size.height * 0.78f
    val topY = bottom - h
    val cx = size.width * 0.5f
    val headR = h * 0.07f
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx()))
    val stroke = Stroke(
        width = 2.dp.toPx(),
        pathEffect = dash,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )

    // 头
    drawCircle(
        color = color,
        radius = headR,
        center = Offset(cx, topY + headR),
        style = stroke
    )

    // 躯干 + 双腿闭合轮廓
    val body = Path().apply {
        moveTo(cx, topY + headR * 2.05f)
        lineTo(cx + h * 0.17f, topY + h * 0.245f)   // 右肩
        lineTo(cx + h * 0.115f, topY + h * 0.56f)   // 右胯
        lineTo(cx + h * 0.075f, bottom)             // 右脚
        lineTo(cx + h * 0.02f, bottom)              // 右脚内侧
        lineTo(cx, topY + h * 0.63f)                // 裆
        lineTo(cx - h * 0.02f, bottom)              // 左脚内侧
        lineTo(cx - h * 0.075f, bottom)             // 左脚
        lineTo(cx - h * 0.115f, topY + h * 0.56f)   // 左胯
        lineTo(cx - h * 0.17f, topY + h * 0.245f)   // 左肩
        close()
    }
    drawPath(body, color, style = stroke)

    // 双臂
    drawLine(
        color,
        Offset(cx + h * 0.17f, topY + h * 0.245f),
        Offset(cx + h * 0.25f, topY + h * 0.53f),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = dash
    )
    drawLine(
        color,
        Offset(cx - h * 0.17f, topY + h * 0.245f),
        Offset(cx - h * 0.25f, topY + h * 0.53f),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = dash
    )
}

@Composable
private fun WorkflowProgress(state: CaptureWorkflowState, modifier: Modifier = Modifier) {
    val activeStep = when (state) {
        CaptureWorkflowState.ANALYZING -> 0
        CaptureWorkflowState.ROTATING,
        CaptureWorkflowState.GUIDING,
        CaptureWorkflowState.NO_SUBJECT,
        CaptureWorkflowState.UPSIDE_DOWN -> 1
        else -> 2
    }
    val finalReady = state == CaptureWorkflowState.READY ||
        state == CaptureWorkflowState.CAPTURING ||
        state == CaptureWorkflowState.SAVED
    val statusText = when (state) {
        CaptureWorkflowState.ANALYZING -> "正在识别主体与构图"
        CaptureWorkflowState.ROTATING -> "第 1 步：调整手机姿态"
        CaptureWorkflowState.GUIDING -> "第 2 步：移动主体到目标"
        CaptureWorkflowState.NO_SUBJECT -> "请先让人物进入画面"
        CaptureWorkflowState.UPSIDE_DOWN -> "请先翻转手机"
        CaptureWorkflowState.READY -> "构图达标，保持稳定"
        CaptureWorkflowState.CAPTURING -> "正在拍摄"
        CaptureWorkflowState.SAVED -> "照片已保存"
        else -> ""
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(220.dp, 22.dp)) {
            val y = size.height / 2f
            val xs = listOf(size.width * 0.12f, size.width * 0.50f, size.width * 0.88f)
            drawLine(
                Color.White.copy(alpha = 0.24f),
                Offset(xs.first(), y),
                Offset(xs.last(), y),
                3.dp.toPx(),
                StrokeCap.Round
            )
            if (activeStep > 0) {
                drawLine(
                    GuideAligned,
                    Offset(xs.first(), y),
                    Offset(xs[activeStep], y),
                    3.dp.toPx(),
                    StrokeCap.Round
                )
            }
            xs.forEachIndexed { index, x ->
                val completed = index < activeStep || (finalReady && index <= activeStep)
                val active = index == activeStep
                drawCircle(
                    color = when {
                        completed -> GuideAligned
                        active -> BrandPink
                        else -> Color.White.copy(alpha = 0.32f)
                    },
                    radius = if (active) 7.dp.toPx() else 5.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
        Text(
            text = statusText,
            color = if (finalReady) GuideAligned else Color.White.copy(alpha = 0.82f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 分析波纹（同上版，保留）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AnalyzingOverlay() {
    val infinite = rememberInfiniteTransition(label = "analyze")
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )
    val ringPhases = remember { listOf(0f, 0.33f, 0.66f) }

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.68f
            val maxR = size.minDimension * 0.55f
            ringPhases.forEach { off ->
                val p = (progress + off) % 1f
                val r = maxR * 0.08f + maxR * 0.92f * p
                val alpha = ((1f - p) * 0.45f).coerceIn(0f, 0.45f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to BrandPink.copy(alpha = alpha),
                        0.7f to BrandPurple.copy(alpha = alpha * 0.4f),
                        1f to Color.Transparent
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "正在分析构图",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "请保持画面稳定",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 中心点连线指引（队友新交付素材参考）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CenterPointGuidance(
    targetNx: Float, targetNy: Float,
    subjectNx: Float, subjectNy: Float,
    cue: GuidanceCue?,
    feedbackHint: String? = null,
    aligned: Boolean,
    bottomBarHeight: Dp = 0.dp
) {
    val infinite = rememberInfiniteTransition(label = "guide_dot")
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_pulse"
    )

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = subjectNx * size.width
            val sy = subjectNy * size.height
            val tx = targetNx * size.width  // 构图目标位置
            val ty = targetNy * size.height

            val lineColor = if (aligned) GuideAligned else BrandPink
            val dotColor = if (aligned) GuideAligned else BrandPink
            val targetColor = if (aligned) GuideAligned else BrandPink

            // ── 虚线/实线箭头：主体中心(或画面中心) → 构图目标 ──
            val dashEffect = PathEffect.dashPathEffect(
                floatArrayOf(18.dp.toPx(), 12.dp.toPx()), 0f
            )
            drawLine(
                color = lineColor,
                start = Offset(sx, sy),
                end = Offset(tx, ty),
                strokeWidth = if (aligned) 3.dp.toPx() else 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (aligned) null else dashEffect
            )

            // ── 主体当前点（或画面中心）: 青绿色/绿色实心 + 呼吸外环 ──
            val centerR = 6.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.18f), centerR * 2.5f, Offset(sx, sy))
            drawCircle(dotColor, centerR, Offset(sx, sy))
            drawCircle(
                dotColor.copy(alpha = pulse * 0.6f),
                centerR * (1f + 0.4f * pulse),
                Offset(sx, sy),
                style = Stroke(width = 1.8.dp.toPx())
            )

            // ── 构图目标点：紫色/绿色大靶心 + 呼吸 ──
            val targetR = 7.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.12f), targetR * 3.5f, Offset(tx, ty))
            drawCircle(targetColor.copy(alpha = pulse), targetR * (1f + 0.3f * pulse), Offset(tx, ty),
                style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(targetColor, targetR * 0.6f, Offset(tx, ty))
        }

        // 底部提示（抬高避开底部工具栏，加半透明深色圆角底提升亮场景可读性）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomBarHeight + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = feedbackHint ?: if (aligned) "位置已对齐，请保持稳定" else phoneMoveHint(cue),
                color = if (aligned) GuideAligned else BrandPink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/** fallback：无 AI 目标位置时用简洁文字 */
private fun phoneMoveHint(cue: GuidanceCue?): String = when (cue) {
    GuidanceCue.MOVE_LEFT -> "手机稍向左移，让脸进入参考圆"
    GuidanceCue.MOVE_RIGHT -> "手机稍向右移，让脸进入参考圆"
    GuidanceCue.MOVE_UP -> "手机稍向上移，让脸进入参考圆"
    GuidanceCue.MOVE_DOWN -> "手机稍向下移，让脸进入参考圆"
    else -> "对照参考位置调整取景，随时可以拍摄"
}

@Composable
private fun FallbackGuidance(cue: GuidanceCue?, phase: GuidancePhase, feedbackHint: String? = null) {
    val hint = feedbackHint ?: when {
        cue in listOf(GuidanceCue.MOVE_LEFT, GuidanceCue.MOVE_RIGHT, GuidanceCue.MOVE_UP, GuidanceCue.MOVE_DOWN) -> phoneMoveHint(cue)
        phase == GuidancePhase.NEAR -> "接近中..."
        phase == GuidancePhase.WRONG_DIRECTION -> "方向相反，回一点"
        else -> "可继续调整取景，随时可以拍摄"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "●",
            color = if (phase == GuidancePhase.ALIGNED) GuideAligned else BrandPink,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = hint, color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

// ═══════════════════════════════════════════════════════════════
// 旋转指引（用同学新图标替代旧弧形手绘代码）
// ═══════════════════════════════════════════════════════════════

/** ROTATING → GUIDING 的旋转容差（度），对齐 HorizonPolicy.enterLevelToleranceDeg */
const val ROTATION_TOLERANCE_DEG = 4.0f  // 与 HorizonPolicy 入口容差一致，用户更易触发 READY

/** Figma 引导配色：水平/俯仰目标线用橙色，当前角度/移动箭头用品牌粉。 */
private val GuideRollAccent = GuideWarning
private val GuideMoveAccent = BrandPink

@Composable
private fun RotatingOverlay(horizonAngleDeg: Float?, cue: GuidanceCue?) {
    val isPitch = cue == GuidanceCue.PITCH_UP || cue == GuidanceCue.PITCH_DOWN
    val isYaw = cue == GuidanceCue.YAW_LEFT || cue == GuidanceCue.YAW_RIGHT
    val rollAligned = horizonAngleDeg != null &&
            kotlin.math.abs(horizonAngleDeg) <= ROTATION_TOLERANCE_DEG

    val (title, subtitle) = when {
        isPitch -> "调整俯仰" to if (cue == GuidanceCue.PITCH_UP) "抬高手机镜头" else "降低手机镜头"
        isYaw -> "调整方向" to if (cue == GuidanceCue.YAW_LEFT) "向左转动手机" else "向右转动手机"
        rollAligned -> "水平已对齐" to "保持当前角度"
        cue == GuidanceCue.ROLL_CLOCKWISE -> "调整水平" to "顺时针旋转手机"
        else -> "调整水平" to "逆时针旋转手机"
    }

    // Figma：引导线直接叠加在预览上，不用深色卡片遮挡画面
    Box(Modifier.fillMaxSize()) {
        when {
            isPitch -> PitchGuide(cue)
            isYaw -> YawGuide(cue)
            else -> RollGuide(horizonAngleDeg)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 188.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = if (rollAligned && !isPitch && !isYaw) GuideAligned else BrandPink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RollGuide(rollDeg: Float?) {
    val aligned = rollDeg != null && kotlin.math.abs(rollDeg) <= ROTATION_TOLERANCE_DEG
    Canvas(Modifier.fillMaxSize()) {
        val cy = size.height * 0.46f
        // Figma：橙色虚线目标水平线横贯预览
        drawLine(
            GuideRollAccent.copy(alpha = 0.95f),
            Offset(0f, cy), Offset(size.width, cy), 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 10.dp.toPx())),
        )
        val half = size.width * 0.23f
        val angle = Math.toRadians((rollDeg ?: 0f).coerceIn(-20f, 20f).toDouble())
        val dx = (half * kotlin.math.cos(angle)).toFloat()
        val dy = (half * kotlin.math.sin(angle)).toFloat()
        drawLine(
            if (aligned) GuideAligned else GuideMoveAccent,
            Offset(size.width / 2f - dx, cy - dy),
            Offset(size.width / 2f + dx, cy + dy),
            4.dp.toPx(), StrokeCap.Round,
        )
        // 旋转方向弧线（真实 IMU 角度决定方向）
        if (rollDeg != null && !aligned) {
            val clockwise = rollDeg < 0f
            val r = 24.dp.toPx()
            val cx = size.width * 0.16f
            val startAngle = if (clockwise) 300f else 240f
            val sweep = if (clockwise) 130f else -130f
            drawArc(
                Color.White, startAngle, sweep, false,
                Offset(cx - r, cy - r), Size(r * 2, r * 2),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
            val endRad = Math.toRadians((startAngle + sweep).toDouble())
            val ex = cx + r * kotlin.math.cos(endRad).toFloat()
            val ey = cy + r * kotlin.math.sin(endRad).toFloat()
            val sign = if (clockwise) 1f else -1f
            drawLine(Color.White, Offset(ex, ey), Offset(ex - 10.dp.toPx(), ey - sign * 12.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, Offset(ex, ey), Offset(ex + 10.dp.toPx(), ey - sign * 4.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun PitchGuide(cue: GuidanceCue?) {
    val up = cue == GuidanceCue.PITCH_UP
    Canvas(Modifier.fillMaxSize()) {
        val cy = size.height * 0.46f
        drawLine(
            GuideRollAccent.copy(alpha = 0.95f),
            Offset(0f, cy), Offset(size.width, cy), 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 10.dp.toPx())),
        )
        val cx = size.width / 2f
        val top = cy - 80.dp.toPx()
        val bottom = cy + 80.dp.toPx()
        val start = if (up) bottom else top
        val end = if (up) top else bottom
        drawLine(GuideMoveAccent, Offset(cx, start), Offset(cx, end), 5.dp.toPx(), StrokeCap.Round)
        val sign = if (up) 1f else -1f
        drawLine(GuideMoveAccent, Offset(cx, end), Offset(cx - 12.dp.toPx(), end + sign * 16.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
        drawLine(GuideMoveAccent, Offset(cx, end), Offset(cx + 12.dp.toPx(), end + sign * 16.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun YawGuide(cue: GuidanceCue?) {
    val left = cue == GuidanceCue.YAW_LEFT
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cy = size.height * 0.46f
        drawLine(
            GuideRollAccent.copy(alpha = 0.95f),
            Offset(0f, cy), Offset(size.width, cy), 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 10.dp.toPx())),
        )
        val cx = size.width / 2f
        val half = size.width * 0.18f
        val start = if (left) cx + half else cx - half
        val end = if (left) cx - half else cx + half
        drawLine(GuideMoveAccent, Offset(start, cy), Offset(end, cy), 5.dp.toPx(), StrokeCap.Round)
        val sign = if (left) -1f else 1f
        drawLine(GuideMoveAccent, Offset(end, cy), Offset(end - sign * 16.dp.toPx(), cy - 12.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
        drawLine(GuideMoveAccent, Offset(end, cy), Offset(end - sign * 16.dp.toPx(), cy + 12.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
    }
}

// ═══════════════════════════════════════════════════════════════
// 设备倒置提示
// ═══════════════════════════════════════════════════════════════

@Composable
private fun UpsideDownOverlay() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(com.example.aicamera.R.drawable.guidance_roll_arrow),
            contentDescription = "手机拿反了",
            modifier = Modifier
                .size(88.dp)
                .rotate(180f),
            colorFilter = ColorFilter.tint(BrandPink),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "手机拿反了",
            color = BrandPink,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "请翻转手机后继续拍摄",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 未识别到主体提示
// ═══════════════════════════════════════════════════════════════

@Composable
private fun NoSubjectOverlay() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(80.dp)) {
            val strokeWidth = 3.dp.toPx()
            val inset = size.width * 0.15f
            val path = Path().apply {
                moveTo(inset, inset)
                lineTo(size.width - inset, inset)
                lineTo(size.width - inset, size.height - inset)
                lineTo(inset, size.height - inset)
                close()
            }
            drawPath(
                path = path,
                color = BrandPink.copy(alpha = 0.85f),
                style = Stroke(width = strokeWidth)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "未识别到主体",
            color = BrandPink,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "请将人物或主体放入画面",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 就绪
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ReadyOverlay(targetNx: Float? = null, targetNy: Float? = null) {
    val infinite = rememberInfiniteTransition(label = "ready")
    val pulse by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // ── 目标取景框：提示用户 AI 建议的构图范围 ──
        if (targetNx != null && targetNy != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = targetNx * size.width
                val cy = targetNy * size.height
                val radius = size.minDimension * 0.18f
                val path = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            left = cx - radius,
                            top = cy - radius,
                            right = cx + radius,
                            bottom = cy + radius
                        )
                    )
                }
                drawPath(
                    path = path,
                    color = GuideAligned.copy(alpha = 0.4f + 0.4f * pulse),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "●",
                color = GuideAligned.copy(alpha = pulse),
                fontSize = 56.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "保持不动 · 即将拍摄",
                color = GuideAligned.copy(alpha = pulse),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CaptureStatusOverlay(saved: Boolean) {
    val transition = rememberInfiniteTransition(label = "capture_status")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "capture_status_pulse"
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = if (saved) 0.18f else 0.28f))
            val statusColor = if (saved) GuideAligned else BrandPink
            drawCircle(statusColor.copy(alpha = 0.18f), 58.dp.toPx() * pulse, center)
            drawCircle(
                color = if (saved) GuideAligned else Color.White,
                radius = 34.dp.toPx(),
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
            if (saved) {
                val check = Path().apply {
                    moveTo(center.x - 15.dp.toPx(), center.y)
                    lineTo(center.x - 4.dp.toPx(), center.y + 12.dp.toPx())
                    lineTo(center.x + 18.dp.toPx(), center.y - 14.dp.toPx())
                }
                drawPath(
                    check,
                    GuideAligned,
                    style = Stroke(
                        width = 5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(112.dp))
            Text(
                text = if (saved) "照片已保存" else "正在拍摄",
                color = if (saved) GuideAligned else Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = if (saved) "正在生成构图结果" else "请保持手机稳定，正在处理照片",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 13.sp
            )
        }
    }
}
