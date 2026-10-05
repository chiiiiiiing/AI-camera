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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    targetNx: Float? = null,
    targetNy: Float? = null,
    horizonAngleDeg: Float? = null,
    /** 真实主体中心点（归一化），null = 无真实主体，箭头从画面中心出发 */
    subjectNx: Float? = null,
    subjectNy: Float? = null,
    showGrid: Boolean = true,
    showSubjectBox: Boolean = true,
    showTargetZone: Boolean = true,
    showHorizon: Boolean = true,
    overlayAlpha: Float = 1f,
    guideLines: List<GuideLine> = emptyList(),
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state in listOf(
                CaptureWorkflowState.ANALYZING, CaptureWorkflowState.ROTATING,
                CaptureWorkflowState.GUIDING, CaptureWorkflowState.READY,
                CaptureWorkflowState.NO_SUBJECT, CaptureWorkflowState.UPSIDE_DOWN
            )) {
            WorkflowGuideLines(state, cue, horizonAngleDeg, guideLines,
                showGrid, showSubjectBox, showTargetZone, showHorizon, overlayAlpha)
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
            if (targetNx != null && targetNy != null) {
                CenterPointGuidance(
                    targetNx = targetNx, targetNy = targetNy,
                    subjectNx = subjectNx, subjectNy = subjectNy,
                    aligned = phase == GuidancePhase.ALIGNED ||
                        cue == GuidanceCue.SUBJECT_ALIGNED
                )
            } else {
                FallbackGuidance(cue = cue, phase = phase)
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
    showTargetZone: Boolean, showHorizon: Boolean, overlayAlpha: Float
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
        if (state in listOf(CaptureWorkflowState.GUIDING, CaptureWorkflowState.READY)) {
            guideLines.filter { it.visible }.forEach { line ->
                when (line.type) {
                    GuideLineType.SUBJECT_BOX -> if (showSubjectBox) {
                        val box = GuideLineParser.parseSubjectBox(line.params)
                        drawRoundRect(
                            color = (if (box.aligned) GuideAligned else BrandPink).copy(alpha = opacity),
                            topLeft = Offset(box.x * size.width, box.y * size.height),
                            size = Size(box.width * size.width, box.height * size.height),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    GuideLineType.TARGET_ZONE -> if (showTargetZone) {
                        val zone = GuideLineParser.parseTargetZone(line.params)
                        drawCircle(Color.White.copy(alpha = opacity * 0.8f),
                            zone.radius * size.minDimension,
                            Offset(zone.centerX * size.width, zone.centerY * size.height),
                            style = Stroke(width = 2.dp.toPx()))
                    }
                    else -> Unit
                }
            }
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
    }
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
    subjectNx: Float? = null, subjectNy: Float? = null,
    aligned: Boolean
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

    val sxNorm = subjectNx ?: 0.5f
    val syNorm = subjectNy ?: 0.5f
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = sxNorm * size.width
            val sy = syNorm * size.height
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

            // ── 箭头三角（指向构图目标） ──
            val dx = tx - sx
            val dy = ty - sy
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > 1f) {
                val ux = dx / len
                val uy = dy / len
                val arrowSize = 14.dp.toPx()
                val tip = Offset(tx - ux * 6.dp.toPx(), ty - uy * 6.dp.toPx())
                val left = Offset(
                    tip.x - ux * arrowSize + uy * arrowSize * 0.45f,
                    tip.y - uy * arrowSize - ux * arrowSize * 0.45f
                )
                val right = Offset(
                    tip.x - ux * arrowSize - uy * arrowSize * 0.45f,
                    tip.y - uy * arrowSize + ux * arrowSize * 0.45f
                )
                val arrowPath = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                }
                drawPath(arrowPath, lineColor)
            }

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

        // 底部提示
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (aligned) "位置已对齐" else "沿着箭头方向移动手机",
                color = if (aligned) GuideAligned else BrandPink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** fallback：无 AI 目标位置时用简洁文字 */
@Composable
private fun FallbackGuidance(cue: GuidanceCue?, phase: GuidancePhase) {
    val hint = when {
        phase == GuidancePhase.ALIGNED -> "构图就绪"
        phase == GuidancePhase.NEAR -> "接近中..."
        phase == GuidancePhase.WRONG_DIRECTION -> "方向相反，回一点"
        else -> "调整构图"
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

/** 指引配色 — 队友 GenWow 2D 扁平风格：不同维度用不同颜色，便于用户辨识 */
private val GuideTargetPurple = Color(0xFFB76EFF)
private val GuideArrowPurple = Color(0xFFB76EFF).copy(alpha = 0.85f)
private val GuideTranslationAccent = Color(0xFF4ECDC4)
private val GuideTranslationAccentAlpha = Color(0xFF4ECDC4).copy(alpha = 0.78f)

@Composable
private fun RotatingOverlay(horizonAngleDeg: Float?, cue: GuidanceCue?) {
    val isRoll = cue == GuidanceCue.ROLL_CLOCKWISE || cue == GuidanceCue.ROLL_COUNTERCLOCKWISE
    val isPitch = cue == GuidanceCue.PITCH_UP || cue == GuidanceCue.PITCH_DOWN
    val isYaw = cue == GuidanceCue.YAW_LEFT || cue == GuidanceCue.YAW_RIGHT

    val rollAligned = horizonAngleDeg != null &&
            kotlin.math.abs(horizonAngleDeg) <= ROTATION_TOLERANCE_DEG

    val (title, subtitle, color) = when {
        isRoll -> when {
            rollAligned -> Triple("水平已对齐", "保持当前角度", GuideAligned)
            cue == GuidanceCue.ROLL_CLOCKWISE -> Triple("调整水平", "顺时针旋转手机，让粉线贴合白色虚线", BrandPink)
            else -> Triple("调整水平", "逆时针旋转手机，让粉线贴合白色虚线", BrandPink)
        }
        isPitch -> Triple(
            "调整俯仰",
            if (cue == GuidanceCue.PITCH_UP) "抬高手机镜头" else "降低手机镜头",
            BrandPink
        )
        isYaw -> Triple(
            "调整方向",
            if (cue == GuidanceCue.YAW_LEFT) "向左转动手机" else "向右转动手机",
            BrandPink
        )
        else -> Triple("调整构图", "", BrandPink)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "第 1 步 · 调整手机姿态",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier.size(300.dp, 146.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.82f),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(26.dp.toPx(), 26.dp.toPx())
                )
            }

            when {
                isPitch -> PitchGuide(cue)
                isYaw -> YawGuide(cue)
                else -> RollGuide(horizonAngleDeg)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = color,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RollGuide(rollDeg: Float?) {
    val aligned = rollDeg != null && kotlin.math.abs(rollDeg) <= ROTATION_TOLERANCE_DEG
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height * 0.46f
            val half = size.width * 0.32f
            val angle = Math.toRadians((rollDeg ?: 0f).coerceIn(-20f, 20f).toDouble())
            val dx = (half * kotlin.math.cos(angle)).toFloat()
            val dy = (half * kotlin.math.sin(angle)).toFloat()
            drawLine(Color.White.copy(alpha = 0.85f), Offset(size.width / 2f - half, cy),
                Offset(size.width / 2f + half, cy), 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
            drawLine(if (aligned) GuideAligned else BrandPink,
                Offset(size.width / 2f - dx, cy - dy), Offset(size.width / 2f + dx, cy + dy),
                4.dp.toPx(), StrokeCap.Round)
        }
        Text("白色虚线＝目标水平  ·  ${if (aligned) "绿线＝已对齐" else if (rollDeg == null) "正在读取水平角度" else "粉线＝当前角度"}",
            color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp))
    }
}

@Composable
private fun PitchGuide(cue: GuidanceCue?) {
    val up = cue == GuidanceCue.PITCH_UP
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val top = size.height * 0.24f
            val bottom = size.height * 0.70f
            drawLine(Color.White.copy(alpha = 0.85f), Offset(cx, top), Offset(cx, bottom),
                3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
            val start = if (up) bottom else top
            val end = if (up) top else bottom
            drawLine(BrandPink, Offset(cx + 28.dp.toPx(), start), Offset(cx + 28.dp.toPx(), end),
                4.dp.toPx(), StrokeCap.Round)
            val sign = if (up) 1f else -1f
            drawLine(BrandPink, Offset(cx + 28.dp.toPx(), end), Offset(cx + 18.dp.toPx(), end + sign * 12.dp.toPx()), 4.dp.toPx())
            drawLine(BrandPink, Offset(cx + 28.dp.toPx(), end), Offset(cx + 38.dp.toPx(), end + sign * 12.dp.toPx()), 4.dp.toPx())
        }
        Text("白色虚线＝目标  ·  粉色箭头＝镜头移动方向", color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp))
    }
}

@Composable
private fun YawGuide(cue: GuidanceCue?) {
    val left = cue == GuidanceCue.YAW_LEFT
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.47f
            val half = size.width * 0.30f
            drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - half, cy), Offset(cx + half, cy),
                3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
            val start = if (left) cx + half * 0.65f else cx - half * 0.65f
            val end = if (left) cx - half * 0.25f else cx + half * 0.25f
            drawLine(BrandPink, Offset(start, cy - 18.dp.toPx()), Offset(end, cy - 18.dp.toPx()),
                4.dp.toPx(), StrokeCap.Round)
            val sign = if (left) 1f else -1f
            drawLine(BrandPink, Offset(end, cy - 18.dp.toPx()), Offset(end + sign * 12.dp.toPx(), cy - 28.dp.toPx()), 4.dp.toPx())
            drawLine(BrandPink, Offset(end, cy - 18.dp.toPx()), Offset(end + sign * 12.dp.toPx(), cy - 8.dp.toPx()), 4.dp.toPx())
        }
        Text("沿粉色箭头转动手机  ·  无需移动身体", color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp))
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
