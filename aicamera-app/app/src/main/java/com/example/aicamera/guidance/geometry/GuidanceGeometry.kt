package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.GuidanceCue
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 构图几何 → GuidanceCue 纯逻辑层（无 Android 依赖，可直接 JVM 单测）。
 *
 * ## 职责
 * 按 docs/10 第 6 节约定，把「当前帧几何」与「模板目标」做差值，
 * 产出 14 种 GuidanceCue 中可可靠支持的部分：
 * - ROLL_CLOCKWISE / ROLL_COUNTERCLOCKWISE（水平角超阈）
 * - MOVE_LEFT / MOVE_RIGHT / MOVE_UP / MOVE_DOWN（主体中心偏离目标区）
 * - SUBJECT_TARGET（模板期望主体但未检测到）
 * - SUBJECT_ALIGNED（主体已进入目标区容差）
 * - NONE（其余情况）
 *
 * PITCH 与 YAW 系列第一版不做（需要 IMU 姿态/视觉几何之外更多的信号，docs/10 明确不建议硬做）。
 */
object GuidanceGeometry {

    /** 水平角阈值（度）：对齐 HorizonPolicy.enterLevelToleranceDeg，用于触发 ROLL 指令 */
    const val ROLL_THRESHOLD_DEG = 4.0f

    /** 俯仰角阈值（度）：大于此值触发 PITCH 指令 */
    const val PITCH_THRESHOLD_DEG = 7.0f

    /** 偏航角阈值（度）：大于此值触发 YAW 指令；磁力计噪声较大，阈值放宽 */
    const val YAW_THRESHOLD_DEG = 12.0f

    /** 目标区容差下限（归一化）：模板 radius 过小时兜底，防止指令过灵敏 */
    const val MIN_TOLERANCE = 0.06f

    /** 位置偏差参考距离（归一化）：偏差达到该值时位置得分降为 0 */
    const val POSITION_REF_DIST = 0.5f

    /** 水平角参考范围（度）：倾角达到该值时水平得分降为 0 */
    const val HORIZON_REF_DEG = 14f

    /** 俯仰角参考范围（度） */
    const val PITCH_REF_DEG = 28f

    /** 偏航角参考范围（度） */
    const val YAW_REF_DEG = 36f

    /** 位置/水平/俯仰/偏航在进度中的权重 */
    const val POSITION_WEIGHT = 0.4f
    const val ROLL_WEIGHT = 0.25f
    const val PITCH_WEIGHT = 0.2f
    const val YAW_WEIGHT = 0.15f

    /**
     * 计算引导指令。
     *
     * 优先级与 docs/10 的 guidanceCueFromGeometry 一致：ROLL > PITCH > YAW > 平移。
     *
     * @param subjectCx 主体中心 x（归一化显示坐标），无主体传 null
     * @param subjectCy 主体中心 y（归一化显示坐标），无主体传 null
     * @param expectsSubject 当前模板是否期望主体（如人像模板）
     * @param targetCx 目标区中心 x（归一化）
     * @param targetCy 目标区中心 y（归一化）
     * @param targetTolerance 目标区容差（归一化，取模板 radius）
     * @param horizonAngleDeg 设备水平倾角（度，正值 = 顺时针倾斜），无信号传 null
     * @param pitchAngleDeg 设备俯仰角（度，正值 = 上仰），无信号传 null
     * @param yawAngleDeg 设备偏航角（度），无信号传 null
     * @param yawTargetDeg 偏航目标角（度），null 时不判断偏航
     */
    fun guidanceCue(
        subjectCx: Float?,
        subjectCy: Float?,
        expectsSubject: Boolean,
        targetCx: Float = 0.5f,
        targetCy: Float = 0.5f,
        targetTolerance: Float = 0.12f,
        horizonAngleDeg: Float? = null,
        pitchAngleDeg: Float? = null,
        yawAngleDeg: Float? = null,
        yawTargetDeg: Float? = null
    ): GuidanceCue {
        // 1. 水平倾斜优先（构图先要端平）
        val angle = horizonAngleDeg
        if (angle != null && angle > ROLL_THRESHOLD_DEG) return GuidanceCue.ROLL_COUNTERCLOCKWISE
        if (angle != null && angle < -ROLL_THRESHOLD_DEG) return GuidanceCue.ROLL_CLOCKWISE

        // 2. 俯仰角
        if (pitchAngleDeg != null && pitchAngleDeg > PITCH_THRESHOLD_DEG) return GuidanceCue.PITCH_DOWN
        if (pitchAngleDeg != null && pitchAngleDeg < -PITCH_THRESHOLD_DEG) return GuidanceCue.PITCH_UP

        // 3. 偏航角（需有目标朝向）
        if (yawAngleDeg != null && yawTargetDeg != null) {
            val delta = normalizeAngle(yawAngleDeg - yawTargetDeg)
            if (delta > YAW_THRESHOLD_DEG) return GuidanceCue.YAW_RIGHT
            if (delta < -YAW_THRESHOLD_DEG) return GuidanceCue.YAW_LEFT
        }

        // 4. 模板期望主体但没检测到 → 引导用户放入主体
        val hasSubject = subjectCx != null && subjectCy != null
        if (!hasSubject) {
            return if (expectsSubject) GuidanceCue.SUBJECT_TARGET else GuidanceCue.NONE
        }

        // 5. 主体 vs 目标区差值（与 docs/10 的映射一致）
        val tolerance = targetTolerance.coerceAtLeast(MIN_TOLERANCE)
        val dx = subjectCx!! - targetCx
        val dy = subjectCy!! - targetCy

        if (abs(dx) <= tolerance && abs(dy) <= tolerance) {
            return GuidanceCue.SUBJECT_ALIGNED
        }
        if (dx > tolerance) return GuidanceCue.MOVE_LEFT
        if (dx < -tolerance) return GuidanceCue.MOVE_RIGHT
        if (dy > tolerance) return GuidanceCue.MOVE_UP
        if (dy < -tolerance) return GuidanceCue.MOVE_DOWN
        return GuidanceCue.SUBJECT_TARGET
    }

    /**
     * 计算构图进度 (0.0–1.0)。
     *
     * 位置/水平/俯仰/偏航按权重归一化混合；所有信号都缺失时返回 null
     * （调用方应回退到模板检索分）。
     */
    fun compositionProgress(
        subjectCx: Float?,
        subjectCy: Float?,
        targetCx: Float = 0.5f,
        targetCy: Float = 0.5f,
        horizonAngleDeg: Float? = null,
        pitchAngleDeg: Float? = null,
        yawAngleDeg: Float? = null,
        yawTargetDeg: Float? = null
    ): Float? {
        var score = 0f
        var weight = 0f

        if (subjectCx != null && subjectCy != null) {
            val dist = hypot(subjectCx - targetCx, subjectCy - targetCy)
            val posScore = (1f - dist / POSITION_REF_DIST).coerceIn(0f, 1f)
            score += POSITION_WEIGHT * posScore
            weight += POSITION_WEIGHT
        }

        if (horizonAngleDeg != null) {
            val hScore = (1f - abs(horizonAngleDeg) / HORIZON_REF_DEG).coerceIn(0f, 1f)
            score += ROLL_WEIGHT * hScore
            weight += ROLL_WEIGHT
        }

        if (pitchAngleDeg != null) {
            val pScore = (1f - abs(pitchAngleDeg) / PITCH_REF_DEG).coerceIn(0f, 1f)
            score += PITCH_WEIGHT * pScore
            weight += PITCH_WEIGHT
        }

        if (yawAngleDeg != null && yawTargetDeg != null) {
            val delta = normalizeAngle(yawAngleDeg - yawTargetDeg)
            val yScore = (1f - abs(delta) / YAW_REF_DEG).coerceIn(0f, 1f)
            score += YAW_WEIGHT * yScore
            weight += YAW_WEIGHT
        }

        return if (weight > 0f) (score / weight).coerceIn(0f, 1f) else null
    }

    /** 主体对齐时的进度下限（保证 GuidanceStateMachine 能达到 ALIGNED 相位） */
    const val ALIGNED_PROGRESS_FLOOR = 0.95f

    /**
     * 进度与指令对齐（纯函数，便于单测）。
     *
     * 问题背景：SUBJECT_ALIGNED 的容差（0.12）比状态机 ALIGNED 阈值（0.92）
     * 宽松——主体刚进容差圈时位置分仅 ~0.66，综合进度 ~0.73，
     * 导致「指令说对齐了、状态机却永远到不了 ALIGNED」。
     * 修复：指令为 SUBJECT_ALIGNED 时进度提升到 [ALIGNED_PROGRESS_FLOOR] 下限。
     */
    fun progressWithAlignedBoost(cue: GuidanceCue, progress: Float): Float {
        return if (cue == GuidanceCue.SUBJECT_ALIGNED) {
            maxOf(progress, ALIGNED_PROGRESS_FLOOR)
        } else {
            progress
        }
    }

    /**
     * 将任意角度归一化到 (-180, 180]。
     */
    private fun normalizeAngle(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * 从检测框计算归一化中心点。
     */
    fun boxCenter(box: NormalizedBox): Pair<Float, Float> {
        return (box.left + box.right) / 2f to (box.top + box.bottom) / 2f
    }
}

/**
 * 归一化矩形框（显示坐标系，纯数据类，无 Android 依赖）。
 */
data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * 指数移动平均（EMA）平滑器 — 消除传感器/检测的帧间抖动。
 *
 * @param alpha 新值权重 (0,1]；越小越平滑但越迟滞。角度建议 0.2–0.35。
 */
class EmaSmoother(private val alpha: Float) {
    @Volatile
    private var current: Float? = null

    /** 输入一个样本，返回平滑后的值 */
    @Synchronized
    fun add(value: Float): Float {
        val prev = current
        val next = if (prev == null) value else prev + alpha * (value - prev)
        current = next
        return next
    }

    /** 当前平滑值（无样本时为 null） */
    fun value(): Float? = current

    fun reset() {
        current = null
    }
}
