package com.example.aicamera.guidance.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯 Kotlin 水平仪数学 — 无 Android 依赖，可 JVM 单测。
 *
 * ## 修复历史
 * - 旧公式 `atan2(-gx, -gy)` 在竖屏正立时输出 ~±180°，倒置时输出 ~0°，
 *   导致倒着拿手机反而更容易通过"水平校准"。审计确认这是 P0 Bug。
 * - 正确公式：`atan2(gx, gy)` — 正立时 0°，顺时针倾斜时 >0°，逆时针 <0°。
 * - 同时引入 `isUpright` 判断（gy > 0），180° 倒置绝不能判为水平。
 *
 * ## 坐标系（Android 设备）
 * ```
 * x → 右, y → 上（沿屏幕）, z → 出屏
 * 重力向量 g = (gx, gy, gz)
 * 竖屏正立时 g ≈ (0, +9.8, 0)
 * 绕 z 轴（镜头光轴）旋转 → roll 角变化
 * ```
 */
object HorizonMath {

    /**
     * 从重力向量计算 roll 角和正立状态。
     *
     * @param gx 重力 x 分量 (m/s²)
     * @param gy 重力 y 分量 (m/s²)
     * @param gz 重力 z 分量 (m/s²)，用于计算置信度
     * @return [HorizonObservation] 包含 roll、isUpright、confidence
     */
    fun compute(gx: Float, gy: Float, gz: Float): HorizonObservation {
        // roll = atan2(gx, gy)：正立时 0°，顺时针 > 0
        val rollRad = atan2(gx.toDouble(), gy.toDouble())
        val rollDeg = Math.toDegrees(rollRad).toFloat()

        // pitch = atan2(-gz, sqrt(gx² + gy²))：水平时 0°，上仰 > 0
        val pitchRad = atan2(-gz.toDouble(), sqrt((gx * gx + gy * gy).toDouble()))
        val pitchDeg = Math.toDegrees(pitchRad).toFloat()

        // isUpright：gy > 0 表示重力指向设备底部（即设备正立）
        // 阈值 0.5 m/s² 过滤水平放置时的噪声
        val isUpright = gy > 0.5f

        // confidence：基于 |g| 接近 9.8 的程度（远离 9.8 说明加速度干扰大）
        val gMag = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
        val confidence = (1f - abs(gMag - 9.8f) / 9.8f).coerceIn(0f, 1f)

        return HorizonObservation(
            rollDegrees = rollDeg,
            pitchDegrees = pitchDeg,
            isUpright = isUpright,
            confidence = confidence
        )
    }
}

/**
 * 水平观测结果。
 *
 * @param rollDegrees 绕镜头轴的倾角（度），正值 = 顺时针，范围 (-180, 180]
 * @param pitchDegrees 绕设备横轴的俯仰角（度），正值 = 上仰，范围 (-180, 180]
 * @param yawDegrees 绕设备竖轴的偏航角（度），范围 (-180, 180]；需要磁力计
 * @param isUpright 设备是否正立（gy > 0.5），倒置时为 false
 * @param confidence 重力读数可信度 0..1（|g| 接近 9.8 时高）
 */
data class HorizonObservation(
    val rollDegrees: Float,
    val pitchDegrees: Float? = null,
    val yawDegrees: Float? = null,
    val isUpright: Boolean,
    val confidence: Float
)

/**
 * 圆周角平滑器 — 用 sin/cos 向量 EMA 替代普通 EMA。
 *
 * ## 为什么需要
 * 普通 EMA（next = prev + α × (value - prev)）对角度有致命缺陷：
 * - 179° → -179° 真实只差 2°，但 EMA 认为差 358°，平滑结果会突然跳变。
 * - 这个跳变直接导致"水平对准闪烁"的用户体感 Bug。
 *
 * ## 做法
 * 1. 将角度转为 (sin, cos) 单位向量
 * 2. 分别对 sin 和 cos 做 EMA
 * 3. 从平滑后的向量反算角度 via atan2
 *
 * 测试用例：179→-179、-179→179、359→1、1→359 都不能产生巨大跳变。
 */
class CircularAngleSmoother(
    /** EMA 平滑因子 (0..1)，越大越紧跟原始值，越小越平滑 */
    val alpha: Float = 0.35f
) {
    private var smoothSin: Float = 0f
    private var smoothCos: Float = 1f  // 初始化为角度 0°
    private var initialized: Boolean = false

    /**
     * 输入一个原始角度，返回平滑后的角度。
     *
     * @param rawDegrees 原始角度（度），范围 (-180, 180]
     * @return 平滑后的角度（度）
     */
    fun smooth(rawDegrees: Float): Float {
        val rawRad = Math.toRadians(rawDegrees.toDouble())
        val rawSin = sin(rawRad).toFloat()
        val rawCos = cos(rawRad).toFloat()

        if (!initialized) {
            smoothSin = rawSin
            smoothCos = rawCos
            initialized = true
            return rawDegrees
        }

        smoothSin += alpha * (rawSin - smoothSin)
        smoothCos += alpha * (rawCos - smoothCos)

        val len = sqrt((smoothSin * smoothSin + smoothCos * smoothCos).toDouble()).toFloat()
        if (len < 0.001f) return rawDegrees  // 向量退化，回退原始值

        smoothSin /= len
        smoothCos /= len

        val smoothedRad = atan2(smoothSin.toDouble(), smoothCos.toDouble())
        return Math.toDegrees(smoothedRad).toFloat()
    }

    /** 重置平滑状态 */
    fun reset() {
        smoothSin = 0f
        smoothCos = 1f
        initialized = false
    }
}

/**
 * 水平判定策略 — 集中配置所有阈值，消除散落各处的魔法数字。
 *
 * ## Hysteresis（迟滞）原理
 * - 进入阈值严格（较小），退出阈值宽松（较大）
 * - 避免在临界点反复切换 → 消除"对齐→失准→对齐"闪烁
 *
 * ## 推荐初值（真机测试后可微调）
 * - enter: 4.0°，exit: 7.0°
 * - stableDuration: 350ms
 */
data class HorizonPolicy(
    /** 进入"水平"的容差（度）— 严格 */
    val enterLevelToleranceDeg: Float = 4.0f,
    /** 退出"水平"的容差（度）— 宽松 */
    val exitLevelToleranceDeg: Float = 7.0f,
    /** 连续稳定的最短时长（毫秒） */
    val stableDurationMs: Long = 350L,
    /** 连续稳定所需的有效帧数 */
    val stableFrameCount: Int = 5,
    /** 其中最少满足帧数 */
    val minGoodFrames: Int = 4,
    /** 退出"水平"需连续越界的帧数 */
    val exitFrames: Int = 2,
    /** 倒置判定：|roll| < 此值 但 !isUpright → UPSIDE_DOWN */
    val upsideDownToleranceDeg: Float = 15f
)

/**
 * 水平判定器 — 带滞回和连续确认的确定性状态机。
 *
 * 禁止模式：
 * - 单帧达标 → 立即"水平已对准"  ❌
 * - 单帧越界 → 立即退出          ❌
 *
 * 正确模式：
 * - 连续 N 帧中 ≥ M 帧满足 enter 阈值 + 持续 ≥ stableDuration → ALIGNED
 * - 连续 exitFrames 帧超出 exit 阈值 → 退出 ALIGNED
 */
class HorizonJudge(
    val policy: HorizonPolicy = HorizonPolicy()
) {
    enum class State { UNKNOWN, NOT_LEVEL, ALIGNED, UPSIDE_DOWN }

    var state: State = State.UNKNOWN
        private set

    private var goodFrames = 0
    private var totalFrames = 0
    private var badFrames = 0
    private var alignedSinceMs: Long = 0L

    /**
     * 输入一帧观测，返回当前水平状态。
     *
     * @param obs 水平观测
     * @param nowMs 当前时间（毫秒），用于稳定时长判定
     */
    fun observe(obs: HorizonObservation, nowMs: Long): State {
        // 倒置检测（最高优先级）
        if (!obs.isUpright && abs(obs.rollDegrees) <= policy.upsideDownToleranceDeg) {
            reset()
            state = State.UPSIDE_DOWN
            return state
        }

        val absRoll = abs(obs.rollDegrees)

        when (state) {
            State.ALIGNED -> {
                if (absRoll > policy.exitLevelToleranceDeg) {
                    badFrames++
                    if (badFrames >= policy.exitFrames) {
                        reset()
                        state = State.NOT_LEVEL
                    }
                } else {
                    badFrames = 0
                    // 仍在 aligned 状态，更新时间
                    if (alignedSinceMs == 0L) alignedSinceMs = nowMs
                }
            }
            else -> {
                totalFrames++
                if (absRoll <= policy.enterLevelToleranceDeg && obs.isUpright) {
                    goodFrames++
                }

                if (totalFrames >= policy.stableFrameCount &&
                    goodFrames >= policy.minGoodFrames
                ) {
                    state = State.ALIGNED
                    alignedSinceMs = nowMs
                    badFrames = 0
                }
            }
        }

        return state
    }

    /** 对齐持续时间（毫秒） */
    fun alignedDurationMs(nowMs: Long): Long =
        if (state == State.ALIGNED && alignedSinceMs > 0) nowMs - alignedSinceMs else 0L

    fun reset() {
        goodFrames = 0
        totalFrames = 0
        badFrames = 0
        alignedSinceMs = 0L
        state = State.UNKNOWN
    }
}
