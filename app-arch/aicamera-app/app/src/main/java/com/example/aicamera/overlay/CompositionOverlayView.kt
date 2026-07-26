package com.example.aicamera.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.aicamera.overlay.model.GuideLine
import com.example.aicamera.overlay.model.GuideLineParser
import com.example.aicamera.overlay.model.GuideLineType
import com.example.aicamera.overlay.model.RenderInput

/**
 * 构图辅助线叠加 View — 渲染引擎的核心入口。
 */
class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentInput: RenderInput = RenderInput()
    private val guideLines: List<GuideLine> get() = currentInput.guideLines

    // ★ 注入刷新回调，动画状态变化驱动重绘
    private val animController = AnimationController {
        postInvalidateOnAnimation()
    }

    /** 当前 density（用于 renderer 的 dp→px 转换） */
    private var currentDensity: Float = resources.displayMetrics.density

    // ── 对焦圆圈动画 ──────────────────────────────────────────

    /** ★ 当前对焦圆圈数据（null = 不绘制） */
    private var focusCircle: FocusCircleAnim? = null

    data class FocusCircleAnim(
        val centerX: Float,
        val centerY: Float,
        val startTime: Long = System.currentTimeMillis()
    ) {
        /** 动画总时长（ms） */
        val durationMs: Long = 800L

        /** 动画进度 0.0 ~ 1.0（1.0 后停止绘制） */
        fun progress(): Float {
            val elapsed = System.currentTimeMillis() - startTime
            return (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
        }

        fun isExpired(): Boolean = System.currentTimeMillis() - startTime > durationMs
    }

    // ── 公开 API ──────────────────────────────────────────────

    fun update(input: RenderInput, animate: Boolean = false) {
        if (animate && currentInput.sceneType != input.sceneType) {
            animController.startTransition {
                currentInput = input
                updateAnimationState()
            }
        } else {
            currentInput = input
            updateAnimationState()
        }
        invalidate()
    }

    /**
     * ★ 节电修复：没有可见辅助线时停掉常驻动画（修复空 View 也 60fps
     *   空转 invalidate 的问题）。对焦圈动画不依赖这两个 animator，
     *   由 showFocusCircle/onDraw 自驱动，不受影响。
     */
    private fun updateAnimationState() {
        val hasVisibleLines = currentInput.guideLines.any { it.visible }
        if (hasVisibleLines) {
            animController.start()
        } else {
            animController.stop()
        }
    }

    fun getAnimationController(): AnimationController = animController

    /** ★ 设置对焦圆圈（触发动画） */
    fun showFocusCircle(cx: Float, cy: Float) {
        focusCircle = FocusCircleAnim(cx, cy)
        postInvalidateOnAnimation()
    }

    // ── 生命周期 ──────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        currentDensity = resources.displayMetrics.density
        // ★ 不再无条件启动动画，由 updateAnimationState() 按需启动
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animController.stop()
    }

    // ── 绘制 ──────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (guideLines.isEmpty()) return

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val pulseAlpha = animController.pulseAlpha
        val pulseScale = animController.pulseScale
        val flowOffset = animController.flowOffset
        val transitionAlpha = animController.transitionAlpha

        // ★ 场景过渡时应用整体透明度
        val needsLayer = transitionAlpha < 0.99f
        val layerId = if (needsLayer) {
            canvas.saveLayerAlpha(
                0f, 0f, w.toFloat(), h.toFloat(),
                (transitionAlpha * 255).toInt().coerceIn(0, 255)
            )
        } else {
            canvas.save()
            0
        }

        for (line in guideLines) {
            if (!line.visible) continue

            try {
                drawLine(
                    canvas, line, w, h,
                    pulseAlpha, pulseScale, flowOffset,
                    currentInput.isFrontCamera, currentInput.rotationDegrees
                )
            } catch (e: Exception) {
                android.util.Log.w("Overlay", "绘制 ${line.type} 失败: ${e.message}")
            }
        }

        // ★ 绘制对焦圆圈（最上层）
        focusCircle?.let { fc ->
            if (!fc.isExpired()) {
                drawFocusCircleAnim(canvas, fc)
                postInvalidateOnAnimation() // 持续重绘直到动画结束
            } else {
                focusCircle = null
            }
        }

        if (needsLayer) {
            canvas.restoreToCount(layerId)
        } else {
            canvas.restore()
        }
    }

    // ── 按类型分发 ────────────────────────────────────────────

    /** ★ 绘制对焦圆圈动画 */
    private fun drawFocusCircleAnim(canvas: Canvas, fc: FocusCircleAnim) {
        val progress = fc.progress()
        val density = currentDensity

        // 动画分阶段：
        // 0.0–0.3：白色圆圈放大（缩小效果，从大到正常）
        // 0.3–0.6：圆圈变绿（对焦成功）
        // 0.6–1.0：淡出消失

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }

        val baseRadius = 28f * density
        val radius = when {
            progress < 0.3f -> baseRadius * (1.5f - progress / 0.3f * 0.5f)
            else -> baseRadius
        }

        val alpha = when {
            progress < 0.6f -> 255
            else -> (255 * (1f - (progress - 0.6f) / 0.4f)).toInt().coerceIn(0, 255)
        }

        val color = when {
            progress < 0.3f -> android.graphics.Color.argb(alpha, 255, 255, 255)
            else -> android.graphics.Color.argb(alpha, 0, 230, 118)
        }

        paint.color = color
        canvas.drawCircle(fc.centerX, fc.centerY, radius, paint)
    }

    private fun drawLine(
        canvas: Canvas,
        line: GuideLine,
        w: Int, h: Int,
        pulseAlpha: Float,
        pulseScale: Float,
        flowOffset: Float,
        isFrontCamera: Boolean,
        rotationDegrees: Int
    ) {
        val density = currentDensity

        when (line.type) {
            GuideLineType.RULE_OF_THIRDS_GRID -> {
                val params = GuideLineParser.parseGrid(line.params)
                GuideLineRenderer.drawGrid(canvas, params, w, h, isFrontCamera, density)
                GuideLineRenderer.drawThirdsPoints(canvas, params, w, h, isFrontCamera, density)
            }

            GuideLineType.SUBJECT_BOX -> {
                val params = GuideLineParser.parseSubjectBox(line.params)
                GuideLineRenderer.drawSubjectBox(
                    canvas, params, w, h, isFrontCamera, rotationDegrees,
                    pulseAlpha, density
                )
            }

            GuideLineType.TARGET_ZONE -> {
                val params = GuideLineParser.parseTargetZone(line.params)
                GuideLineRenderer.drawTargetZone(
                    canvas, params, w, h, isFrontCamera, rotationDegrees,
                    pulseAlpha, pulseScale, density
                )
            }

            GuideLineType.HORIZON_LEVEL -> {
                val params = GuideLineParser.parseHorizon(line.params)
                GuideLineRenderer.drawHorizonLevel(canvas, params, w, h, density)
            }

            GuideLineType.DIRECTION_ARROW -> {
                val params = GuideLineParser.parseArrow(line.params)
                GuideLineRenderer.drawDirectionArrow(
                    canvas,
                    params.fromX, params.fromY,
                    params.toX, params.toY,
                    w, h, flowOffset, density
                )
            }

            GuideLineType.GUIDE_LINE_PATH -> {
                val points = (line.params["points"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toFloat() }
                    ?.toFloatArray()
                if (points != null) {
                    GuideLineRenderer.drawGuideLinePath(
                        canvas, points, w, h, flowOffset, density
                    )
                }
            }

            GuideLineType.UNKNOWN,
            GuideLineType.FRAMEWORK -> {
                // 未实现：跳过
            }

            else -> {}
        }
    }
}
