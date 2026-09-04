package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.HorizonObservation
import com.example.aicamera.guidance.geometry.HorizonPolicy
import kotlin.math.abs

/**
 * 分步构图引导序列器（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么需要它
 * 旧实现把 14 种 GuidanceCue 一帧全部可能抛给 UI，屏幕上同时出现
 * 水平仪、移动箭头、主体框，用户不知道先做什么。
 * 本序列器把引导拆成**有先后依赖的四个步骤**，每帧只放行当前步骤的指令：
 *
 * ```
 * ① ZOOM    智能变焦：AI 分析画面 → 按主体占比计算最佳倍率 → 自动变焦
 * ② LEVEL   水平校正：只放行 ROLL_* 指令，端平为止
 * ③ SUBJECT 主体构图：只放行 MOVE_*、SUBJECT_* 指令，主体到位为止
 * ✓ DONE    全部就绪：放行所有指令（持续守护，水平/主体被破坏会回退）
 * ```
 *
 * ## 设计要点
 * - 步骤只前进不回退（DONE 除外：水平被打乱回 LEVEL、主体丢失回 SUBJECT）；
 * - ZOOM 只跑一次（避免和用户手动变焦打架），用户捏合变焦可 cancelAutoZoom()；
 * - 每一步都有超时/跳过条件，永远不会把用户卡死（快门全程可用）；
 * - 无信号降级：无水平传感器 → 跳过 LEVEL；模板不要主体 → 跳过 SUBJECT。
 */
class GuidanceStepSequencer {

    enum class GuidanceStep { ZOOM, LEVEL, SUBJECT, DONE }

    /** 每帧输入（由 RenderViewModel 从管线输出适配） */
    data class Input(
        /** 设备水平倾角（度，EMA 后），无传感器信号传 null */
        val horizonAngleDeg: Float?,
        /** 设备是否正立（gy > 0.5）；null = 未知 */
        val isUpright: Boolean? = null,
        /** 主体框高度占画面比例 0..1（归一化），无主体传 null */
        val subjectHeightFraction: Float?,
        /** 当前帧是否检测到主体 */
        val hasSubject: Boolean,
        /** 当前模板是否期望主体（人像类模板） */
        val expectsSubject: Boolean,
        /** 当前变焦倍率 */
        val currentZoomRatio: Float,
        val minZoomRatio: Float,
        val maxZoomRatio: Float,
        val nowMs: Long,
    )

    data class Output(
        val step: GuidanceStep,
        /** 过滤后的指令：只有当前步骤相关的 cue 会放行 */
        val cue: GuidanceCue,
        /** 当前步骤进度 0..1（喂给 GuidanceStateMachine） */
        val progress: Float,
        /** 自动变焦目标倍率（仅决策完成当帧非 null，属一次性事件） */
        val zoomTarget: Float? = null,
        /** 当前步骤给用户的一句话提示 */
        val hint: String,
    )

    var step: GuidanceStep = GuidanceStep.ZOOM
        private set

    // ── ZOOM 步骤状态 ──
    private val zoomSamples = mutableListOf<Float>()
    private var zoomDecisionDone = false
    private var zoomTargetEmitted = false
    private var zoomSettleSinceMs = -1L
    private var stepStartMs = -1L
    private var zoomUserCancelled = false

    // ── LEVEL 步骤状态 ──
    private var levelGoodFrames = 0
    private var levelNoSignalFrames = 0

    // ── SUBJECT 步骤状态 ──
    private var subjectAlignedFrames = 0

    // ── DONE 守护状态 ──
    private var doneBadLevelFrames = 0
    private var doneSubjectLostFrames = 0

    /** 场景切换 / 换摄像头 / 重新进入引导时调用：回到第①步 */
    fun reset(nowMs: Long = -1L) {
        step = GuidanceStep.ZOOM
        zoomSamples.clear()
        zoomDecisionDone = false
        zoomTargetEmitted = false
        zoomSettleSinceMs = -1L
        stepStartMs = nowMs
        zoomUserCancelled = false
        levelGoodFrames = 0
        levelNoSignalFrames = 0
        subjectAlignedFrames = 0
        doneBadLevelFrames = 0
        doneSubjectLostFrames = 0
    }

    /** 用户手动变焦（捏合/滑杆）→ 取消本次自动变焦，直接进入下一步 */
    fun cancelAutoZoom() {
        if (step == GuidanceStep.ZOOM) {
            zoomUserCancelled = true
            zoomDecisionDone = true
        }
    }

    /**
     * 每帧调用。
     *
     * @param rawCue 几何层算出的原始指令（未过滤）
     * @param rawProgress 原始构图进度
     */
    fun onFrame(input: Input, rawCue: GuidanceCue, rawProgress: Float): Output {
        if (stepStartMs < 0) stepStartMs = input.nowMs
        return when (step) {
            GuidanceStep.ZOOM -> handleZoom(input)
            GuidanceStep.LEVEL -> handleLevel(input, rawProgress)
            GuidanceStep.SUBJECT -> handleSubject(input, rawCue, rawProgress)
            GuidanceStep.DONE -> handleDone(input, rawCue, rawProgress)
        }
    }

    // ─────────────────────────────────────────────────────────
    // ① 智能变焦
    // ─────────────────────────────────────────────────────────

    private fun handleZoom(input: Input): Output {
        val dwellOk = input.nowMs - stepStartMs >= MIN_STEP_DWELL_MS

        // 0. 快速跳过：无主体可测 + 模板不要主体 + 已在广角端 → 变焦无意义
        //    （仍停留 MIN_STEP_DWELL_MS，让「AI 分析画面」可被感知）
        if (dwellOk && !input.hasSubject && !input.expectsSubject &&
            abs(input.currentZoomRatio - input.minZoomRatio) < 0.05f
        ) {
            advanceTo(GuidanceStep.LEVEL, input.nowMs)
            return Output(step, GuidanceCue.NONE, 0f, null, hintFor(input, step))
        }

        // 1. 采样主体占比（只要检测到主体就采样——即使风景模板，
        //    画面里有人脸时也值得为人像取景自动变焦）
        if (!zoomDecisionDone && input.hasSubject) {
            input.subjectHeightFraction?.let { frac ->
                if (frac in 0.02f..0.98f) zoomSamples.add(frac)
            }
        }

        val elapsed = input.nowMs - stepStartMs
        val enoughSamples = zoomSamples.size >= ZOOM_SAMPLE_COUNT
        val sampleWindowOver = elapsed >= ZOOM_SAMPLE_WINDOW_MS

        // 2. 决策（样本足够，或采样窗口结束——含无主体/非人像场景）
        var zoomTarget: Float? = null
        if (!zoomDecisionDone && (enoughSamples || sampleWindowOver)) {
            zoomDecisionDone = true
            val target = decideZoomTarget(input)
            if (target != null && abs(target - input.currentZoomRatio) >= ZOOM_MIN_CHANGE) {
                zoomTarget = target
                zoomTargetEmitted = true
            }
        }

        // 3. 前进条件
        val timedOut = elapsed >= ZOOM_STEP_TIMEOUT_MS
        val noZoomNeeded = zoomDecisionDone && !zoomTargetEmitted
        val zoomSettled = zoomTargetEmitted && zoomSettleSinceMs >= 0 &&
                (input.nowMs - zoomSettleSinceMs) >= ZOOM_SETTLE_MS

        if (timedOut || noZoomNeeded || zoomSettled || (zoomUserCancelled && zoomDecisionDone)) {
            advanceTo(GuidanceStep.LEVEL, input.nowMs)
            return Output(step, GuidanceCue.NONE, 0f, null, hintFor(input, GuidanceStep.LEVEL))
        }

        val hint = when {
            !zoomDecisionDone -> "AI 正在分析画面…"
            zoomTarget != null -> "自动变焦到 ${"%.1f".format(zoomTarget)}x，寻找最佳构图"
            else -> "自动变焦中，请稍候…"
        }
        return Output(step, GuidanceCue.NONE, 0f, zoomTarget, hint)
    }

    /** UI 层把变焦实际打到目标值后回调（开始 [ZOOM_SETTLE_MS] 稳定计时） */
    fun notifyZoomApplied(nowMs: Long) {
        if (step == GuidanceStep.ZOOM && zoomSettleSinceMs < 0) {
            zoomSettleSinceMs = nowMs
        }
    }

    /**
     * 计算最佳变焦倍率。
     *
     * 人像构图经验值：主体（人脸）高度占画面 ~[PORTRAIT_TARGET_FRAC] 时
     * 头肩比例最自然。占比偏小 → 放大；占比过大 → 缩小。
     * 结果夹到 [minZoom, min(maxZoom, AUTO_ZOOM_CAP)]。
     */
    private fun decideZoomTarget(input: Input): Float? {
        val cap = minOf(input.maxZoomRatio, AUTO_ZOOM_CAP)
        if (zoomSamples.isNotEmpty()) {
            val median = zoomSamples.sorted().let { it[it.size / 2] }
            val desired = input.currentZoomRatio * (PORTRAIT_TARGET_FRAC / median)
            return desired
                .coerceIn(input.currentZoomRatio * MIN_ZOOM_STEP_SCALE, input.currentZoomRatio * MAX_ZOOM_STEP_SCALE)
                .coerceIn(maxOf(1f, input.minZoomRatio), cap)
        }
        // 没有真实主体时不猜测，不覆盖用户手动选择的焦段。
        return null
    }

    // ─────────────────────────────────────────────────────────
    // ② 水平校正（使用 HorizonPolicy 滞回 + isUpright 防倒置）
    // ─────────────────────────────────────────────────────────

    /** 水平策略（集中配置，替代散落的魔法数字） */
    private val horizonPolicy = HorizonPolicy()

    private fun handleLevel(input: Input, rawProgress: Float): Output {
        val angle = input.horizonAngleDeg
        if (angle == null) {
            // 无传感器信号：连续无信号则跳过本步
            levelNoSignalFrames++
            if (levelNoSignalFrames >= LEVEL_NO_SIGNAL_SKIP_FRAMES) {
                advanceTo(GuidanceStep.SUBJECT, input.nowMs)
                return Output(step, GuidanceCue.NONE, rawProgress, null, hintFor(input, step))
            }
            return Output(step, GuidanceCue.NONE, rawProgress, null, hintFor(input, step))
        }
        levelNoSignalFrames = 0

        // ★ P0: 倒置检测 — 不能因为 |roll| 在容差内就让倒置通过
        if (input.isUpright == false && abs(angle) <= horizonPolicy.upsideDownToleranceDeg) {
            levelGoodFrames = 0
            return Output(step, GuidanceCue.DEVICE_UPSIDE_DOWN, 0f, null,
                "手机已倒置，请翻转手机")
        }

        val absAngle = abs(angle)
        if (absAngle <= horizonPolicy.enterLevelToleranceDeg) {
            levelGoodFrames++
            if (levelGoodFrames >= horizonPolicy.stableFrameCount &&
                input.nowMs - stepStartMs >= MIN_STEP_DWELL_MS
            ) {
                advanceTo(GuidanceStep.SUBJECT, input.nowMs)
                return Output(step, GuidanceCue.NONE, 1f, null, hintFor(input, step))
            }
            // 已端平但未满稳定帧：不再发指令，给接近满分的进度
            val progress = (1f - absAngle / horizonPolicy.exitLevelToleranceDeg).coerceIn(0.7f, 0.95f)
            return Output(step, GuidanceCue.NONE, progress, null, hintFor(input, step))
        }

        levelGoodFrames = 0
        val cue = if (angle > 0) GuidanceCue.ROLL_COUNTERCLOCKWISE else GuidanceCue.ROLL_CLOCKWISE
        // ★ 使用 exitLevelToleranceDeg 而非旧 LEVEL_REF_DEG 计算进度
        val progress = (1f - (absAngle - horizonPolicy.enterLevelToleranceDeg) /
                (horizonPolicy.exitLevelToleranceDeg * 2f)).coerceIn(0f, 1f)
        return Output(step, cue, progress, null, hintFor(input, step))
    }

    // ─────────────────────────────────────────────────────────
    // ③ 主体构图
    // ─────────────────────────────────────────────────────────

    private fun handleSubject(input: Input, rawCue: GuidanceCue, rawProgress: Float): Output {
        // 模板不要主体（风景/静物）：本步无意义，停留最短时长后通过
        if (!input.expectsSubject &&
            input.nowMs - stepStartMs >= MIN_STEP_DWELL_MS
        ) {
            advanceTo(GuidanceStep.DONE, input.nowMs)
            return Output(step, rawCue, rawProgress, null, hintFor(input, step))
        }

        if (rawCue == GuidanceCue.SUBJECT_ALIGNED) {
            subjectAlignedFrames++
            if (subjectAlignedFrames >= SUBJECT_ALIGNED_FRAMES &&
                input.nowMs - stepStartMs >= MIN_STEP_DWELL_MS
            ) {
                advanceTo(GuidanceStep.DONE, input.nowMs)
                return Output(step, rawCue, rawProgress, null, hintFor(input, step))
            }
        } else {
            subjectAlignedFrames = 0
        }

        // 只放行主体相关指令（ROLL 在上一步已解决；万一又歪了，DONE 守护会管）
        val cue = when (rawCue) {
            GuidanceCue.MOVE_LEFT, GuidanceCue.MOVE_RIGHT,
            GuidanceCue.MOVE_UP, GuidanceCue.MOVE_DOWN,
            GuidanceCue.SUBJECT_TARGET, GuidanceCue.SUBJECT_ALIGNED -> rawCue
            else -> GuidanceCue.NONE
        }
        return Output(step, cue, rawProgress, null, hintFor(input, step))
    }

    // ─────────────────────────────────────────────────────────
    // ✓ 完成守护
    // ─────────────────────────────────────────────────────────

    private fun handleDone(input: Input, rawCue: GuidanceCue, rawProgress: Float): Output {
        // 水平被明显打乱（带滞回）→ 回 LEVEL
        val angle = input.horizonAngleDeg
        if (angle != null && abs(angle) > horizonPolicy.exitLevelToleranceDeg) {
            doneBadLevelFrames++
            if (doneBadLevelFrames >= DONE_REGRESS_FRAMES) {
                advanceTo(GuidanceStep.LEVEL, input.nowMs)
                return Output(step, GuidanceCue.NONE, rawProgress, null, hintFor(input, step))
            }
        } else {
            doneBadLevelFrames = 0
        }

        // 倒置检测 → 回 LEVEL
        if (input.isUpright == false && angle != null &&
            abs(angle) <= horizonPolicy.upsideDownToleranceDeg
        ) {
            doneBadLevelFrames++
            if (doneBadLevelFrames >= DONE_REGRESS_FRAMES) {
                advanceTo(GuidanceStep.LEVEL, input.nowMs)
                return Output(step, GuidanceCue.DEVICE_UPSIDE_DOWN, rawProgress, null,
                    "手机已倒置，请翻转手机")
            }
        }

        // 主体丢失 → 回 SUBJECT
        if (input.expectsSubject && !input.hasSubject) {
            doneSubjectLostFrames++
            if (doneSubjectLostFrames >= DONE_SUBJECT_LOST_FRAMES) {
                advanceTo(GuidanceStep.SUBJECT, input.nowMs)
                return Output(step, GuidanceCue.SUBJECT_TARGET, rawProgress, null, hintFor(input, step))
            }
        } else {
            doneSubjectLostFrames = 0
        }

        return Output(step, rawCue, rawProgress, null, hintFor(input, step))
    }

    private fun advanceTo(next: GuidanceStep, nowMs: Long) {
        step = next
        stepStartMs = nowMs
        when (next) {
            GuidanceStep.LEVEL -> { levelGoodFrames = 0; levelNoSignalFrames = 0 }
            GuidanceStep.SUBJECT -> subjectAlignedFrames = 0
            GuidanceStep.DONE -> { doneBadLevelFrames = 0; doneSubjectLostFrames = 0 }
            GuidanceStep.ZOOM -> reset(nowMs)
        }
    }

    private fun hintFor(input: Input, s: GuidanceStep): String = when (s) {
        GuidanceStep.ZOOM -> "AI 正在分析画面…"
        GuidanceStep.LEVEL ->
            if (input.horizonAngleDeg == null) "正在校准画面…" else "② 端平手机，保持画面水平"
        GuidanceStep.SUBJECT ->
            if (!input.expectsSubject) "画面已就绪" else "③ 移动手机，让主体进入目标位置"
        GuidanceStep.DONE -> "构图已就绪，可以拍摄了"
    }

    companion object {
        /** 人像主体目标占高比（头肩构图经验值） */
        const val PORTRAIT_TARGET_FRAC = 0.30f

        /** 自动变焦倍率上限（不超过长焦端，避免数码变焦画质崩塌） */
        const val AUTO_ZOOM_CAP = 1.8f
        const val MIN_ZOOM_STEP_SCALE = 0.90f
        const val MAX_ZOOM_STEP_SCALE = 1.25f

        /** 变焦采样帧数 / 采样窗口 */
        const val ZOOM_SAMPLE_COUNT = 8
        const val ZOOM_SAMPLE_WINDOW_MS = 900L

        /** 小于该倍率变化不值得动变焦（用户感知不到） */
        const val ZOOM_MIN_CHANGE = 0.12f

        /** 变焦到位后的稳定观察期 */
        const val ZOOM_SETTLE_MS = 400L

        /** ZOOM 步最长停留（兜底前进，绝卡死） */
        const val ZOOM_STEP_TIMEOUT_MS = 4_500L

        /** 每步最短停留：让分步指引肉眼可感知，不会一闪而过 */
        const val MIN_STEP_DWELL_MS = 700L

        /** 无传感器信号跳过帧数 */
        const val LEVEL_NO_SIGNAL_SKIP_FRAMES = 30

        /** 主体对齐稳定帧数 */
        const val SUBJECT_ALIGNED_FRAMES = 6

        /** DONE 回退帧数 */
        const val DONE_REGRESS_FRAMES = 4
        const val DONE_SUBJECT_LOST_FRAMES = 10
    }
}
