package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.HorizonPolicy
import com.example.aicamera.guidance.geometry.GuidanceGeometry
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
class CompositionSessionController(
    private val horizonPolicy: HorizonPolicy = HorizonPolicy()
) {

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
        data object MoveUpLeft : GuidanceAction
        data object MoveUpRight : GuidanceAction
        data object MoveDownLeft : GuidanceAction
        data object MoveDownRight : GuidanceAction
        data object DeviceUpsideDown : GuidanceAction
        data object Ready : GuidanceAction
    }

    /** 帧观测（由几何阶段 + AI 推理合并后输入） */
    data class FrameObservation(
        val frameId: Long = 0,
        val timestampMs: Long = System.nanoTime() / 1_000_000L,
        /** 水平角（度），经圆周平滑 */
        val rollDegrees: Float? = null,
        val rawRollDegrees: Float? = null,
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
        /** Retrieval availability only; similarity is not aesthetic or alignment confidence. */
        val modelConfidence: Float = 0f,
        /** Explicit trusted pose targets; raw IMU observations never imply a zero target. */
        val pitchTargetDegrees: Float? = null,
        val yawTargetDegrees: Float? = null,
        val coordinateAspectRatio: Float = 1f,
        val subjectObservationId: Long = frameId,
        val subjectTrackingId: Int? = null,
        val subjectSampledAtMs: Long = timestampMs,
        val imuObservationId: Long = frameId,
        val imuSampledAtMs: Long = timestampMs,
        val faceCount: Int = if (hasRealSubject) 1 else 0,
        val viewportVersion: Long = 0,
        val targetViewportVersion: Long = viewportVersion,
        val targetId: String? = null,
        val qualityScore: Float? = null,
        val qualityAcceptable: Boolean = true,
        /** FACE and OBJECT use different detectors; a COCO object never counts as a face. */
        val observedAnchorKind: ObservedAnchorKind = ObservedAnchorKind.FACE,
        /** False only when a fresh IMU confirms the gravity axis cannot establish image roll. */
        val rollObservable: Boolean = true,
        val executionQuality: ExecutionQualityEvidence? = null,
        /** Enabled by the real model path; legacy/fake geometry fixtures cannot invent image evidence. */
        val executionQualityRequired: Boolean = false,
        /** A bounded camera operation, not a second composition-readiness verdict. */
        val zoomInProgress: Boolean = false,
        /** Preserve visual direction briefly, but revoke every automatic capture lease. */
        val trackingPaused: Boolean = false,
        val visualPositionAvailable: Boolean = false,
        val visualPositionSampledAtMs: Long = -1L,
        /** Production position guidance uses the visible target's one-second dwell policy. */
        val targetDwellCapture: Boolean = false
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _action = MutableStateFlow<GuidanceAction>(GuidanceAction.HoldStill)
    val action: StateFlow<GuidanceAction> = _action.asStateFlow()

    private val _decision = MutableStateFlow(GuidanceDecision(GuidanceAction.HoldStill, null, null,
        GuidanceFeedback.INSUFFICIENT_EVIDENCE, 0))
    val decision: StateFlow<GuidanceDecision> = _decision.asStateFlow()
    private var lastSubjectId = -1L
    private var lastImuId = -1L
    private var viewportVersion: Long? = null
    private var readySamples = 0
    private var firstReadyAt = 0L
    private var lockedTarget: FrameObservation? = null
    private var previousError: Float? = null
    private var previousQuality: Float? = null
    private var worseningCount = 0
    private var lastImprovedAt = -1L
    private var replanned = false
    private var pausedTarget: String? = null
    private var pausedAtMs: Long = 0L
    private var readyGraceFrames = 0
    private var lastTimestamp = -1L
    private var lastTrackingId: Int? = null
    private var lastAcceptedAt = -1L
    private var lastAnchorKind: ObservedAnchorKind? = null
    private val executionGuard = ExecutionQualityGuard()
    private var lastExecutionId = -1L
    private var lastCaptureEvidence: FrameObservation? = null
    private val targetDwell = TargetDwellGate()


    // ── 滞回状态 ──
    private var levelGoodFrames = 0
    private var levelBadFrames = 0
    private var positionGoodFrames = 0
    private var positionBadFrames = 0
    private var lastAppliedFrameId: Long = -1

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

    /** Called again after asynchronous AF/AE. Ready is a lease on live evidence, not a latch. */
    fun confirmReadyForCapture(nowMs:Long):Boolean {
        val obs=lastCaptureEvidence
        if (obs?.targetDwellCapture == true) {
            val valid = _state.value is State.Ready && dwellEvidenceValid(obs, nowMs) &&
                GuidanceGeometry.isSubjectAligned(obs.subjectCx, obs.subjectCy, obs.targetCx, obs.targetCy,
                    obs.targetTolerance, obs.coordinateAspectRatio)
            if (!valid) { targetDwell.reset(); hold(obs.copy(timestampMs = nowMs), GuidanceFeedback.INSUFFICIENT_EVIDENCE) }
            return valid
        }
        val subjectAge=if(obs?.observedAnchorKind==ObservedAnchorKind.OBJECT)1500L else 750L
        fun fresh(time:Long,maxAge:Long)=nowMs>=time && nowMs-time<=maxAge
        val valid=_state.value is State.Ready && obs!=null && fresh(obs.timestampMs,750) && fresh(obs.imuSampledAtMs,1500) &&
            (!(obs.expectsSubject || obs.hasRealSubject) || fresh(obs.subjectSampledAtMs,subjectAge)) &&
            (!obs.executionQualityRequired || obs.executionQuality?.isFresh(nowMs)==true)
        if(!valid && _state.value is State.Ready && obs!=null)hold(obs.copy(timestampMs=nowMs),GuidanceFeedback.INSUFFICIENT_EVIDENCE)
        return valid
    }

    private fun dwellEvidenceValid(obs: FrameObservation, now: Long): Boolean {
        val sampledAt = if (obs.visualPositionAvailable) obs.visualPositionSampledAtMs else obs.subjectSampledAtMs
        val positionValid = (obs.visualPositionAvailable || (obs.hasRealSubject && obs.subjectObservationId > 0 &&
            (obs.observedAnchorKind != ObservedAnchorKind.FACE || obs.faceCount == 1))) &&
            now >= sampledAt && now - sampledAt <= 750 && obs.targetViewportVersion == obs.viewportVersion
        val requireRoll = obs.observedAnchorKind != ObservedAnchorKind.OBJECT || obs.rollObservable
        val roll = obs.rawRollDegrees ?: obs.rollDegrees
        val levelValid = !requireRoll || (obs.isUpright == true && roll?.isFinite() == true &&
            abs(roll) <= horizonPolicy.enterLevelToleranceDeg)
        val poseValid = listOf(GuidanceGeometry.angleDelta(obs.pitchDegrees, obs.pitchTargetDegrees) to GuidanceGeometry.PITCH_THRESHOLD_DEG,
            GuidanceGeometry.angleDelta(obs.yawDegrees, obs.yawTargetDegrees) to GuidanceGeometry.YAW_THRESHOLD_DEG)
            .all { (delta, tolerance) -> delta == null || abs(delta) <= tolerance }
        return positionValid && !obs.zoomInProgress && levelValid && poseValid && obs.imuObservationId > 0 &&
            now >= obs.imuSampledAtMs && now - obs.imuSampledAtMs <= 1500 &&
            now >= obs.timestampMs && now - obs.timestampMs <= 750
    }

    /** Shared by detector and image-tracker positions; cached positions never accumulate time. */
    private fun observeTargetDwell(obs: FrameObservation): Boolean {
        if (!GuidanceGeometry.isValidTarget(obs.targetCx, obs.targetCy, obs.targetTolerance) ||
            (!obs.hasRealSubject && !obs.visualPositionAvailable)) {
            targetDwell.reset()
            lastCaptureEvidence = null
            return false
        }
        val valid = dwellEvidenceValid(obs, obs.timestampMs)
        val inside = valid && GuidanceGeometry.isSubjectAligned(obs.subjectCx, obs.subjectCy,
            obs.targetCx, obs.targetCy, obs.targetTolerance, obs.coordinateAspectRatio)
        val sampledAt = if (obs.visualPositionAvailable) obs.visualPositionSampledAtMs else obs.subjectSampledAtMs
        val ready = targetDwell.update("${obs.viewportVersion}:${obs.targetId}:${obs.targetCx}:${obs.targetCy}:${obs.targetTolerance}",
            sampledAt, obs.timestampMs, inside)
        val roll = obs.rawRollDegrees ?: obs.rollDegrees
        val requireRoll = obs.observedAnchorKind != ObservedAnchorKind.OBJECT || obs.rollObservable
        val cue = GuidanceGeometry.guidanceCue(obs.subjectCx, obs.subjectCy, true,
            obs.targetCx!!, obs.targetCy!!, obs.targetTolerance,
            horizonAngleDeg = roll?.takeIf { requireRoll && abs(it) > horizonPolicy.enterLevelToleranceDeg },
            pitchAngleDeg = obs.pitchDegrees, pitchTargetDeg = obs.pitchTargetDegrees,
            yawAngleDeg = obs.yawDegrees, yawTargetDeg = obs.yawTargetDegrees,
            coordinateAspectRatio = obs.coordinateAspectRatio)
        val action = when {
            ready -> GuidanceAction.Ready
            obs.zoomInProgress -> GuidanceAction.HoldStill
            else -> when (cue) {
                GuidanceCue.MOVE_LEFT -> GuidanceAction.MoveLeft
                GuidanceCue.MOVE_RIGHT -> GuidanceAction.MoveRight
                GuidanceCue.MOVE_UP -> GuidanceAction.MoveUp
                GuidanceCue.MOVE_DOWN -> GuidanceAction.MoveDown
                GuidanceCue.MOVE_UP_LEFT -> GuidanceAction.MoveUpLeft
                GuidanceCue.MOVE_UP_RIGHT -> GuidanceAction.MoveUpRight
                GuidanceCue.MOVE_DOWN_LEFT -> GuidanceAction.MoveDownLeft
                GuidanceCue.MOVE_DOWN_RIGHT -> GuidanceAction.MoveDownRight
                GuidanceCue.ROLL_CLOCKWISE -> GuidanceAction.RotateClockwise
                GuidanceCue.ROLL_COUNTERCLOCKWISE -> GuidanceAction.RotateCounterClockwise
                GuidanceCue.PITCH_UP -> GuidanceAction.PitchUp
                GuidanceCue.PITCH_DOWN -> GuidanceAction.PitchDown
                GuidanceCue.YAW_LEFT -> GuidanceAction.YawLeft
                GuidanceCue.YAW_RIGHT -> GuidanceAction.YawRight
                else -> GuidanceAction.HoldStill
            }
        }
        _state.value = when { ready -> State.Ready; inside -> State.Verifying; else -> State.Adjusting(action, computeProgress(obs)) }
        _action.value = action
        _decision.value = GuidanceDecision(action, obs.targetId, measuredError(obs), when {
            ready -> GuidanceFeedback.READY
            inside -> GuidanceFeedback.VERIFYING
            valid -> GuidanceFeedback.ADJUSTING
            else -> GuidanceFeedback.INSUFFICIENT_EVIDENCE
        }, sampledAt)
        lastCaptureEvidence = obs.takeIf { ready }
        return true
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
    fun observe(input: FrameObservation) {
        if (input.frameId > 0 && input.frameId <= lastAppliedFrameId) return
        lastAppliedFrameId = input.frameId
        if (_state.value !is State.Adjusting && _state.value !is State.Verifying && _state.value !is State.Ready) return
        if (input.timestampMs < lastTimestamp) { hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE); return }
        lastTimestamp = input.timestampMs
        if (input.targetDwellCapture && observeTargetDwell(input)) return
        if (input.trackingPaused) {
            readySamples = 0; firstReadyAt = 0; lastCaptureEvidence = null
            val retained = if (input.visualPositionAvailable) {
                val cue = GuidanceGeometry.guidanceCue(input.subjectCx, input.subjectCy, true,
                    input.targetCx ?: .5f, input.targetCy ?: .5f, input.targetTolerance,
                    coordinateAspectRatio = input.coordinateAspectRatio)
                when (cue) {
                    GuidanceCue.MOVE_LEFT -> GuidanceAction.MoveLeft
                    GuidanceCue.MOVE_RIGHT -> GuidanceAction.MoveRight
                    GuidanceCue.MOVE_UP -> GuidanceAction.MoveUp
                    GuidanceCue.MOVE_DOWN -> GuidanceAction.MoveDown
                    GuidanceCue.MOVE_UP_LEFT -> GuidanceAction.MoveUpLeft
                    GuidanceCue.MOVE_UP_RIGHT -> GuidanceAction.MoveUpRight
                    GuidanceCue.MOVE_DOWN_LEFT -> GuidanceAction.MoveDownLeft
                    GuidanceCue.MOVE_DOWN_RIGHT -> GuidanceAction.MoveDownRight
                    else -> GuidanceAction.HoldStill
                }
            } else _action.value.takeUnless { it is GuidanceAction.Ready } ?: GuidanceAction.HoldStill
            _state.value = State.Adjusting(retained, 0f)
            _action.value = retained
            _decision.value = GuidanceDecision(retained, input.targetId, null,
                GuidanceFeedback.INSUFFICIENT_EVIDENCE, input.subjectObservationId)
            return
        }
        if (input.zoomInProgress) {
            hold(input, GuidanceFeedback.ADJUSTING)
            return
        }
        // Ready 后的柔性容差只能覆盖“同一主体、同一目标、同一视口”的轻微手抖。
        // 目标替换、跟踪 ID 重建、换场景或切视口必须立即重新积累证据。
        // 人脸跟踪 ID 在轻微移动时会频繁重分配，不应该撤销 Ready；
        // 物体检测的跟踪 ID 变化则可能意味着目标换了，必须重新积累证据。
        val trackingChanged = input.observedAnchorKind == ObservedAnchorKind.OBJECT &&
            lastTrackingId != input.subjectTrackingId
        if (trackingChanged && lastTrackingId != null) {
            resetCounters(resetFrameSequence = false)
        }
        val anchorChanged = lastAnchorKind != null && lastAnchorKind != input.observedAnchorKind
        val targetChanged = (input.observedAnchorKind == ObservedAnchorKind.OBJECT || input.targetId?.startsWith("live-face:") == true) &&
            lockedTarget != null && lockedTarget?.targetId != input.targetId
        val viewportChanged = viewportVersion != null && viewportVersion != input.viewportVersion
        if (viewportVersion != input.viewportVersion) {
            resetCounters(resetFrameSequence = false)
            viewportVersion = input.viewportVersion
        }
        if (lastTrackingId != input.subjectTrackingId) {
            // ★ 不再因为 trackingId 变化就清空全部计数。
            //   ML Kit 在人轻微移动、遮挡一次、甚至每几百毫秒就会重新分配 trackingId，
            //   旧逻辑会因此把 levelGoodFrames / positionGoodFrames / readySamples
            //   全部清零，看起来就是"人稍微动一下，引导就重新开始判断"。
            //   真正的换主体由后续的质量证据（框中心距离）与场景校验负责。
            lastTrackingId = input.subjectTrackingId
        }
        if ((lastAnchorKind != null && lastAnchorKind != input.observedAnchorKind) ||
            targetChanged) {
            val preserveReplan = lastAnchorKind == input.observedAnchorKind && replanned
            resetCounters(resetFrameSequence = false)
            replanned=preserveReplan
            viewportVersion=input.viewportVersion
            lastTrackingId=input.subjectTrackingId
        }
        lastAnchorKind=input.observedAnchorKind
        lastTimestamp = input.timestampMs
        if(input.observedAnchorKind == ObservedAnchorKind.OBJECT && input.targetId == null) lockedTarget = null
        val requiresSubject = input.expectsSubject || input.hasRealSubject
        if (requiresSubject && !hasPositionEvidence(input)) {
            lockedTarget = null
            hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE)
            return
        }
        val requireRoll = input.observedAnchorKind != ObservedAnchorKind.OBJECT || input.rollObservable
        val validImu = input.imuObservationId > 0 && input.timestampMs >= input.imuSampledAtMs &&
            input.timestampMs-input.imuSampledAtMs <= 1500 && (!requireRoll || input.rollDegrees?.isFinite() == true)
        val maxSubjectAge = if(input.observedAnchorKind == ObservedAnchorKind.OBJECT) 1500 else 750
        val validSubject = !requiresSubject || ((input.observedAnchorKind == ObservedAnchorKind.OBJECT || input.faceCount == 1) && input.hasRealSubject &&
            input.subjectObservationId > 0 && input.timestampMs >= input.subjectSampledAtMs &&
            input.timestampMs-input.subjectSampledAtMs <= maxSubjectAge && input.targetViewportVersion == input.viewportVersion)
        // ★ qualityAcceptable 不再作为硬门槛：
        //   它来自"98% 以上像素接近纯黑/纯白"的极端曝光判断，暗光或逆光场景下
        //   会长期为 false，导致引导永远停在"先保持，正在确认可用的构图依据"。
        //   画质信息仍会在质量校验里参与劣化判断，但不再阻断姿态/位置引导。
        if (!input.modelConfidence.isFinite() || input.modelConfidence <= 0f || !validImu || !validSubject) {
            lockedTarget = null
            hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE)
            return
        }
        var executionComparison = ExecutionComparison.COMPARABLE
        if(input.executionQualityRequired) {
            val quality=input.executionQuality
            val box=quality?.subjectBox
            val comparableSubject=!requiresSubject || (quality?.subjectTrackingId==input.subjectTrackingId && box!=null &&
                input.subjectCx?.let { abs(it-box.centerX)<=.03f }==true && input.subjectCy?.let { abs(it-box.centerY)<=.03f }==true)
            // ★ 质量证据缺失/不可比时，不再直接 hold 卡死，而是跳过本帧的质量校验，
            //   继续正常的姿态与位置引导。否则只要一次证据缺失（如检测采样错位），
            //   用户就会永远停在"确认中"，既看不到下一步指令也不会自动拍摄。
            val qualityUsable = quality != null && quality.isFresh(input.timestampMs) &&
                quality.viewportVersion == input.viewportVersion && comparableSubject
            if (!qualityUsable) {
                executionComparison = ExecutionComparison.COMPARABLE
            } else {
                val q = quality!!  // qualityUsable 已保证非空
                executionComparison = if (q.observationId <= lastExecutionId) {
                    ExecutionComparison.CACHED
                } else {
                    executionGuard.compare(q)
                }
                lastExecutionId = maxOf(lastExecutionId, q.observationId)
                if (executionComparison == ExecutionComparison.CONTEXT_CHANGED) {
                    previousError = null; previousQuality = null; lastImprovedAt = input.timestampMs
                    hold(input, GuidanceFeedback.SCENE_CHANGED)
                    return
                }
                if (executionComparison == ExecutionComparison.UNATTRIBUTED_MOTION) {
                    // 主体自身在小幅移动：跳过这一帧的推进，但不弹"画面发生变化"。
                    return
                }
                if (executionComparison == ExecutionComparison.PHOTOMETRIC_SETTLING) {
                    // 曝光/AE 微调既不是场景变化，也不是构图证据：这一帧直接跳过，
                    // 保持当前引导文案和进度不变。之前这里调用 hold() 会反复把界面
                    // 刷成"先保持，正在确认可用的构图依据"，手持时看起来像一直在重置。
                    return
                }
                if (executionComparison == ExecutionComparison.QUALITY_REGRESSED) {
                    // A closer target is not a success if the measured image becomes worse.
                    pausedTarget = input.targetId
                        ?: "${input.targetCx}:${input.targetCy}:${input.pitchTargetDegrees}:${input.yawTargetDegrees}"
                    lockedTarget = null
                    hold(input, GuidanceFeedback.WORSENING)
                    return
                }
            }
        }
        // Cached evidence may render, but can never confirm progress or Ready a second time.
        if (input.imuObservationId <= lastImuId || (requiresSubject && input.subjectObservationId <= lastSubjectId)) {
            // A new bad measurement can revoke Ready even when the other sensor is cached.
            // Only positive confirmation requires both streams to advance together.
            val target = lockedTarget ?: input
            val positionBad = requiresSubject && (!hasPositionEvidence(input.copy(targetCx=target.targetCx,targetCy=target.targetCy)) ||
                !GuidanceGeometry.isSubjectAligned(input.subjectCx, input.subjectCy,
                    target.targetCx ?: .5f, target.targetCy ?: .5f, target.targetTolerance, input.coordinateAspectRatio))
            val wasVerifying = _state.value is State.Ready || _state.value is State.Verifying
            val rollBad = requireRoll && (abs(input.rawRollDegrees ?: input.rollDegrees ?: Float.NaN) > horizonPolicy.enterLevelToleranceDeg || input.isUpright != true)
            if (rollBad && wasVerifying)
                hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE)
            else if (rollBad) {
                // IMU itself updates far slower than camera frames (sensor ~5Hz vs ~15-30fps).
                // Re-showing the SAME rotate action on a cached IMU sample is correct — the
                // device is still tilted — but calling hold() here overwrote it with HoldStill
                // on every repeated frame, so rotate/pitch/yaw guidance flashed for one frame
                // and then vanished until the next fresh IMU sample. Keep emitting the action
                // computed from this (still valid, just not brand-new) reading instead.
                val rawRoll = input.rawRollDegrees
                val action = if (rawRoll != null && rawRoll.isFinite()) {
                    if (rawRoll > 0) GuidanceAction.RotateCounterClockwise else GuidanceAction.RotateClockwise
                } else GuidanceAction.HoldStill
                _state.value = State.Adjusting(action, 0f)
                _action.value = action
                _decision.value = GuidanceDecision(action, input.targetId, measuredError(input),
                    GuidanceFeedback.ADJUSTING, input.subjectObservationId, reasons = executionGuard.lastReasons)
            } else if (positionBad && wasVerifying) {
                hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE)
            } else if(input.observedAnchorKind == ObservedAnchorKind.OBJECT && input.imuObservationId > lastImuId) {
                // Fresh IMU may establish level while a detector sample is cached; it never
                // advances position stability or Ready. This avoids a five-second level delay.
                if(!requireRoll || (input.isUpright == true && abs(input.rollDegrees ?: Float.NaN)<=horizonPolicy.enterLevelToleranceDeg))
                    levelGoodFrames++
                lastImuId=input.imuObservationId
            }
            return
        }
        if(executionComparison==ExecutionComparison.CACHED)return
        if (lastAcceptedAt >= 0 && input.timestampMs-lastAcceptedAt > 1500) {
            lockedTarget = null; previousError = null; previousQuality = null; worseningCount = 0
            lastImprovedAt = input.timestampMs
            hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE)
        }
        lastAcceptedAt = input.timestampMs
        lastImuId = input.imuObservationId
        if (requiresSubject) lastSubjectId = input.subjectObservationId
        val incomingId = input.targetId ?: "${input.targetCx}:${input.targetCy}:${input.pitchTargetDegrees}:${input.yawTargetDegrees}"
        if (pausedTarget != null) {
            // ★ 暂停不能无限期：旧逻辑要求"目标 id 变化"或"已重新规划"才恢复，
            //   但 WORSENING 分支从不请求重新规划（request = timedOut && !replanned），
            //   于是同一目标下会永久 hold 在这条提示上，用户看到的就是"卡死"。
            //   超过 PAUSE_TIMEOUT_MS 后强制恢复引导。
            val pausedTooLong = input.timestampMs - pausedAtMs > PAUSE_TIMEOUT_MS
            if (!pausedTooLong && (pausedTarget == incomingId || !replanned)) {
                hold(input, _decision.value.feedback)
                return
            }
            pausedTarget = null
            previousError = null
            previousQuality = null
            lastImprovedAt = input.timestampMs
            worseningCount = 0
        }
        if (lockedTarget == null && (hasPositionEvidence(input) || input.pitchTargetDegrees != null || input.yawTargetDegrees != null))
            lockedTarget = input
        val locked = lockedTarget
        val obs = if (locked == null) input else input.copy(targetCx = locked.targetCx, targetCy = locked.targetCy,
            targetTolerance = locked.targetTolerance, targetId = locked.targetId,
            pitchTargetDegrees = locked.pitchTargetDegrees, yawTargetDegrees = locked.yawTargetDegrees)
        val rawRoll = obs.rawRollDegrees
        val nextAction = if (requireRoll && rawRoll != null && (!rawRoll.isFinite() || abs(rawRoll) > horizonPolicy.enterLevelToleranceDeg)) {
            levelGoodFrames = 0; positionGoodFrames = 0
            when { rawRoll > 0 -> GuidanceAction.RotateCounterClockwise
                rawRoll < 0 -> GuidanceAction.RotateClockwise
                else -> GuidanceAction.HoldStill }
        } else computeAction(obs)
        val error = measuredError(obs)
        val previous = previousError
        val qualityWorse = obs.qualityScore?.let { q -> previousQuality?.let { q < it-.1f } } == true
        val improving = previous != null && error != null && error < previous-.015f &&
            executionComparison==ExecutionComparison.COMPARABLE
        if (lastImprovedAt < 0 || improving) lastImprovedAt = obs.timestampMs
        if(obs.observedAnchorKind == ObservedAnchorKind.OBJECT &&
            (nextAction is GuidanceAction.HoldStill || _action.value is GuidanceAction.HoldStill)) lastImprovedAt=obs.timestampMs
        if (previous != null && error != null && (error > previous+.015f || qualityWorse)) worseningCount++ else worseningCount = 0
        previousError = error
        previousQuality = obs.qualityScore?.takeIf { it.isFinite() }
        // Ready 后的轻微手抖不立刻打回调整态：允许最多 2 帧的“柔性越界”，
        // 只有持续偏离或主体离开安全区才撤销 Ready。
        val wasReadyLike = _state.value is State.Ready
        val sameEvidenceContext = !trackingChanged && !anchorChanged && !targetChanged && !viewportChanged
        if (wasReadyLike && sameEvidenceContext && (hasPositionEvidence(obs) || obs.pitchTargetDegrees != null ||
                obs.yawTargetDegrees != null) && nextAction !is GuidanceAction.Ready) {
            if (withinReadyGrace(obs) && readyGraceFrames < READY_GRACE_FRAMES) {
                readyGraceFrames++
                _state.value = State.Ready
                _action.value = GuidanceAction.Ready
                _decision.value = GuidanceDecision(
                    GuidanceAction.Ready, incomingId, error,
                    GuidanceFeedback.VERIFYING, obs.subjectObservationId,
                    reasons = executionGuard.lastReasons
                )
                lastCaptureEvidence = obs
                return
            }
            readyGraceFrames = 0
        } else {
            readyGraceFrames = 0
        }
        val timedOut = nextAction !is GuidanceAction.Ready && nextAction !is GuidanceAction.HoldStill &&
            obs.timestampMs-lastImprovedAt >= 5000
        if (worseningCount >= 2 || timedOut) {
            val feedback = if (worseningCount >= 2) GuidanceFeedback.WORSENING else GuidanceFeedback.NO_PROGRESS
            // WORSENING 也要请求重新规划，否则 pausedTarget 永远无法解除。
            val request = !replanned
            if (request) replanned = true
            pausedTarget = incomingId
            pausedAtMs = obs.timestampMs
            lockedTarget = null
            hold(obs, feedback)
            _decision.value = GuidanceDecision(GuidanceAction.HoldStill, incomingId, error, feedback,
                obs.subjectObservationId, replanRequested = request)
            return
        }
        if (nextAction is GuidanceAction.Ready) {
            if (readySamples == 0) firstReadyAt = obs.timestampMs
            readySamples++
            if (readySamples >= READY_SAMPLE_COUNT && obs.timestampMs-firstReadyAt >= READY_HOLD_MS) {
                _state.value = State.Ready
                _action.value = GuidanceAction.Ready
            } else {
                _state.value = State.Verifying
                _action.value = GuidanceAction.HoldStill
            }
        } else {
            readySamples = 0
            _state.value = State.Adjusting(nextAction, computeProgress(obs))
            _action.value = nextAction
        }
        _decision.value = GuidanceDecision(_action.value, incomingId, error,
            when { _state.value is State.Ready -> GuidanceFeedback.READY
                _state.value is State.Verifying -> GuidanceFeedback.VERIFYING
                improving -> GuidanceFeedback.IMPROVING
                nextAction is GuidanceAction.HoldStill -> GuidanceFeedback.INSUFFICIENT_EVIDENCE
                else -> GuidanceFeedback.ADJUSTING }, obs.subjectObservationId,reasons=executionGuard.lastReasons)
        if(_state.value is State.Ready)lastCaptureEvidence=obs
    }

    private fun hold(obs: FrameObservation, feedback: GuidanceFeedback) {
        readySamples = 0; firstReadyAt = 0
        levelGoodFrames = 0; positionGoodFrames = 0
        _state.value = State.Adjusting(GuidanceAction.HoldStill, 0f)
        _action.value = GuidanceAction.HoldStill
        _decision.value = GuidanceDecision(GuidanceAction.HoldStill, obs.targetId, measuredError(obs), feedback, obs.subjectObservationId,
            reasons=executionGuard.lastReasons)
    }

    /**
     * Ready 验收的柔性区：主体仍在画面安全区、水平没有明显歪掉时，
     * 允许一两帧轻微抖动，不把用户立刻打回“保持稳定”。
     */
    private fun withinReadyGrace(obs: FrameObservation): Boolean {
        // 必须使用原始 IMU 角度：平滑角可能掩盖真实倾斜（见 raw tilt 回归测试）。
        val roll = obs.rawRollDegrees ?: obs.rollDegrees
        val rollOk = roll == null || (roll.isFinite() && abs(roll) <= READY_GRACE_ROLL_DEG)
        val uprightOk = obs.isUpright != false
        val subjectOk = !(obs.expectsSubject || obs.hasRealSubject) ||
            (obs.subjectCx?.let { it in 0.08f..0.92f } == true &&
                obs.subjectCy?.let { it in 0.10f..0.84f } == true)
        val targetOk = if (hasPositionEvidence(obs)) {
            val dx = obs.subjectCx!! - obs.targetCx!!
            val dy = obs.subjectCy!! - obs.targetCy!!
            kotlin.math.hypot(dx, dy) <= maxOf(obs.targetTolerance * 2.2f, 0.22f)
        } else true
        return rollOk && uprightOk && subjectOk && targetOk
    }

    private fun measuredError(obs: FrameObservation): Float? {
        val roll = if(obs.observedAnchorKind == ObservedAnchorKind.OBJECT && !obs.rollObservable) 0f
            else obs.rollDegrees?.takeIf { it.isFinite() } ?: return null
        var error = abs(roll)/14f
        if (hasPositionEvidence(obs)) {
            val sx = maxOf(1f, obs.coordinateAspectRatio)
            val sy = maxOf(1f, 1f/obs.coordinateAspectRatio)
            error += kotlin.math.hypot((obs.subjectCx!!-obs.targetCx!!)*sx, (obs.subjectCy!!-obs.targetCy!!)*sy)
        }
        return error
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun computeAction(obs: FrameObservation): GuidanceAction {
        // 1. 倒置检测（最高优先级）
        val requireRoll = obs.observedAnchorKind != ObservedAnchorKind.OBJECT || obs.rollObservable
        if (requireRoll && obs.isUpright == false && obs.rollDegrees != null &&
            abs(obs.rollDegrees) <= horizonPolicy.upsideDownToleranceDeg
        ) {
            levelGoodFrames = 0
            positionGoodFrames = 0
            return GuidanceAction.DeviceUpsideDown
        }

        // 2. 水平检查
        val levelOk = !requireRoll || (obs.rollDegrees != null && obs.rollDegrees.isFinite() &&
                obs.isUpright == true &&
                abs(obs.rollDegrees) <= horizonPolicy.enterLevelToleranceDeg)

        if (levelOk) {
            levelGoodFrames++
            levelBadFrames = 0
        } else {
            levelBadFrames++
            levelGoodFrames = 0
        }

        val levelStable = levelGoodFrames >= horizonPolicy.stableFrameCount
        // 水平未达标 → 旋转指令优先
        if (requireRoll && !levelStable && obs.rollDegrees != null &&
            abs(obs.rollDegrees) > horizonPolicy.enterLevelToleranceDeg
        ) {
            positionGoodFrames = 0
            return if (obs.rollDegrees > 0) GuidanceAction.RotateCounterClockwise
            else GuidanceAction.RotateClockwise
        }

        // Respect deliberate overhead/low-angle framing unless a verified target exists.
        val pitchDelta = GuidanceGeometry.angleDelta(obs.pitchDegrees, obs.pitchTargetDegrees)
        if (levelStable && pitchDelta != null && abs(pitchDelta) > GuidanceGeometry.PITCH_THRESHOLD_DEG) {
            positionGoodFrames = 0
            return if (pitchDelta > 0) GuidanceAction.PitchDown
            else GuidanceAction.PitchUp
        }

        // 4. 偏航检查（水平、俯仰已稳）
        val yawDelta = GuidanceGeometry.angleDelta(obs.yawDegrees, obs.yawTargetDegrees)
        if (levelStable && yawDelta != null) {
            if (abs(yawDelta) > GuidanceGeometry.YAW_THRESHOLD_DEG) {
                positionGoodFrames = 0
                return if (yawDelta > 0) GuidanceAction.YawRight
                else GuidanceAction.YawLeft
            }
        }

        // A portrait template needs a live subject. Level sensors cannot substitute
        // for detection, and reacquisition must build fresh position stability.
        if ((obs.expectsSubject && !hasPositionEvidence(obs)) ||
            (obs.pitchTargetDegrees != null && pitchDelta == null) ||
            (obs.yawTargetDegrees != null && yawDelta == null)
        ) {
            positionGoodFrames = 0
            positionBadFrames = 0
            return GuidanceAction.HoldStill
        }

        // 5. 位置检查（仅在有真实主体 + 模板期望主体时生效）
        if (hasPositionEvidence(obs)) {
            val positionCue = GuidanceGeometry.guidanceCue(
                obs.subjectCx, obs.subjectCy, true, obs.targetCx!!, obs.targetCy!!, obs.targetTolerance,
                coordinateAspectRatio = obs.coordinateAspectRatio
            )
            // A generous circle around the actual recommendation replaces the old
            // entire-screen rectangle, which approved almost every subject position.
            val positionOk = positionCue == GuidanceCue.SUBJECT_ALIGNED

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
                when (positionCue) {
                    GuidanceCue.MOVE_LEFT -> return GuidanceAction.MoveLeft
                    GuidanceCue.MOVE_RIGHT -> return GuidanceAction.MoveRight
                    GuidanceCue.MOVE_UP -> return GuidanceAction.MoveUp
                    GuidanceCue.MOVE_DOWN -> return GuidanceAction.MoveDown
                    GuidanceCue.MOVE_UP_LEFT -> return GuidanceAction.MoveUpLeft
                    GuidanceCue.MOVE_UP_RIGHT -> return GuidanceAction.MoveUpRight
                    GuidanceCue.MOVE_DOWN_LEFT -> return GuidanceAction.MoveDownLeft
                    GuidanceCue.MOVE_DOWN_RIGHT -> return GuidanceAction.MoveDownRight
                    else -> {}
                }
            }
        }

        // 水平已稳，且画面里没有可继续对齐的主体/位置目标 → 直接视为可拍。
        // 旧逻辑额外要求"存在验证过的俯仰/偏航目标"，导致风景、静物等无主体场景
        // 永远拿不到 Ready：用户按提示端平后一直停在"正在确认可用的构图依据"，
        // 只能自己按快门，自动拍摄失效。
        if (levelStable && !obs.expectsSubject && !hasPositionEvidence(obs) &&
            (obs.pitchTargetDegrees != null || obs.yawTargetDegrees != null)) {
            return GuidanceAction.Ready
        }

        // 5. 水平接近中
        return GuidanceAction.HoldStill
    }

    private fun computeProgress(obs: FrameObservation): Float {
        val hasPosition = hasPositionEvidence(obs)
        if ((obs.expectsSubject && !hasPosition) ||
            (obs.pitchTargetDegrees != null && GuidanceGeometry.angleDelta(obs.pitchDegrees, obs.pitchTargetDegrees) == null) ||
            (obs.yawTargetDegrees != null && GuidanceGeometry.angleDelta(obs.yawDegrees, obs.yawTargetDegrees) == null)
        ) return 0f
        val cue = GuidanceGeometry.guidanceCue(
            obs.subjectCx.takeIf { hasPosition }, obs.subjectCy.takeIf { hasPosition }, obs.expectsSubject,
            targetCx = obs.targetCx ?: 0.5f, targetCy = obs.targetCy ?: 0.5f,
            targetTolerance = obs.targetTolerance, horizonAngleDeg = obs.rollDegrees.takeIf { obs.rollObservable },
            pitchAngleDeg = obs.pitchDegrees, yawAngleDeg = obs.yawDegrees,
            pitchTargetDeg = obs.pitchTargetDegrees, yawTargetDeg = obs.yawTargetDegrees,
            coordinateAspectRatio = obs.coordinateAspectRatio
        )
        val progress = GuidanceGeometry.compositionProgress(
            obs.subjectCx.takeIf { hasPosition }, obs.subjectCy.takeIf { hasPosition },
            targetCx = obs.targetCx ?: 0.5f, targetCy = obs.targetCy ?: 0.5f,
            targetTolerance = obs.targetTolerance, horizonAngleDeg = obs.rollDegrees.takeIf { obs.rollObservable },
            pitchAngleDeg = obs.pitchDegrees, yawAngleDeg = obs.yawDegrees,
            pitchTargetDeg = obs.pitchTargetDegrees, yawTargetDeg = obs.yawTargetDegrees,
            coordinateAspectRatio = obs.coordinateAspectRatio
        )
        return GuidanceGeometry.verifiedProgress(cue, progress, obs.expectsSubject, hasPosition,
            hasCompositionTarget = hasPosition || obs.pitchTargetDegrees != null || obs.yawTargetDegrees != null)
    }

    private fun hasPositionEvidence(obs: FrameObservation): Boolean = obs.hasRealSubject &&
        obs.subjectCx != null && obs.subjectCx.isFinite() && obs.subjectCx in 0f..1f &&
        obs.subjectCy != null && obs.subjectCy.isFinite() && obs.subjectCy in 0f..1f &&
        GuidanceGeometry.isValidTarget(obs.targetCx, obs.targetCy, obs.targetTolerance) &&
        obs.coordinateAspectRatio.isFinite() && obs.coordinateAspectRatio > 0f

    companion object {
        /**
         * READY 确认门槛。
         *
         * 旧值 3 帧 / 750ms 让「辅助」变成阻碍：用户对齐后还要再等近 1 秒。
         * 现在 2 帧 / 300ms —— 仍要求连续正向观测（防单帧抖动误拍），
         * 但把无意义的等待压到感知阈值以下。
         */
        const val READY_SAMPLE_COUNT = 2
        const val READY_HOLD_MS = 300L

        /** Ready 后允许的轻微抖动帧数 */
        const val READY_GRACE_FRAMES = 2

        /** Ready 后允许的手持水平偏差（度） */
        const val READY_GRACE_ROLL_DEG = 8.0f

        /** "先保持/无改善"状态的最长停留：超过后强制恢复引导，避免永久卡死 */
        const val PAUSE_TIMEOUT_MS = 2_500L
    }

    private fun resetCounters(resetFrameSequence: Boolean = true) {
        targetDwell.reset()
        levelGoodFrames = 0
        levelBadFrames = 0
        positionGoodFrames = 0
        positionBadFrames = 0
        lastSubjectId = -1; lastImuId = -1; readySamples = 0; firstReadyAt = 0
        lockedTarget = null; previousError = null; previousQuality = null; worseningCount = 0
        lastImprovedAt = -1; pausedTarget = null; replanned = false; lastTimestamp = -1
        readyGraceFrames = 0
        lastAcceptedAt = -1; lastTrackingId = null
        executionGuard.reset(); lastExecutionId=-1L
        lastCaptureEvidence=null
        if (resetFrameSequence) { lastAppliedFrameId = -1; viewportVersion = null; lastAnchorKind = null }
    }
}

/** The active capture workflow renders the same action that controls readiness. */
fun CompositionSessionController.GuidanceAction.toGuidanceCue(): GuidanceCue = when (this) {
    CompositionSessionController.GuidanceAction.HoldStill -> GuidanceCue.NONE
    CompositionSessionController.GuidanceAction.RotateClockwise -> GuidanceCue.ROLL_CLOCKWISE
    CompositionSessionController.GuidanceAction.RotateCounterClockwise -> GuidanceCue.ROLL_COUNTERCLOCKWISE
    CompositionSessionController.GuidanceAction.PitchUp -> GuidanceCue.PITCH_UP
    CompositionSessionController.GuidanceAction.PitchDown -> GuidanceCue.PITCH_DOWN
    CompositionSessionController.GuidanceAction.YawLeft -> GuidanceCue.YAW_LEFT
    CompositionSessionController.GuidanceAction.YawRight -> GuidanceCue.YAW_RIGHT
    CompositionSessionController.GuidanceAction.MoveLeft -> GuidanceCue.MOVE_LEFT
    CompositionSessionController.GuidanceAction.MoveRight -> GuidanceCue.MOVE_RIGHT
    CompositionSessionController.GuidanceAction.MoveUp -> GuidanceCue.MOVE_UP
    CompositionSessionController.GuidanceAction.MoveDown -> GuidanceCue.MOVE_DOWN
    CompositionSessionController.GuidanceAction.MoveUpLeft -> GuidanceCue.MOVE_UP_LEFT
    CompositionSessionController.GuidanceAction.MoveUpRight -> GuidanceCue.MOVE_UP_RIGHT
    CompositionSessionController.GuidanceAction.MoveDownLeft -> GuidanceCue.MOVE_DOWN_LEFT
    CompositionSessionController.GuidanceAction.MoveDownRight -> GuidanceCue.MOVE_DOWN_RIGHT
    CompositionSessionController.GuidanceAction.DeviceUpsideDown -> GuidanceCue.DEVICE_UPSIDE_DOWN
    CompositionSessionController.GuidanceAction.Ready -> GuidanceCue.SUBJECT_ALIGNED
}
