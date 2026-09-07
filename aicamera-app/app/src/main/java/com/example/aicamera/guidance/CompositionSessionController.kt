package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.HorizonObservation
import com.example.aicamera.guidance.geometry.HorizonPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 统一构图会话控制器 — 整个拍摄工作流的唯一状态机。
 *
 * ## 修复历史
 * - 旧架构存在两套工作流同时修改构图状态：
 *   ① RenderViewModel → GuidanceStepSequencer → ZOOM/LEVEL/SUBJECT/DONE
 *   ② CameraScreen → CaptureWorkflowState → ANALYZING/ROTATING/GUIDING/READY
 * - 新工作流 GUIDING→READY 读取旧工作流 guidanceState.phase == ALIGNED，
 *   导致 GUIDING 经常直接跳过。这是「两个驾驶员同时控制一台车」。
 * - 本控制器统一为唯一状态机，UI 只管渲染 [state] 和 [action]。
 *
 * ## 状态机
 * ```
 * IDLE → ANALYZING → ADJUSTING → VERIFYING → READY → CAPTURING → SAVED
 *                ↘ ERROR（任何阶段可跳入）
 * ```
 *
 * ## 职责边界
 * - 控制器：接收 FrameObservation → 判断状态 → 输出 GuidanceAction
 * - UI：渲染 state + action，不包含业务 while 循环或状态过渡逻辑
 */
class CompositionSessionController {

    /** 统一会话状态 */
    sealed interface State {
        data object Idle : State
        data object Analyzing : State

        /**
         * 正在引导用户调整构图。
         * @param action 当前引导动作（旋转/平移/倒置提示等）
         * @param progress 当前维度进度 0..1
         */
        data class Adjusting(
            val action: GuidanceAction,
            val progress: Float = 0f
        ) : State

        /** 等待稳定确认 */
        data object Verifying : State

        /** 构图就绪，可以拍摄 */
        data object Ready : State

        /** 正在拍照/保存 */
        data object Capturing : State

        /** 保存成功 */
        data class Saved(val uri: String? = null) : State

        /** 异常 */
        data class Error(val reason: String) : State
    }

    /** 引导动作 */
    sealed interface GuidanceAction {
        data object HoldStill : GuidanceAction
        data object RotateClockwise : GuidanceAction
        data object RotateCounterClockwise : GuidanceAction
        data object PitchUp : GuidanceAction
        data object PitchDown : GuidanceAction
        data object YawLeft : GuidanceAction
        data object YawRight : GuidanceAction
        data object MoveLeft : GuidanceAction
        data object MoveRight : GuidanceAction
        data object MoveUp : GuidanceAction
        data object MoveDown : GuidanceAction
        data object DeviceUpsideDown : GuidanceAction
        data object Ready : GuidanceAction
    }

    /** 帧观测（由几何阶段 + AI 推理合并后输入） */
    data class FrameObservation(
        val frameId: Long = 0,
        val timestampMs: Long = System.currentTimeMillis(),
        /** 水平角（度），经圆周平滑 */
        val rollDegrees: Float? = null,
        /** 俯仰角（度），经圆周平滑 */
        val pitchDegrees: Float? = null,
        /** 偏航角（度），经圆周平滑；需磁力计 */
        val yawDegrees: Float? = null,
        /** 设备是否正立 */
        val isUpright: Boolean? = null,
        /** 当前主体中心 x（归一化），无真实主体则为 null */
        val subjectCx: Float? = null,
        /** 当前主体中心 y（归一化） */
        val subjectCy: Float? = null,
        /** 目标位置 x（归一化，来自 CompositionPlan） */
        val targetCx: Float? = null,
        /** 目标位置 y（归一化） */
        val targetCy: Float? = null,
        /** 目标区容差（归一化半径） */
        val targetTolerance: Float = 0.12f,
        /** 模板是否期望主体 */
        val expectsSubject: Boolean = false,
        /** 是否有真实主体检测 */
        val hasRealSubject: Boolean = false,
        /** AI 模型置信度 */
        val modelConfidence: Float = 0f
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _action = MutableStateFlow<GuidanceAction>(GuidanceAction.HoldStill)
    val action: StateFlow<GuidanceAction> = _action.asStateFlow()

    private val horizonPolicy = HorizonPolicy()

    // ── 滞回状态 ──
    private var levelGoodFrames = 0
    private var levelBadFrames = 0
    private var positionGoodFrames = 0
    private var positionBadFrames = 0
    private var verifyingStartMs = 0L
    private var lastAppliedFrameId: Long = -1

    /** 偏航目标：进入构图引导时锁定当前朝向 */
    private var yawTargetDeg: Float? = null

    /** 俯仰目标：理想为水平（0°） */
    private val pitchTargetDeg: Float = 0f

    /** 开始分析（快门按下后调用） */
    fun startAnalysis() {
        if (_state.value !is State.Idle) return
        _state.value = State.Analyzing
        resetCounters()
    }

    /** 分析完成 → 进入调整阶段 */
    fun onAnalysisComplete() {
        if (_state.value !is State.Analyzing) return
        _state.value = State.Adjusting(GuidanceAction.HoldStill)
        resetCounters()
    }

    /** 取消拍摄（用户再按快门） */
    fun cancel() {
        _state.value = State.Idle
        resetCounters()
    }

    /** 开始拍照 */
    fun startCapture() {
        _state.value = State.Capturing
    }

    /** 拍照完成 */
    fun onCaptureComplete(uri: String? = null) {
        _state.value = State.Saved(uri)
    }

    /** 重置到空闲 */
    fun resetToIdle() {
        _state.value = State.Idle
        resetCounters()
    }

    /** 错误 */
    fun error(reason: String) {
        _state.value = State.Error(reason)
    }

    /**
     * 输入一帧观测，控制器决定状态和动作。
     *
     * ★ 帧序保护：stale frame（frameId <= lastApplied）自动丢弃
     * ★ 单帧不改变状态：所有判定都经过滞回+连续确认
     */
    fun observe(obs: FrameObservation) {
        // ── Stale frame 丢弃 ──
        if (obs.frameId > 0 && obs.frameId <= lastAppliedFrameId) return
        lastAppliedFrameId = obs.frameId

        val currentState = _state.value
        when (currentState) {
            is State.Idle, State.Analyzing, State.Capturing, is State.Saved, is State.Error -> {
                // 这些状态不消费观测
                return
            }
            is State.Adjusting, State.Verifying, State.Ready -> {
                // 继续处理
            }
        }

        val action = computeAction(obs)

        when (currentState) {
            is State.Adjusting -> {
                if (action is GuidanceAction.Ready) {
                    // 所有维度对齐 → 进入验证
                    transitionToVerifying(obs.timestampMs)
                } else {
                    _state.value = State.Adjusting(action, computeProgress(obs))
                }
                _action.value = action
            }
            is State.Verifying -> {
                if (action is GuidanceAction.Ready) {
                    val elapsed = obs.timestampMs - verifyingStartMs
                    if (elapsed >= horizonPolicy.stableDurationMs) {
                        _state.value = State.Ready
                        _action.value = GuidanceAction.Ready
                    }
                } else {
                    // 验证期间出现偏差 → 退回调整
                    _state.value = State.Adjusting(action, computeProgress(obs))
                    _action.value = action
                }
            }
            is State.Ready -> {
                // READY 退出保护：单帧越界不退出
                if (action !is GuidanceAction.Ready) {
                    _state.value = State.Adjusting(action, computeProgress(obs))
                    _action.value = action
                }
            }
            else -> {}
        }
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun computeAction(obs: FrameObservation): GuidanceAction {
        // 1. 倒置检测（最高优先级）
        if (obs.isUpright == false && obs.rollDegrees != null &&
            abs(obs.rollDegrees) <= horizonPolicy.upsideDownToleranceDeg
        ) {
            levelGoodFrames = 0
            positionGoodFrames = 0
            return GuidanceAction.DeviceUpsideDown
        }

        // 2. 水平检查
        val levelOk = obs.rollDegrees != null &&
                obs.isUpright == true &&
                abs(obs.rollDegrees) <= horizonPolicy.enterLevelToleranceDeg

        if (levelOk) {
            levelGoodFrames++
            levelBadFrames = 0
        } else if (obs.rollDegrees != null) {
            levelBadFrames++
            levelGoodFrames = 0
        }

        val levelStable = levelGoodFrames >= horizonPolicy.stableFrameCount
        val levelBadStable = levelBadFrames >= horizonPolicy.exitFrames &&
                obs.rollDegrees != null &&
                abs(obs.rollDegrees) > horizonPolicy.exitLevelToleranceDeg

        // 水平未达标 → 旋转指令优先
        if (!levelStable && obs.rollDegrees != null &&
            abs(obs.rollDegrees) > horizonPolicy.enterLevelToleranceDeg
        ) {
            positionGoodFrames = 0
            return if (obs.rollDegrees > 0) GuidanceAction.RotateCounterClockwise
            else GuidanceAction.RotateClockwise
        }

        // 3. 俯仰检查（水平已稳）
        if (levelStable && obs.pitchDegrees != null &&
            abs(obs.pitchDegrees) > PITCH_TOLERANCE_DEG
        ) {
            positionGoodFrames = 0
            return if (obs.pitchDegrees > 0) GuidanceAction.PitchDown
            else GuidanceAction.PitchUp
        }

        // 4. 偏航检查（水平、俯仰已稳）
        if (levelStable && obs.yawDegrees != null) {
            if (yawTargetDeg == null) yawTargetDeg = obs.yawDegrees
            val delta = normalizeAngle(obs.yawDegrees - yawTargetDeg!!)
            if (abs(delta) > YAW_TOLERANCE_DEG) {
                positionGoodFrames = 0
                return if (delta > 0) GuidanceAction.YawRight
                else GuidanceAction.YawLeft
            }
        }

        // 5. 位置检查（仅在有真实主体 + 模板期望主体时生效）
        if (obs.hasRealSubject && obs.expectsSubject &&
            obs.subjectCx != null && obs.subjectCy != null &&
            obs.targetCx != null && obs.targetCy != null
        ) {
            val dx = obs.subjectCx - obs.targetCx
            val dy = obs.subjectCy - obs.targetCy
            val tolerance = obs.targetTolerance.coerceAtLeast(0.06f)

            val positionOk = abs(dx) <= tolerance && abs(dy) <= tolerance

            if (positionOk && levelStable) {
                positionGoodFrames++
                positionBadFrames = 0
                if (positionGoodFrames >= 2) {
                    return GuidanceAction.Ready
                }
            } else {
                positionBadFrames++
                if (positionBadFrames >= 3) positionGoodFrames = 0
            }

            // 水平已稳 → 平移指令
            if (levelStable) {
                if (dx > tolerance) return GuidanceAction.MoveLeft
                if (dx < -tolerance) return GuidanceAction.MoveRight
                if (dy > tolerance) return GuidanceAction.MoveUp
                if (dy < -tolerance) return GuidanceAction.MoveDown
            }
        }

        // 4. 如果没有真实主体/模板不要主体 → 水平达标即 Ready
        if (levelStable && (!obs.expectsSubject || !obs.hasRealSubject)) {
            return GuidanceAction.Ready
        }

        // 5. 水平接近中
        return if (levelOk) GuidanceAction.HoldStill else GuidanceAction.HoldStill
    }

    private fun transitionToVerifying(nowMs: Long) {
        verifyingStartMs = nowMs
        _state.value = State.Verifying
    }

    private fun computeProgress(obs: FrameObservation): Float {
        var score = 0f
        var weight = 0f

        if (obs.rollDegrees != null) {
            val hScore = (1f - abs(obs.rollDegrees) / horizonPolicy.exitLevelToleranceDeg)
                .coerceIn(0f, 1f)
            score += 0.25f * hScore
            weight += 0.25f
        }

        if (obs.pitchDegrees != null) {
            val pScore = (1f - abs(obs.pitchDegrees) / (PITCH_TOLERANCE_DEG * 2f))
                .coerceIn(0f, 1f)
            score += 0.2f * pScore
            weight += 0.2f
        }

        if (obs.yawDegrees != null && yawTargetDeg != null) {
            val delta = normalizeAngle(obs.yawDegrees - yawTargetDeg!!)
            val yScore = (1f - abs(delta) / (YAW_TOLERANCE_DEG * 2f))
                .coerceIn(0f, 1f)
            score += 0.15f * yScore
            weight += 0.15f
        }

        if (obs.subjectCx != null && obs.subjectCy != null &&
            obs.targetCx != null && obs.targetCy != null
        ) {
            val dist = kotlin.math.sqrt(
                (obs.subjectCx - obs.targetCx).let { it * it } +
                        (obs.subjectCy - obs.targetCy).let { it * it }
            ).toFloat()
            val pScore = (1f - dist / 0.5f).coerceIn(0f, 1f)
            score += 0.4f * pScore
            weight += 0.4f
        }

        return if (weight > 0f) (score / weight).coerceIn(0f, 1f) else 0f
    }

    private fun resetCounters() {
        levelGoodFrames = 0
        levelBadFrames = 0
        positionGoodFrames = 0
        positionBadFrames = 0
        verifyingStartMs = 0L
        lastAppliedFrameId = -1
        yawTargetDeg = null
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

    companion object {
        /** 俯仰角容差（度）：超过此值触发俯仰引导 */
        private const val PITCH_TOLERANCE_DEG = 7.0f

        /** 偏航角容差（度）：超过此值触发偏航引导 */
        private const val YAW_TOLERANCE_DEG = 12.0f
    }
}
