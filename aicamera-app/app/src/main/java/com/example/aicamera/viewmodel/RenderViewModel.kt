package com.example.aicamera.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.GuidanceState
import com.example.aicamera.guidance.GuidanceStateMachine
import com.example.aicamera.guidance.GuidanceStepSequencer
import com.example.aicamera.overlay.model.GuideLine
import com.example.aicamera.overlay.model.GuideLineType
import com.example.aicamera.overlay.model.RenderInput
import com.example.aicamera.pipeline.FakeInferenceOutput
import com.example.aicamera.pipeline.PipelineResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 渲染 ViewModel — 连接帧处理管线输出 → 辅助线渲染引擎。
 *
 * 注意：FramePreprocessor 已把帧转正（并按前摄做镜像）后再送推理，
 * 因此所有辅助线坐标都是「显示坐标系」归一化值，
 * 下发给渲染层时 rotationDegrees 固定为 0（见 P1-7 修复）。
 */
@HiltViewModel
class RenderViewModel @Inject constructor() : ViewModel() {

    private val _renderInput = MutableStateFlow(RenderInput())
    val renderInput: StateFlow<RenderInput> = _renderInput.asStateFlow()

    private val _renderStats = MutableStateFlow(RenderStats())
    val renderStats: StateFlow<RenderStats> = _renderStats.asStateFlow()

    /** ★ 新引导系统状态（与队友 GuidanceCue 对齐） */
    private val guidanceStateMachine = GuidanceStateMachine()
    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    // ── ★ 分步引导序列器（自动变焦 → 水平 → 主体 → 完成） ──
    private val stepSequencer = GuidanceStepSequencer()

    // ── ★ 统一构图会话控制器（替代双工作流，唯一状态机） ──
    val sessionController = CompositionSessionController()

    private val _guidanceStep = MutableStateFlow(GuidanceStepSequencer.GuidanceStep.ZOOM)
    val guidanceStep: StateFlow<GuidanceStepSequencer.GuidanceStep> = _guidanceStep.asStateFlow()

    /** 当前步骤的一句话提示（"端平手机"等），空串 = 不显示 */
    private val _stepHint = MutableStateFlow("")
    val stepHint: StateFlow<String> = _stepHint.asStateFlow()

    /** 自动变焦目标倍率事件（一次性；UI 消费后执行变焦斜坡） */
    private val _autoZoomTarget = MutableSharedFlow<Float>(replay = 0, extraBufferCapacity = 1)
    val autoZoomTarget: SharedFlow<Float> = _autoZoomTarget.asSharedFlow()

    /** 当前变焦状态（由 UI 层推送，序列器决策用） */
    @Volatile private var currentZoomRatio: Float = 1.0f
    @Volatile private var minZoomRatio: Float = 1.0f
    @Volatile private var maxZoomRatio: Float = 4.0f

    /** 上一帧场景：场景持续变化才重置分步引导（帧间抖动不重置） */
    @Volatile private var lastSceneKey: String? = null
    @Volatile private var pendingSceneKey: String? = null
    @Volatile private var pendingSceneFrames: Int = 0

    data class RenderStats(
        val frameCount: Long = 0,
        val currentScene: String = "unknown",
        val confidence: Float = 0f,
        val guideLineCount: Int = 0
    )

    /** ★ 防止重复 connect */
    private var collectJob: Job? = null

    /** Scheduler frame counters restart on resume; controller observations must keep increasing. */
    private var observationFrameId: Long = 0

    fun connect(pipelineResults: SharedFlow<PipelineResult>) {
        if (collectJob?.isActive == true) return

        collectJob = viewModelScope.launch {
            pipelineResults.collect { result ->
                val input = parseResult(result)

                // ★ 未知输出时保留上一帧有效输入，避免 UI 闪烁
                if (input.guideLines.isNotEmpty() || input.sceneType != "unknown") {
                    _renderInput.value = input
                } else if (_renderInput.value.guideLines.isEmpty()) {
                    // 首帧有效输入还没到，暂时传空
                    _renderInput.value = input
                }
                // 否则：保留上一帧有效的 RenderInput，不更新

                _renderStats.update {
                    RenderStats(
                        frameCount = result.frameIndex,
                        currentScene = input.sceneType,
                        confidence = input.confidence,
                        guideLineCount = input.guideLines.size
                    )
                }

                // ★ 更新引导状态机（经分步序列器过滤：一屏只出一个指令）
                val fakeOutput = result.modelOutput as? FakeInferenceOutput
                if (fakeOutput != null) {
                    maybeResetStepsForSceneChange(fakeOutput, result.isFrontCamera)

                    val seqOut = stepSequencer.onFrame(
                        input = sequencerInput(fakeOutput),
                        rawCue = fakeOutput.guidanceCue,
                        rawProgress = fakeOutput.compositionProgress
                    )
                    _guidanceStep.value = seqOut.step
                    _stepHint.value = seqOut.hint
                    seqOut.zoomTarget?.let { target ->
                        _autoZoomTarget.tryEmit(target)
                    }

                    val newGuidance = guidanceStateMachine.reduceFrame(
                        current = _guidanceState.value,
                        cue = seqOut.cue,
                        progress = seqOut.progress,
                        subjectOutline = null,
                        anchors = null,
                    )
                    _guidanceState.value = newGuidance

                    // ★ 同步驱动统一会话控制器（替代双工作流）
                    sessionController.observe(buildFrameObservation(fakeOutput, result))
                } else {
                    // A pipeline failure/unknown schema must revoke readiness immediately,
                    // including when no later frame arrives to clear ALIGNED hysteresis.
                    resetGuidanceSteps()
                    _guidanceState.value = GuidanceState()
                    _stepHint.value = "AI 暂未就绪，可直接拍摄"
                    _renderInput.value = input
                    // Reuse the controller's zero-confidence fallback with no invented subject,
                    // target or sensor readings. Keep Capturing/Saved handling in the controller.
                    sessionController.observe(CompositionSessionController.FrameObservation(
                        frameId = nextObservationFrameId(result),
                        timestampMs = System.currentTimeMillis(),
                        modelConfidence = 0f
                    ))
                }
            }
        }
    }

    // ── 分步引导外部接口 ─────────────────────────────────────

    /** UI 推送当前变焦倍率（序列器决策/到位判定的依据） */
    fun onZoomRatioChanged(ratio: Float) {
        currentZoomRatio = ratio
    }

    /** UI 推送硬件变焦范围 */
    fun onZoomRangeAvailable(minZoom: Float, maxZoom: Float) {
        minZoomRatio = minZoom
        maxZoomRatio = maxZoom
    }

    /** UI 把自动变焦打到目标值后回调（开始稳定计时） */
    fun notifyAutoZoomApplied() {
        stepSequencer.notifyZoomApplied(System.currentTimeMillis())
    }

    /** 用户手动变焦（捏合/滑杆）→ 取消本次自动变焦 */
    fun cancelAutoZoom() {
        stepSequencer.cancelAutoZoom()
    }

    /** 切摄像头/重新进入相机时重开分步引导 */
    fun resetGuidanceSteps() {
        lastSceneKey = null
        pendingSceneKey = null
        pendingSceneFrames = 0
        stepSequencer.reset(System.currentTimeMillis())
        _guidanceStep.value = GuidanceStepSequencer.GuidanceStep.ZOOM
    }

    companion object {
        /** 场景防抖帧数：新场景需稳定约 4.5s（10fps × 45）才重开引导 */
        private const val SCENE_RESET_FRAMES = 45
    }

    /**
     * 场景重置防抖（★ 修复：推理场景帧间抖动导致序列器每帧被重置、
     * 引导永远卡在第①步、用户看不到任何方向指令）。
     *
     * 只有新场景**连续稳定 [SCENE_RESET_FRAMES] 帧**才认为是真切换并重开引导；
     * 摄像头切换不经过这里（CameraScreen 显式调 [resetGuidanceSteps]）。
     */
    private fun maybeResetStepsForSceneChange(output: FakeInferenceOutput, isFrontCamera: Boolean) {
        val sceneKey = "${output.sceneType}|$isFrontCamera"
        val last = lastSceneKey
        when {
            last == null -> {
                lastSceneKey = sceneKey
                stepSequencer.reset(System.currentTimeMillis())
            }
            sceneKey == last -> {
                pendingSceneKey = null
                pendingSceneFrames = 0
            }
            sceneKey == pendingSceneKey -> {
                pendingSceneFrames++
                if (pendingSceneFrames >= SCENE_RESET_FRAMES) {
                    lastSceneKey = sceneKey
                    pendingSceneKey = null
                    pendingSceneFrames = 0
                    stepSequencer.reset(System.currentTimeMillis())
                    Log.i(CameraConfig.TAG, "[RenderVM] 场景稳定切换，重开分步引导")
                }
            }
            else -> {
                pendingSceneKey = sceneKey
                pendingSceneFrames = 1
            }
        }
    }

    /** 从管线输出适配序列器输入（水平角/主体占比来自几何阶段注入的辅助线） */
    private fun sequencerInput(output: FakeInferenceOutput): GuidanceStepSequencer.Input {
        var angle: Float? = null
        var isUpright: Boolean? = null
        var subjectFrac: Float? = null
        var hasSubject = false
        for (line in output.guideLines) {
            when (line.type) {
                "horizon_level" -> {
                    angle = (line.params["angle_degrees"] as? Number)?.toFloat()
                    // ★ 提取 is_upright 标记（由 GeometryGuidanceStage 从传感器注入）
                    isUpright = line.params["is_upright"] as? Boolean
                }
                "subject_box" -> {
                    // ★ 只信真实检测框（real=true）：模板占位框会让
                    //   自动变焦对着假主体算出「无需变焦」的错误结论
                    val isReal = line.params["real"] == true
                    val h = (line.params["height"] as? Number)?.toFloat()
                    if (isReal && h != null && h > 0f) {
                        subjectFrac = h
                        hasSubject = true
                    }
                }
            }
        }
        return GuidanceStepSequencer.Input(
            horizonAngleDeg = angle,
            isUpright = isUpright,
            subjectHeightFraction = subjectFrac,
            hasSubject = hasSubject,
            expectsSubject = output.expectsSubject,
            currentZoomRatio = currentZoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
            nowMs = System.currentTimeMillis(),
            recommendationReady = output.confidence.isFinite() && output.confidence > 0f
        )
    }

    private fun parseResult(result: PipelineResult): RenderInput {        return when (val output = result.modelOutput) {
            is FakeInferenceOutput -> parseFakeOutput(output, result)
            null -> RenderInput(
                isFrontCamera = result.isFrontCamera,
                rotationDegrees = 0
            )
            else -> {
                Log.w(CameraConfig.TAG, "[RenderVM] 未知模型输出类型: ${output::class.simpleName}")
                // ★ 保留 isFrontCamera，但不输出辅助线（等模型适配）
                RenderInput(
                    sceneType = "unknown",
                    isFrontCamera = result.isFrontCamera,
                    rotationDegrees = 0
                )
            }
        }
    }

    private fun parseFakeOutput(
        output: FakeInferenceOutput,
        result: PipelineResult
    ): RenderInput {
        val guideLines = output.guideLines.map { fakeLine ->
            GuideLine(
                type = GuideLineType.fromKey(fakeLine.type),
                visible = fakeLine.visible,
                params = fakeLine.params
            )
        }

        return RenderInput(
            sceneType = output.sceneType.name.lowercase(),
            compositionStrategy = output.compositionStrategy.name.lowercase(),
            confidence = output.confidence,
            guideLines = guideLines,
            isFrontCamera = result.isFrontCamera,
            // 帧已在预处理阶段转正 + 镜像，辅助线坐标即显示坐标
            rotationDegrees = 0
        )
    }

    private fun nextObservationFrameId(result: PipelineResult): Long {
        observationFrameId = maxOf(observationFrameId + 1, result.frameIndex)
        return observationFrameId
    }

    /** 从管线输出构建统一会话控制器的帧观测 */
    private fun buildFrameObservation(
        output: FakeInferenceOutput,
        result: PipelineResult
    ): CompositionSessionController.FrameObservation {
        var roll: Float? = null
        var pitch: Float? = null
        var yaw: Float? = null
        var isUpright: Boolean? = null
        var subjectCx: Float? = null
        var subjectCy: Float? = null
        var hasRealSubject = false
        var targetCx: Float? = null
        var targetCy: Float? = null
        var targetTol = 0.12f

        for (line in output.guideLines) {
            when (line.type) {
                "horizon_level" -> {
                    roll = (line.params["angle_degrees"] as? Number)?.toFloat()
                    pitch = (line.params["pitch_degrees"] as? Number)?.toFloat()
                    yaw = (line.params["yaw_degrees"] as? Number)?.toFloat()
                    isUpright = line.params["is_upright"] as? Boolean
                }
                "subject_box" -> {
                    val isReal = line.params["real"] == true
                    if (isReal) {
                        val x = (line.params["x"] as? Number)?.toFloat()
                        val y = (line.params["y"] as? Number)?.toFloat()
                        val w = (line.params["width"] as? Number)?.toFloat()
                        val h = (line.params["height"] as? Number)?.toFloat()
                        if (x != null && y != null && w != null && h != null) {
                            subjectCx = x + w / 2f
                            subjectCy = y + h / 2f
                            hasRealSubject = true
                        }
                    }
                }
                "target_zone" -> {
                    targetCx = (line.params["center_x"] as? Number)?.toFloat()
                    targetCy = (line.params["center_y"] as? Number)?.toFloat()
                    targetTol = (line.params["radius"] as? Number)?.toFloat() ?: 0.12f
                }
            }
        }

        return CompositionSessionController.FrameObservation(
            frameId = nextObservationFrameId(result),
            timestampMs = System.currentTimeMillis(),
            rollDegrees = roll,
            pitchDegrees = pitch,
            yawDegrees = yaw,
            isUpright = isUpright,
            subjectCx = subjectCx,
            subjectCy = subjectCy,
            targetCx = targetCx,
            targetCy = targetCy,
            targetTolerance = targetTol,
            expectsSubject = output.expectsSubject,
            hasRealSubject = hasRealSubject,
            modelConfidence = output.confidence
        )
    }
}
