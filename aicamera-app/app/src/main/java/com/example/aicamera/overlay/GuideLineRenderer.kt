package com.example.aicamera.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.example.aicamera.overlay.model.GridParams
import com.example.aicamera.overlay.model.HorizonParams
import com.example.aicamera.overlay.model.SubjectBoxParams
import com.example.aicamera.overlay.model.TargetZoneParams

/**
 * 辅助线渲染器 — 所有具体的 Canvas 绘制逻辑。
 *
 * ★ 所有需要坐标映射的辅助线统一经过 [CoordinateMapper]，
 *   处理前摄镜像和旋转。密度参数由调用方传入。
 */
object GuideLineRenderer {

    // ── Paint 缓存 ───────────────────────────────────────────

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val subjectBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val targetZoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val targetZoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val horizonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val horizonPointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** ★ 缓存虚线 Paint，避免每帧分配 */
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    /** ★ 缓存可复用 Path */
    private val reusablePath = Path()

    // ── 三分线网格 ────────────────────────────────────────────

    fun drawGrid(
        canvas: Canvas,
        params: GridParams,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean = false,
        density: Float = 3.0f
    ) {
        gridPaint.color = RenderConfig.GRID_LINE_COLOR
        gridPaint.strokeWidth = 1f * density
        gridPaint.pathEffect = null

        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()

        for (vLine in params.verticalLines) {
            val x = if (isFrontCamera) (1f - vLine) * w else vLine * w
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        for (hLine in params.horizontalLines) {
            val y = hLine * h
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    // ── 三分点标记 ────────────────────────────────────────────

    fun drawThirdsPoints(
        canvas: Canvas,
        params: GridParams,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean = false,
        density: Float = 3.0f
    ) {
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()
        val pointRadius = 3f * density
        val outerRadius = 5f * density

        fillPaint.color = RenderConfig.ACCENT_COLOR

        for (v in params.verticalLines) {
            for (hl in params.horizontalLines) {
                val cx = if (isFrontCamera) (1f - v) * w else v * w
                val cy = hl * h

                fillPaint.alpha = 80
                canvas.drawCircle(cx, cy, outerRadius, fillPaint)
                fillPaint.alpha = 255
                canvas.drawCircle(cx, cy, pointRadius, fillPaint)
            }
        }
    }

    // ── 主体定位框 ────────────────────────────────────────────

    fun drawSubjectBox(
        canvas: Canvas,
        params: SubjectBoxParams,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean = false,
        rotationDegrees: Int = 0,
        pulseAlpha: Float = 1f,
        density: Float = 3.0f
    ) {
        // ★ 使用 CoordinateMapper 映射矩形（★ 修复：rotationDegrees 之前漏传，
        //   与 drawTargetZone 行为不一致——一个旋转一个不旋转）
        val normRect = params.toRectF()
        val rect = CoordinateMapper.mapRect(
            normRect, viewWidth, viewHeight, isFrontCamera, rotationDegrees
        )
        val cornerLen = 12f * density

        subjectBoxPaint.color = RenderConfig.SUBJECT_BOX_COLOR
        subjectBoxPaint.alpha = (200 * pulseAlpha).toInt()
        subjectBoxPaint.strokeWidth = 2f * density
        canvas.drawRoundRect(rect, 6f * density, 6f * density, subjectBoxPaint)

        cornerPaint.color = RenderConfig.SUBJECT_BOX_COLOR
        cornerPaint.alpha = (255 * pulseAlpha).toInt()

        val (l, t, r, b) = listOf(rect.left, rect.top, rect.right, rect.bottom)
        // 左上
        canvas.drawLine(l, t, l + cornerLen, t, cornerPaint)
        canvas.drawLine(l, t, l, t + cornerLen, cornerPaint)
        // 右上
        canvas.drawLine(r, t, r - cornerLen, t, cornerPaint)
        canvas.drawLine(r, t, r, t + cornerLen, cornerPaint)
        // 左下
        canvas.drawLine(l, b, l + cornerLen, b, cornerPaint)
        canvas.drawLine(l, b, l, b - cornerLen, cornerPaint)
        // 右下
        canvas.drawLine(r, b, r - cornerLen, b, cornerPaint)
        canvas.drawLine(r, b, r, b - cornerLen, cornerPaint)
    }

    // ── 目标区域 ──────────────────────────────────────────────

    fun drawTargetZone(
        canvas: Canvas,
        params: TargetZoneParams,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean = false,
        rotationDegrees: Int = 0,
        pulseAlpha: Float = 1f,
        pulseScale: Float = 1f,
        density: Float = 3.0f
    ) {
        // ★ 使用 CoordinateMapper 映射中心点
        val center = CoordinateMapper.mapPoint(
            params.centerX, params.centerY,
            viewWidth, viewHeight, isFrontCamera, rotationDegrees
        )
        val radius = CoordinateMapper.mapRadius(params.radius, viewWidth, viewHeight) * pulseScale

        targetZoneFillPaint.color = RenderConfig.TARGET_ZONE_COLOR
        targetZoneFillPaint.alpha = (60 * pulseAlpha).toInt()
        canvas.drawCircle(center.x, center.y, radius, targetZoneFillPaint)

        targetZoneStrokePaint.color = RenderConfig.TARGET_ZONE_COLOR
        targetZoneStrokePaint.alpha = (180 * pulseAlpha).toInt()
        targetZoneStrokePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(center.x, center.y, radius, targetZoneStrokePaint)

        val crossLen = radius * 0.4f
        targetZoneStrokePaint.alpha = (200 * pulseAlpha).toInt()
        canvas.drawLine(center.x - crossLen, center.y, center.x + crossLen, center.y, targetZoneStrokePaint)
        canvas.drawLine(center.x, center.y - crossLen, center.x, center.y + crossLen, targetZoneStrokePaint)
    }

    // ── 水平仪 ────────────────────────────────────────────────

    fun drawHorizonLevel(
        canvas: Canvas,
        params: HorizonParams,
        viewWidth: Int,
        viewHeight: Int,
        density: Float = 3.0f
    ) {
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()

        // ★ 水平参考线（来自模板 horizon_position，修复 P1-6：该参数曾被静默丢弃）
        params.referenceY?.let { refY ->
            dashPaint.color = RenderConfig.HORIZON_BG_COLOR
            dashPaint.alpha = 160
            dashPaint.strokeWidth = 1.2f * density
            dashPaint.pathEffect = DashPathEffect(
                floatArrayOf(10f * density, 8f * density), 0f
            )
            val y = refY * h
            canvas.drawLine(0f, y, w, y, dashPaint)
            dashPaint.pathEffect = null
        }

        val cx = w / 2f
        val cy = h * 0.85f
        val radius = 36f * density
        val angle = params.angleDegrees

        horizonBgPaint.color = RenderConfig.HORIZON_BG_COLOR
        horizonBgPaint.strokeWidth = 2.5f * density
        horizonBgPaint.style = Paint.Style.STROKE
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(oval, -30f, 60f, false, horizonBgPaint)

        horizonBgPaint.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(cx, cy - radius, cx, cy - radius + 8f * density, horizonBgPaint)

        horizonPointerPaint.color = when {
            kotlin.math.abs(angle) < 1.0f -> RenderConfig.HORIZON_GREEN
            kotlin.math.abs(angle) < 3.0f -> RenderConfig.HORIZON_YELLOW
            else -> RenderConfig.HORIZON_RED
        }
        horizonPointerPaint.strokeWidth = 3f * density

        val rad = Math.toRadians(angle.toDouble())
        val pointerLen = radius * 0.75f
        val endX = cx + pointerLen * kotlin.math.sin(rad).toFloat()
        val endY = cy - pointerLen * kotlin.math.cos(rad).toFloat()
        canvas.drawLine(cx, cy, endX, endY, horizonPointerPaint)

        fillPaint.color = horizonPointerPaint.color
        fillPaint.alpha = 255
        canvas.drawCircle(endX, endY, 3f * density, fillPaint)
    }

    // ── 方向引导箭头 ──────────────────────────────────────────

    fun drawDirectionArrow(
        canvas: Canvas,
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        viewWidth: Int, viewHeight: Int,
        flowOffset: Float = 0f,
        density: Float = 3.0f
    ) {
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()
        val start = PointF(fromX * w, fromY * h)
        val end = PointF(toX * w, toY * h)

        // ★ 复用缓存 Paint
        dashPaint.color = RenderConfig.ACCENT_COLOR
        dashPaint.alpha = 160
        dashPaint.strokeWidth = 2f * density
        dashPaint.pathEffect = DashPathEffect(
            floatArrayOf(8f * density, 6f * density),
            -flowOffset * 14f * density
        )
        canvas.drawLine(start.x, start.y, end.x, end.y, dashPaint)

        arrowFillPaint.color = RenderConfig.ACCENT_COLOR
        arrowFillPaint.alpha = (180 + 75 * flowOffset).toInt()

        val angle = CoordinateMapper.angle(start, end)
        val arrowLen = 10f * density
        val arrowAngle = 0.5f

        // ★ 复用 Path
        reusablePath.reset()
        reusablePath.apply {
            moveTo(end.x, end.y)
            lineTo(
                end.x - arrowLen * kotlin.math.cos(angle - arrowAngle),
                end.y - arrowLen * kotlin.math.sin(angle - arrowAngle)
            )
            lineTo(
                end.x - arrowLen * kotlin.math.cos(angle + arrowAngle),
                end.y - arrowLen * kotlin.math.sin(angle + arrowAngle)
            )
            close()
        }
        canvas.drawPath(reusablePath, arrowFillPaint)
    }

    // ── 引导线路径 ────────────────────────────────────────────

    fun drawGuideLinePath(
        canvas: Canvas,
        points: FloatArray,
        viewWidth: Int,
        viewHeight: Int,
        flowOffset: Float = 0f,
        density: Float = 3.0f
    ) {
        if (points.size < 4) return

        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()

        dashPaint.color = RenderConfig.GUIDE_LINE_COLOR
        dashPaint.alpha = 200
        dashPaint.strokeWidth = 2f * density
        dashPaint.pathEffect = DashPathEffect(
            floatArrayOf(10f * density, 6f * density),
            -flowOffset * 16f * density
        )

        // ★ 复用 Path
        reusablePath.reset()
        reusablePath.moveTo(points[0] * w, points[1] * h)
        var i = 2
        while (i + 1 < points.size) {
            reusablePath.lineTo(points[i] * w, points[i + 1] * h)
            i += 2
        }
        canvas.drawPath(reusablePath, dashPaint)
    }
}

// ── 渲染配置 ──────────────────────────────────────────────────

object RenderConfig {
    val GRID_LINE_COLOR: Int = Color.argb(80, 255, 255, 255)
    val SUBJECT_BOX_COLOR: Int = Color.rgb(0, 200, 255)
    val TARGET_ZONE_COLOR: Int = Color.rgb(255, 107, 53)
    val ACCENT_COLOR: Int = Color.rgb(255, 255, 255)
    val GUIDE_LINE_COLOR: Int = Color.rgb(255, 215, 0)
    val HORIZON_BG_COLOR: Int = Color.argb(100, 255, 255, 255)
    val HORIZON_GREEN: Int = Color.rgb(0, 230, 118)
    val HORIZON_YELLOW: Int = Color.rgb(255, 234, 0)
    val HORIZON_RED: Int = Color.rgb(255, 23, 68)
}
