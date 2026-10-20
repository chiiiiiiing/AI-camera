package com.example.aicamera.replay

import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.GuidanceFeedback
import com.example.aicamera.guidance.ObservedAnchorKind
import com.example.aicamera.zoom.AutoZoomRecommendationEngine
import com.example.aicamera.zoom.AutoZoomRecommendationStabilizer
import kotlin.math.abs

/**
 * 场景播放器 — 忠实复现真实拍摄流程，驱动真实构图状态机：
 *
 * ```
 * ANALYZING（1.5s 分析波纹，检索构图）
 *   → ADJUSTING：旋转（水平）→ 俯仰 → 水平转向 → 主体对齐目标区
 *      （多轴逐步收敛；「虚拟用户」每帧只响应当前真实指令，方向由状态机决定）
 *   → VERIFYING（稳定验证）→ READY
 *   → ZOOMING（真实 AutoZoomRecommendationEngine 推荐，稳定化后执行）
 *   → CAPTURING（自动拍摄）→ SAVED
 * ```
 *
 * 初始姿态：roll +7°、pitch +18°、yaw +15°、主体偏离目标点；
 * pitch/yaw 目标显式给 0°（与 App 姿态引导一致）。
 * 无真实主体（人像锚点/物体检测都没有）时，合成「演示主体」保证完整流程可看，
 * 来源会在 JSON 与网页上明确标注，不与真实检测混淆。
 */
object ScenePlayer {

    enum class Phase { ANALYZING, ROTATING, PITCHING, YAWING, MOVING, VERIFYING, READY, ZOOMING, CAPTURING, SAVED }

    data class FrameRecord(
        val frame: Int,
        val tMs: Long,
        val state: String,
        val phase: String,
        val action: String?,
        val feedback: String?,
        val rollDegrees: Float,
        val pitchDegrees: Float,
        val yawDegrees: Float,
        val zoomFactor: Float,
        val subjectX: Float?,
        val subjectY: Float?,
    )

    data class PlayResult(
        val records: List<FrameRecord>,
        val finalState: String,
        val readyAtFrame: Int?,
        val capturedAtFrame: Int?,
        val savedUri: String?,
        val statesVisited: List<String>,
        val phasesVisited: List<String>,
        val zoomRatio: Float?,
        val zoomReasons: List<String>,
    )

    private const val FRAME_MS = 250L
    private const val ANALYZE_FRAMES = 6        // 1.5s 分析
    private const val MAX_ADJUST_FRAMES = 48    // 12s 上限，防永不收敛
    private const val ZOOM_FRAMES = 4           // 1s 变焦动画
    private const val CAPTURE_FRAMES = 2
    private const val SAVED_FRAMES = 2

    private const val ROLL_START = 7f
    private const val PITCH_START = 18f
    private const val YAW_START = 15f
    private const val ROLL_STEP = 0.9f
    private const val ANGLE_STEP = 1.6f
    private const val POSITION_STEP = 0.025f

    fun play(engine: ReplayEngine.ImageResult): PlayResult {
        val controller = CompositionSessionController()
        val subject = engine.subject
        val confidence = engine.top5.firstOrNull()?.cosine ?: 0f

        val records = mutableListOf<FrameRecord>()
        val statesVisited = linkedSetOf<String>()
        val phasesVisited = linkedSetOf<Phase>()
        var readyAtFrame: Int? = null
        var capturedAtFrame: Int? = null
        var savedUri: String? = null
        var frame = 0
        var t = 0L

        // 分析阶段：状态机保持 Analyzing，等待 onAnalysisComplete
        controller.startAnalysis()
        for (a in 1..ANALYZE_FRAMES) {
            frame++
            t += FRAME_MS
            record(records, statesVisited, phasesVisited, frame, t, controller, Phase.ANALYZING, 0f, PITCH_START, YAW_START, 1f, null, null)
        }
        controller.onAnalysisComplete()

        // 虚拟设备姿态
        var roll = ROLL_START
        var pitch = PITCH_START
        var yaw = YAW_START
        var subX = subject?.startX ?: 0.5f
        var subY = subject?.startY ?: 0.5f
        val targetX = subject?.targetX ?: 0.5f
        val targetY = subject?.targetY ?: 0.5f
        val tolerance = subject?.tolerance ?: 0.08f

        // 变焦推荐（真实引擎 + 稳定化）
        val zoomEngine = AutoZoomRecommendationEngine()
        val zoomStabilizer = AutoZoomRecommendationStabilizer()
        val zoomRec = zoomStabilizer.stabilize(zoomEngine.recommend(engine.hits, engine.stats))
        val zoomTarget = if (zoomRec.shouldAutoZoom) zoomRec.suggestedZoomRatio.coerceIn(1f, 3f) else 1f
        var zoom = 1f
        var zoomDone = false
        var captureStarted = false

        var adjustCount = 0
        while (adjustCount < MAX_ADJUST_FRAMES && controller.state.value !is CompositionSessionController.State.Saved) {
            adjustCount++
            val state = controller.state.value
            when (state) {
                is CompositionSessionController.State.Adjusting, is CompositionSessionController.State.Verifying, is CompositionSessionController.State.Ready -> {
                    // 进入 Ready 后先执行变焦，再拍摄
                    if (state is CompositionSessionController.State.Ready) {
                        if (readyAtFrame == null) readyAtFrame = frame
                        if (!zoomDone) {
                            if (zoomTarget > 1.001f) {
                                for (z in 1..ZOOM_FRAMES) {
                                    frame++
                                    t += FRAME_MS
                                    zoom = 1f + (zoomTarget - 1f) * (z.toFloat() / ZOOM_FRAMES)
                                    record(records, statesVisited, phasesVisited, frame, t, controller, Phase.ZOOMING, roll, pitch, yaw, zoom, subX, subY)
                                }
                            }
                            zoomDone = true
                            controller.confirmReadyForCapture(t)
                            controller.startCapture()
                            capturedAtFrame = frame + 1
                            continue
                        }
                    } else {
                        frame++
                        t += FRAME_MS
                        // 喂真实观测
                        val obs = CompositionSessionController.FrameObservation(
                            frameId = frame.toLong(),
                            timestampMs = t,
                            rollDegrees = roll,
                            rawRollDegrees = roll,
                            pitchDegrees = pitch,
                            pitchTargetDegrees = 0f,
                            yawDegrees = yaw,
                            yawTargetDegrees = 0f,
                            isUpright = true,
                            modelConfidence = confidence,
                            expectsSubject = subject != null,
                            hasRealSubject = subject != null,
                            faceCount = if (subject?.source == ReplayEngine.SubjectInfo.SOURCE_FACE) 1 else 0,
                            subjectCx = subX,
                            subjectCy = subY,
                            targetCx = targetX,
                            targetCy = targetY,
                            targetTolerance = tolerance,
                            subjectObservationId = frame.toLong(),
                            subjectSampledAtMs = t,
                            subjectTrackingId = 1,
                            targetId = "replay-target",
                            imuObservationId = frame.toLong(),
                            imuSampledAtMs = t,
                            observedAnchorKind = if (subject?.source == ReplayEngine.SubjectInfo.SOURCE_FACE) ObservedAnchorKind.FACE else ObservedAnchorKind.OBJECT,
                            rollObservable = true,
                        )
                        controller.observe(obs)
                        // 虚拟用户：只响应当前真实指令
                        when (val action = controller.action.value) {
                            CompositionSessionController.GuidanceAction.RotateCounterClockwise -> roll = approach(roll, 0f, ROLL_STEP)
                            CompositionSessionController.GuidanceAction.RotateClockwise -> roll = approach(roll, 0f, ROLL_STEP)
                            CompositionSessionController.GuidanceAction.PitchDown, CompositionSessionController.GuidanceAction.PitchUp -> pitch = approach(pitch, 0f, ANGLE_STEP)
                            CompositionSessionController.GuidanceAction.YawLeft, CompositionSessionController.GuidanceAction.YawRight -> yaw = approach(yaw, 0f, ANGLE_STEP)
                            CompositionSessionController.GuidanceAction.MoveLeft -> subX = approach(subX, targetX, POSITION_STEP)
                            CompositionSessionController.GuidanceAction.MoveRight -> subX = approach(subX, targetX, POSITION_STEP)
                            CompositionSessionController.GuidanceAction.MoveUp -> subY = approach(subY, targetY, POSITION_STEP)
                            CompositionSessionController.GuidanceAction.MoveDown -> subY = approach(subY, targetY, POSITION_STEP)
                            CompositionSessionController.GuidanceAction.MoveUpLeft,
                            CompositionSessionController.GuidanceAction.MoveUpRight,
                            CompositionSessionController.GuidanceAction.MoveDownLeft,
                            CompositionSessionController.GuidanceAction.MoveDownRight -> {
                                subX = approach(subX, targetX, POSITION_STEP)
                                subY = approach(subY, targetY, POSITION_STEP)
                            }
                            else -> {}
                        }
                        val phase = phaseOf(controller.action.value, controller.state.value)
                        record(records, statesVisited, phasesVisited, frame, t, controller, phase, roll, pitch, yaw, zoom, subX, subY)
                        if (state is CompositionSessionController.State.Ready && readyAtFrame == null) readyAtFrame = frame
                    }
                }
                is CompositionSessionController.State.Capturing -> {
                    if (!captureStarted) {
                        captureStarted = true
                        for (c in 1..CAPTURE_FRAMES) {
                            frame++
                            t += FRAME_MS
                            record(records, statesVisited, phasesVisited, frame, t, controller, Phase.CAPTURING, roll, pitch, yaw, zoom, subX, subY)
                        }
                        controller.onCaptureComplete("replay://saved/${engine.imageName}")
                    }
                    // 状态仍为 Capturing（本帧内已调用 onCaptureComplete 后进入 Saved）
                    val now = controller.state.value
                    if (now is CompositionSessionController.State.Saved) savedUri = now.uri
                }
                is CompositionSessionController.State.Saved -> savedUri = state.uri
                else -> { adjustCount = MAX_ADJUST_FRAMES }
            }
        }

        val final = controller.state.value
        if (final is CompositionSessionController.State.Saved) savedUri = final.uri
        if (savedUri != null) {
            for (s in 1..SAVED_FRAMES) {
                frame++
                t += FRAME_MS
                record(records, statesVisited, phasesVisited, frame, t, controller, Phase.SAVED, roll, pitch, yaw, zoom, subX, subY)
            }
        }

        return PlayResult(
            records = records,
            finalState = stateName(final),
            readyAtFrame = readyAtFrame,
            capturedAtFrame = capturedAtFrame,
            savedUri = savedUri,
            statesVisited = statesVisited.toList(),
            phasesVisited = phasesVisited.map { it.name },
            zoomRatio = if (zoomTarget > 1.001f) zoomTarget else null,
            zoomReasons = zoomRec.reasons.map { it.name },
        )
    }

    private fun record(
        records: MutableList<FrameRecord>,
        statesVisited: MutableSet<String>,
        phasesVisited: MutableSet<Phase>,
        frame: Int, t: Long,
        controller: CompositionSessionController,
        phase: Phase,
        roll: Float, pitch: Float, yaw: Float, zoom: Float,
        subX: Float?, subY: Float?,
    ) {
        val state = controller.state.value
        statesVisited += stateName(state)
        phasesVisited += phase
        val decision = controller.decision.value
        records += FrameRecord(
            frame = frame,
            tMs = t,
            state = stateName(state),
            phase = phase.name,
            action = when (state) {
                is CompositionSessionController.State.Adjusting -> actionName(state.action)
                else -> actionName(controller.action.value)
            },
            feedback = decision.feedback.takeUnless { it == GuidanceFeedback.INSUFFICIENT_EVIDENCE }?.name,
            rollDegrees = roll,
            pitchDegrees = pitch,
            yawDegrees = yaw,
            zoomFactor = zoom,
            subjectX = subX,
            subjectY = subY,
        )
    }

    private fun phaseOf(action: CompositionSessionController.GuidanceAction, state: CompositionSessionController.State): Phase = when {
        state is CompositionSessionController.State.Verifying -> Phase.VERIFYING
        state is CompositionSessionController.State.Ready -> Phase.READY
        action is CompositionSessionController.GuidanceAction.RotateClockwise || action is CompositionSessionController.GuidanceAction.RotateCounterClockwise -> Phase.ROTATING
        action is CompositionSessionController.GuidanceAction.PitchUp || action is CompositionSessionController.GuidanceAction.PitchDown -> Phase.PITCHING
        action is CompositionSessionController.GuidanceAction.YawLeft || action is CompositionSessionController.GuidanceAction.YawRight -> Phase.YAWING
        action is CompositionSessionController.GuidanceAction.MoveLeft || action is CompositionSessionController.GuidanceAction.MoveRight ||
            action is CompositionSessionController.GuidanceAction.MoveUp || action is CompositionSessionController.GuidanceAction.MoveDown ||
            action is CompositionSessionController.GuidanceAction.MoveUpLeft || action is CompositionSessionController.GuidanceAction.MoveUpRight ||
            action is CompositionSessionController.GuidanceAction.MoveDownLeft || action is CompositionSessionController.GuidanceAction.MoveDownRight -> Phase.MOVING
        else -> Phase.VERIFYING
    }

    private fun approach(value: Float, target: Float, step: Float): Float = when {
        value > target -> (value - step).coerceAtLeast(target)
        value < target -> (value + step).coerceAtMost(target)
        else -> value
    }

    private fun stateName(state: CompositionSessionController.State): String = when (state) {
        is CompositionSessionController.State.Idle -> "Idle"
        is CompositionSessionController.State.Analyzing -> "Analyzing"
        is CompositionSessionController.State.Adjusting -> "Adjusting"
        is CompositionSessionController.State.Verifying -> "Verifying"
        is CompositionSessionController.State.Ready -> "Ready"
        is CompositionSessionController.State.Capturing -> "Capturing"
        is CompositionSessionController.State.Saved -> "Saved"
        is CompositionSessionController.State.Error -> "Error"
    }

    private fun actionName(action: CompositionSessionController.GuidanceAction): String = when (action) {
        CompositionSessionController.GuidanceAction.HoldStill -> "HoldStill"
        CompositionSessionController.GuidanceAction.RotateClockwise -> "RotateClockwise"
        CompositionSessionController.GuidanceAction.RotateCounterClockwise -> "RotateCounterClockwise"
        CompositionSessionController.GuidanceAction.PitchUp -> "PitchUp"
        CompositionSessionController.GuidanceAction.PitchDown -> "PitchDown"
        CompositionSessionController.GuidanceAction.YawLeft -> "YawLeft"
        CompositionSessionController.GuidanceAction.YawRight -> "YawRight"
        CompositionSessionController.GuidanceAction.MoveLeft -> "MoveLeft"
        CompositionSessionController.GuidanceAction.MoveRight -> "MoveRight"
        CompositionSessionController.GuidanceAction.MoveUp -> "MoveUp"
        CompositionSessionController.GuidanceAction.MoveDown -> "MoveDown"
        CompositionSessionController.GuidanceAction.MoveUpLeft -> "MoveUpLeft"
        CompositionSessionController.GuidanceAction.MoveUpRight -> "MoveUpRight"
        CompositionSessionController.GuidanceAction.MoveDownLeft -> "MoveDownLeft"
        CompositionSessionController.GuidanceAction.MoveDownRight -> "MoveDownRight"
        CompositionSessionController.GuidanceAction.DeviceUpsideDown -> "DeviceUpsideDown"
        CompositionSessionController.GuidanceAction.Ready -> "Ready"
    }
}
