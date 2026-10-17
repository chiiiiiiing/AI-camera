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
        val subjectBox: com.example.aicamera.guidance.geometry.NormalizedBox? = null,
        val guidanceLimitations: List<String> = emptyList()
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _action = MutableStateFlow<GuidanceAction>(GuidanceAction.HoldStill)
    val action: StateFlow<GuidanceAction> = _action.asStateFlow()

    private val horizonPolicy = HorizonPolicy()
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
    private var lastTimestamp = -1L
    private var lastTrackingId: Int? = null
    private var lastAcceptedAt = -1L
    private var lastAnchorKind: ObservedAnchorKind? = null
    private val executionGuard = ExecutionQualityGuard()
    private var lastExecutionId = -1L
    private var lastCaptureEvidence: FrameObservation? = null


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
        val subjectAge=if(obs?.observedAnchorKind==ObservedAnchorKind.OBJECT)1500L else 750L
        fun fresh(time:Long,maxAge:Long)=nowMs>=time && nowMs-time<=maxAge
        val valid=_state.value is State.Ready && obs!=null && fresh(obs.timestampMs,750) && fresh(obs.imuSampledAtMs,1500) &&
            (!(obs.expectsSubject || obs.hasRealSubject) || fresh(obs.subjectSampledAtMs,subjectAge)) &&
            (!obs.executionQualityRequired || obs.executionQuality?.isFresh(nowMs)==true)
        if(!valid && _state.value is State.Ready && obs!=null)hold(obs.copy(timestampMs=nowMs),GuidanceFeedback.INSUFFICIENT_EVIDENCE)
        return valid
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
        if (viewportVersion != input.viewportVersion) {
            resetCounters(resetFrameSequence = false)
            viewportVersion = input.viewportVersion
        }
        if (lastTrackingId != input.subjectTrackingId) {
            resetCounters(resetFrameSequence = false)
            lastTrackingId = input.subjectTrackingId
        }
        if ((lastAnchorKind != null && lastAnchorKind != input.observedAnchorKind) ||
            (lockedTarget != null && lockedTarget?.targetId != input.targetId &&
                (input.observedAnchorKind == ObservedAnchorKind.OBJECT || input.targetId?.startsWith("rule:")==true ||
                    lockedTarget?.targetId?.startsWith("rule:")==true))) {
            val preserveReplan = lastAnchorKind == input.observedAnchorKind && replanned
            resetCounters(resetFrameSequence = false)
            replanned=preserveReplan
            viewportVersion=input.viewportVersion
            lastTrackingId=input.subjectTrackingId
        }
        lastAnchorKind=input.observedAnchorKind
        lastTimestamp = input.timestampMs
        if(input.targetId == null) lockedTarget = null
        val requiresSubject = input.expectsSubject || input.hasRealSubject
        val requireRoll = input.observedAnchorKind != ObservedAnchorKind.OBJECT || input.rollObservable
        val validImu = input.imuObservationId > 0 && input.timestampMs >= input.imuSampledAtMs &&
            input.timestampMs-input.imuSampledAtMs <= 1500 && (!requireRoll || input.rollDegrees?.isFinite() == true)
        val maxSubjectAge = if(input.observedAnchorKind == ObservedAnchorKind.OBJECT) 1500 else 750
        val validSubject = !requiresSubject || ((input.observedAnchorKind == ObservedAnchorKind.OBJECT || input.faceCount == 1) && input.hasRealSubject &&
            input.subjectObservationId > 0 && input.timestampMs >= input.subjectSampledAtMs &&
            input.timestampMs-input.subjectSampledAtMs <= maxSubjectAge && input.targetViewportVersion == input.viewportVersion)
        if (!input.modelConfidence.isFinite() || input.modelConfidence <= 0f || !validImu || !validSubject || !input.qualityAcceptable) {
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
            if(quality==null || !quality.isFresh(input.timestampMs) || quality.viewportVersion!=input.viewportVersion || !comparableSubject) {
                hold(input,GuidanceFeedback.INSUFFICIENT_EVIDENCE)
                return
            }
            executionComparison=if(quality.observationId<=lastExecutionId) ExecutionComparison.CACHED else executionGuard.compare(quality)
            lastExecutionId=maxOf(lastExecutionId,quality.observationId)
            if(executionComparison==ExecutionComparison.CONTEXT_CHANGED || executionComparison==ExecutionComparison.UNATTRIBUTED_MOTION) {
                previousError=null;previousQuality=null;lastImprovedAt=input.timestampMs
                hold(input,GuidanceFeedback.SCENE_CHANGED)
                return
            }
            if(executionComparison==ExecutionComparison.QUALITY_REGRESSED) {
                // A closer target is not a success if the measured image becomes worse.
                pausedTarget=input.targetId ?: "${input.targetCx}:${input.targetCy}:${input.pitchTargetDegrees}:${input.yawTargetDegrees}"
                lockedTarget=null
                hold(input,GuidanceFeedback.WORSENING)
                return
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
            if ((requireRoll && (abs(input.rawRollDegrees ?: input.rollDegrees ?: Float.NaN) > horizonPolicy.enterLevelToleranceDeg || input.isUpright != true)) || (positionBad && wasVerifying))
                hold(input, GuidanceFeedback.INSUFFICIENT_EVIDENCE)
            else if(input.observedAnchorKind == ObservedAnchorKind.OBJECT && input.imuObservationId > lastImuId) {
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
            if (pausedTarget == incomingId || !replanned) { hold(input, _decision.value.feedback); return }
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
        val timedOut = nextAction !is GuidanceAction.Ready && nextAction !is GuidanceAction.HoldStill &&
            obs.timestampMs-lastImprovedAt >= 5000
        if (worseningCount >= 2 || timedOut) {
            val feedback = if (worseningCount >= 2) GuidanceFeedback.WORSENING else GuidanceFeedback.NO_PROGRESS
            val request = timedOut && !replanned
            if (request) replanned = true
            pausedTarget = incomingId
            lockedTarget = null
            hold(obs, feedback)
            _decision.value = GuidanceDecision(GuidanceAction.HoldStill, incomingId, error, feedback,
                obs.subjectObservationId, replanRequested = request)
            return
        }
        if (nextAction is GuidanceAction.Ready) {
            if (readySamples == 0) firstReadyAt = obs.timestampMs
            readySamples++
            if (readySamples >= 3 && obs.timestampMs-firstReadyAt >= 750) {
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
                else -> GuidanceFeedback.ADJUSTING }, obs.subjectObservationId,
            reasons=(executionGuard.lastReasons + obs.guidanceLimitations).distinct(),
            displayEvidence = if (nextAction !is GuidanceAction.HoldStill || _state.value is State.Verifying)
                displayEvidence(obs) else null)
        if(_state.value is State.Ready)lastCaptureEvidence=obs
    }

    private fun displayEvidence(obs: FrameObservation): GuidanceDisplayEvidence {
        fun point(x: Float?, y: Float?) = if (x != null && y != null && x.isFinite() && y.isFinite() &&
            x in 0f..1f && y in 0f..1f) NormalizedPoint(x, y) else null
        var expiry = minOf(obs.timestampMs + if (_state.value is State.Ready) 750L else 1500L,
            obs.imuSampledAtMs + 1500L)
        if (obs.expectsSubject || obs.hasRealSubject) expiry = minOf(expiry,
            obs.subjectSampledAtMs + if (obs.observedAnchorKind == ObservedAnchorKind.OBJECT) 1500L else 750L)
        if (obs.executionQualityRequired) expiry = minOf(expiry, (obs.executionQuality?.sampledAtMs ?: 0L) + 1500L)
        return GuidanceDisplayEvidence(obs.timestampMs, expiry, obs.viewportVersion, obs.observedAnchorKind,
            if (obs.hasRealSubject) point(obs.subjectCx, obs.subjectCy) else null,
            obs.subjectBox.takeIf { obs.hasRealSubject }, point(obs.targetCx, obs.targetCy),
            obs.targetTolerance, obs.rollDegrees?.takeIf { obs.rollObservable && it.isFinite() })
    }

    private fun hold(obs: FrameObservation, feedback: GuidanceFeedback) {
        readySamples = 0; firstReadyAt = 0
        levelGoodFrames = 0; positionGoodFrames = 0
        _state.value = State.Adjusting(GuidanceAction.HoldStill, 0f)
        _action.value = GuidanceAction.HoldStill
        _decision.value = GuidanceDecision(GuidanceAction.HoldStill, obs.targetId, measuredError(obs), feedback, obs.subjectObservationId,
            reasons=(executionGuard.lastReasons + obs.guidanceLimitations).distinct())
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

        // 3. 俯仰检查（水平已稳）
        val pitchDelta = GuidanceGeometry.angleDelta(obs.pitchDegrees, obs.pitchTargetDegrees)
        if (levelStable && pitchDelta != null &&
            abs(pitchDelta) > GuidanceGeometry.PITCH_THRESHOLD_DEG
        ) {
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
                    else -> {}
                }
            }
        }

        // Level alone cannot certify a pleasing composition. A non-subject
        // reference needs an explicit, comparable target pose to verify.
        if (levelStable && !obs.expectsSubject && !hasPositionEvidence(obs) &&
            (pitchDelta != null || yawDelta != null)
        ) {
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

    private fun resetCounters(resetFrameSequence: Boolean = true) {
        levelGoodFrames = 0
        levelBadFrames = 0
        positionGoodFrames = 0
        positionBadFrames = 0
        lastSubjectId = -1; lastImuId = -1; readySamples = 0; firstReadyAt = 0
        lockedTarget = null; previousError = null; previousQuality = null; worseningCount = 0
        lastImprovedAt = -1; pausedTarget = null; replanned = false; lastTimestamp = -1
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
    CompositionSessionController.GuidanceAction.DeviceUpsideDown -> GuidanceCue.DEVICE_UPSIDE_DOWN
    CompositionSessionController.GuidanceAction.Ready -> GuidanceCue.SUBJECT_ALIGNED
}
