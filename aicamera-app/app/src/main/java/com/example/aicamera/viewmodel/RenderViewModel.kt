package com.example.aicamera.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.GuidanceState
import com.example.aicamera.guidance.GuidanceStepSequencer
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.guidance.GuidancePhase
import com.example.aicamera.guidance.GuidanceAnchors
import com.example.aicamera.guidance.NormalizedPoint
import com.example.aicamera.guidance.toGuidanceCue
import com.example.aicamera.guidance.geometry.HandheldHorizonPolicy
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

    /** UI projection of the session's single composition verdict. */
    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    // Bounded zoom preparation; subsequent composition belongs to sessionController.
    // 手持容差：±6° 进入 ±9° 退出、连续 3 帧稳定。仅放宽 App 实时引导，
    // 核心 HorizonPolicy 默认值保持严格（测试与历史语义不变）。
    private val stepSequencer = GuidanceStepSequencer(HandheldHorizonPolicy)

    // ── ★ 统一构图会话控制器（替代双工作流，唯一状态机） ──
    val sessionController = CompositionSessionController(HandheldHorizonPolicy)
    private val subjectContinuity = com.example.aicamera.guidance.SubjectContinuity()
    private var lastSubjectInput: RenderInput? = null
    fun needsSubjectSelection(): Boolean = _guidanceState.value.trackingPaused || _guidanceState.value.anchors?.current == null

    private val _guidanceStep = MutableStateFlow(GuidanceStepSequencer.GuidanceStep.ZOOM)
    val guidanceStep: StateFlow<GuidanceStepSequencer.GuidanceStep> = _guidanceStep.asStateFlow()

    /** 当前步骤的一句话提示（"端平手机"等），空串 = 不显示 */
    private val _stepHint = MutableStateFlow("")
    val stepHint: StateFlow<String> = _stepHint.asStateFlow()

    /** 自动变焦目标倍率事件（一次性；UI 消费后执行变焦斜坡） */
    data class AutoZoomRequest(val ratio: Float, val generation: Long)
    private var zoomGeneration = 0L
    private var zoomReferenceSeen = false
    private var zoomPreparationFinished = false
    private val _autoZoomTarget = MutableSharedFlow<AutoZoomRequest>(replay = 0, extraBufferCapacity = 1)
    val autoZoomTarget: SharedFlow<AutoZoomRequest> = _autoZoomTarget.asSharedFlow()
    fun isCurrentAutoZoom(request: AutoZoomRequest): Boolean = request.generation == zoomGeneration

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

    fun connect(pipelineResults: SharedFlow<PipelineResult>, onRequestReplan: () -> Unit = {}) {
        if (collectJob?.isActive == true) return

        collectJob = viewModelScope.launch {
            pipelineResults.collect { result ->
                // Apply handheld tolerance once, to both rendered targets and
                // controller observations. Displayed and accepted zones must agree.
                val fakeOutput = (result.modelOutput as? FakeInferenceOutput)?.forHandheldGuidance()
                val input = parseResult(result.copy(modelOutput = fakeOutput ?: result.modelOutput))

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
                if (fakeOutput != null) {
                    maybeResetStepsForSceneChange(fakeOutput, result.isFrontCamera)
                    val activeSession = sessionController.state.value.let {
                        it is CompositionSessionController.State.Adjusting || it is CompositionSessionController.State.Verifying ||
                            it is CompositionSessionController.State.Ready
                    }
                    val zoomInput = sequencerInput(fakeOutput)
                    if (!zoomReferenceSeen && zoomInput.targetSubjectHeightFraction != null && zoomInput.hasSubject) {
                        zoomReferenceSeen = true
                        if (!activeSession && stepSequencer.step != GuidanceStepSequencer.GuidanceStep.ZOOM) {
                            zoomGeneration++
                            stepSequencer.reset(android.os.SystemClock.elapsedRealtime())
                            zoomPreparationFinished = false
                        }
                    }

                    // The sequencer performs only the bounded zoom preparation.
                    // The session controller owns every composition action and Ready.
                    val seqOut = if (!zoomPreparationFinished) stepSequencer.onFrame(
                        input = zoomInput,
                        rawCue = fakeOutput.guidanceCue,
                        rawProgress = fakeOutput.compositionProgress
                    ) else null
                    if (stepSequencer.step != GuidanceStepSequencer.GuidanceStep.ZOOM) zoomPreparationFinished = true
                    seqOut?.zoomTarget?.let { target ->
                        _autoZoomTarget.tryEmit(AutoZoomRequest(target, zoomGeneration))
                    }
                    val zoomPending = !zoomPreparationFinished
                    val visual = fakeOutput.visualSubjectBox?.takeIf {
                        android.os.SystemClock.elapsedRealtime() - fakeOutput.visualSampledAtMs in 0..750
                    }
                    val measured = buildFrameObservation(fakeOutput, result).copy(zoomInProgress = zoomPending, targetDwellCapture = true)
                    val tracked = if (visual != null && fakeOutput.visualSampledAtMs > measured.subjectSampledAtMs)
                        measured.copy(subjectCx = visual.centerX, subjectCy = visual.centerY, hasRealSubject = false,
                            trackingPaused = true, visualPositionAvailable = true, visualPositionSampledAtMs = fakeOutput.visualSampledAtMs)
                        else measured
                    val observation = subjectContinuity.update(tracked,
                        keepSession = activeSession, visualBox = visual, visualSampledAtMs = fakeOutput.visualSampledAtMs)
                    if (observation.trackingPaused) {
                        (lastSubjectInput ?: input).let { last ->
                            val subjectTypes = setOf(GuideLineType.SUBJECT_BOX, GuideLineType.TARGET_ZONE)
                            _renderInput.value = input.copy(guideLines = input.guideLines.filterNot { it.type in subjectTypes } +
                                last.guideLines.filter { it.type in subjectTypes }.map { line ->
                                    if (line.type == GuideLineType.SUBJECT_BOX && visual != null) line.copy(params = line.params + mapOf(
                                        "x" to visual.left, "y" to visual.top, "width" to visual.width, "height" to visual.height,
                                        "real" to false, "visual_tracking" to true)) else line
                                })
                            if (visual != null) lastSubjectInput = _renderInput.value
                        }
                    } else if (observation.hasRealSubject && observation.targetCx != null) lastSubjectInput = input
                    if (observation.targetCx != null && observation.targetCy != null) _renderInput.value = _renderInput.value.copy(
                        guideLines = _renderInput.value.guideLines.map { line ->
                            if (line.type == GuideLineType.TARGET_ZONE) line.copy(params = line.params + mapOf(
                                "center_x" to observation.targetCx, "center_y" to observation.targetCy,
                                "radius" to observation.targetTolerance)) else line
                        })
                    sessionController.observe(observation)
                    val state = sessionController.state.value
                    val cue = com.example.aicamera.guidance.geometry.GuidanceGeometry.displayPhoneCue(
                        sessionController.action.value.toGuidanceCue(), result.isFrontCamera)
                    val ready = state is CompositionSessionController.State.Ready
                    _guidanceStep.value = when {
                        ready -> GuidanceStepSequencer.GuidanceStep.DONE
                        zoomPending -> GuidanceStepSequencer.GuidanceStep.ZOOM
                        cue in listOf(GuidanceCue.ROLL_CLOCKWISE, GuidanceCue.ROLL_COUNTERCLOCKWISE,
                            GuidanceCue.DEVICE_UPSIDE_DOWN) -> GuidanceStepSequencer.GuidanceStep.LEVEL
                        else -> GuidanceStepSequencer.GuidanceStep.SUBJECT
                    }
                    _stepHint.value = when {
                        ready -> "目标圈内已保持 1 秒，正在准备拍摄"
                        state is CompositionSessionController.State.Verifying -> "已进入目标圈，保持约 1 秒自动拍摄"
                        observation.trackingPaused && observation.visualPositionAvailable && cue == GuidanceCue.NONE -> "进入目标圈后保持约 1 秒自动拍摄"
                        observation.trackingPaused && observation.visualPositionAvailable -> "按目标继续移动 · 画面跟踪中"
                        observation.trackingPaused -> "保留目标取景，可按快门完成拍摄"
                        zoomPending -> seqOut?.hint.orEmpty()
                        !fakeOutput.aiInvolved -> "AI 暂未就绪，可按快门直接拍摄"
                        observation.targetCx == null -> "轻点画面中的主体可直接锁定取景"
                        !observation.hasRealSubject -> "主体暂时丢失，请让主体回到画面中"
                        else -> fakeOutput.aiCompositionMethod.orEmpty()
                    }
                    _guidanceState.value = GuidanceState(
                        cue = if (zoomPending) GuidanceCue.NONE else cue,
                        phase = when {
                            ready -> GuidancePhase.ALIGNED
                            state is CompositionSessionController.State.Verifying -> GuidancePhase.NEAR
                            cue != GuidanceCue.NONE && !zoomPending -> GuidancePhase.ADJUST
                            else -> GuidancePhase.NEUTRAL
                        },
                        progress = if (ready) 1f else (state as? CompositionSessionController.State.Adjusting)?.progress ?: 0f,
                        anchors = GuidanceAnchors(
                            observation.subjectCx?.let { x -> observation.subjectCy?.let { NormalizedPoint(x, it) } },
                            observation.targetCx?.let { x -> observation.targetCy?.let { NormalizedPoint(x, it) } }
                        ),
                        trackingPaused = observation.trackingPaused
                    )
                    if (sessionController.decision.value.replanRequested) onRequestReplan()
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
                        timestampMs = android.os.SystemClock.elapsedRealtime(),
                        modelConfidence = 0f
                    ))
                }
            }
        }
    }

    private fun FakeInferenceOutput.forHandheldGuidance(): FakeInferenceOutput {
        fun radius(value: Float): Float = if (value.isFinite() && value > 0f) maxOf(.12f, value) else value
        if (manualTarget != null) {
            val lines = guideLines.filterNot { it.type == "subject_box" || it.type == "target_zone" }.toMutableList()
            lines += com.example.aicamera.pipeline.FakeGuideLine("target_zone", true, mapOf(
                "center_x" to manualTarget.x, "center_y" to manualTarget.y, "radius" to .12f, "anchor_kind" to "manual"))
            visualSubjectBox?.let { box -> lines += com.example.aicamera.pipeline.FakeGuideLine("subject_box", true, mapOf(
                "x" to box.left, "y" to box.top, "width" to box.width, "height" to box.height,
                "real" to false, "visual_tracking" to true, "anchor_kind" to "manual")) }
            return copy(guideLines = lines, aiCompositionMethod = "手动锁定区域 · 画面跟踪与规则取景")
        }
        return copy(
            compositionTarget = compositionTarget?.let { it.copy(tolerance = radius(it.tolerance)) },
            objectTarget = objectTarget?.let { it.copy(tolerance = radius(it.tolerance)) },
            guideLines = guideLines.map { line ->
                val value = (line.params["radius"] as? Number)?.toFloat()
                if (line.type == "target_zone" && value != null)
                    line.copy(params = line.params + ("radius" to radius(value))) else line
            }
        )
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
    fun notifyAutoZoomApplied(request: AutoZoomRequest) {
        if (isCurrentAutoZoom(request)) stepSequencer.notifyZoomApplied(android.os.SystemClock.elapsedRealtime())
    }

    /** 用户手动变焦（捏合/滑杆）→ 取消本次自动变焦 */
    fun cancelAutoZoom() {
        zoomPreparationFinished = true
        zoomReferenceSeen = true
        zoomGeneration++
        stepSequencer.cancelAutoZoom()
    }

    /** 切摄像头/重新进入相机时重开分步引导 */
    fun resetGuidanceSteps() {
        zoomPreparationFinished = false
        subjectContinuity.reset()
        lastSubjectInput = null
        zoomReferenceSeen = false
        zoomGeneration++
        lastSceneKey = null
        pendingSceneKey = null
        pendingSceneFrames = 0
        stepSequencer.reset(android.os.SystemClock.elapsedRealtime())
        _guidanceStep.value = GuidanceStepSequencer.GuidanceStep.ZOOM
    }

    // ── 人像 / 自拍模式：强制要求主体 ───────────────────────────
    // 顶部「人像模式」此前只是改了图标颜色，既不影响模板也不影响引导，
    // 所以选人像后仍然没有任何"把人放进画面"的提示。这里把模式选择
    // 真正接进引导输入：人像（含自拍）一律要求画面里有真实主体。
    private val _portraitGuide = MutableStateFlow(false)

    fun setPortraitGuide(enabled: Boolean) {
        if (_portraitGuide.value == enabled) return
        _portraitGuide.value = enabled
        resetGuidanceSteps()
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
                zoomGeneration++
                stepSequencer.reset(android.os.SystemClock.elapsedRealtime())
            }
            sceneKey == last -> {
                pendingSceneKey = null
                pendingSceneFrames = 0
            }
            sceneKey == pendingSceneKey -> {
                pendingSceneFrames++
                if (pendingSceneFrames >= SCENE_RESET_FRAMES) {
                    val midGuidance = sessionController.state.value is
                        CompositionSessionController.State.Adjusting ||
                        sessionController.state.value is CompositionSessionController.State.Verifying ||
                        sessionController.state.value is CompositionSessionController.State.Ready
                    lastSceneKey = sceneKey
                    pendingSceneKey = null
                    pendingSceneFrames = 0
                    if (midGuidance) {
                        // 用户正在按当前指令调整时，场景分类抖动不能把步骤打回 ZOOM；
                        // 下一次按快门会统一 resetGuidanceSteps()。
                        Log.i(CameraConfig.TAG, "[RenderVM] 场景变化发生在引导中，保持当前步骤")
                    } else {
                        zoomGeneration++
                        zoomPreparationFinished = false
                        stepSequencer.reset(android.os.SystemClock.elapsedRealtime())
                        Log.i(CameraConfig.TAG, "[RenderVM] 场景稳定切换，重开分步引导")
                    }
                }
            }
            else -> {
                pendingSceneKey = sceneKey
                pendingSceneFrames = 1
            }
        }
    }

    /** 从管线输出适配序列器输入（水平角/主体占比来自几何阶段注入的辅助线） */
    private fun usesFaceAnchor(output: FakeInferenceOutput): Boolean = _portraitGuide.value ||
        output.sceneAnalysis == null ||
        output.sceneAnalysis.family == com.example.aicamera.guidance.SceneFamily.PORTRAIT ||
        (output.sceneAnalysis.family == com.example.aicamera.guidance.SceneFamily.UNKNOWN &&
            output.structureObservation?.singleFace(android.os.SystemClock.elapsedRealtime()) != null)

    private fun sequencerInput(output: FakeInferenceOutput): GuidanceStepSequencer.Input {
        val faceScene = usesFaceAnchor(output)
        var angle: Float? = null
        var isUpright: Boolean? = null
        var subjectFrac: Float? = null
        var hasSubject = false
        for (line in output.guideLines) {
            if (!line.visible) continue
            when (line.type) {
                "horizon_level" -> {
                    angle = (line.params["angle_degrees"] as? Number)?.toFloat()
                    // ★ 提取 is_upright 标记（由 GeometryGuidanceStage 从传感器注入）
                    isUpright = line.params["is_upright"] as? Boolean
                }
                "subject_box" -> {
                    // ★ 只信真实检测框（real=true）：模板占位框会让
                    //   自动变焦对着假主体算出「无需变焦」的错误结论
                    val anchorKind = if (faceScene) "face" else "object"
                    val isReal = line.params["real"] == true && line.params["anchor_kind"] == anchorKind
                    val h = (line.params["height"] as? Number)?.toFloat()
                    if (isReal && h != null && h.isFinite() && h > 0f && h <= 1f) {
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
            expectsSubject = output.expectsSubject || output.objectTarget != null || _portraitGuide.value,
            currentZoomRatio = currentZoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            targetSubjectHeightFraction = if (faceScene) output.compositionTarget?.referenceFaceHeight
                else output.objectTarget?.referenceBox?.height,
            recommendationReady = output.aiInvolved && output.confidence.isFinite() && output.confidence > 0f
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
            aiCompositionMethod = output.aiCompositionMethod,
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
        var pitchTarget: Float? = null
        var yawTarget: Float? = null
        var isUpright: Boolean? = null
        var subjectCx: Float? = null
        var subjectCy: Float? = null
        var hasRealSubject = false
        var targetCx: Float? = null
        var targetCy: Float? = null
        var targetTol = 0.12f
        var coordinateAspectRatio = 1f

        for (line in output.guideLines) {
            if (!line.visible) continue
            when (line.type) {
                "horizon_level" -> {
                    roll = (line.params["angle_degrees"] as? Number)?.toFloat()
                    pitch = (line.params["pitch_degrees"] as? Number)?.toFloat()
                    yaw = (line.params["yaw_degrees"] as? Number)?.toFloat()
                    if (line.params["pose_target_verified"] == true) {
                        pitchTarget = (line.params["target_pitch_degrees"] as? Number)?.toFloat()?.takeIf { it.isFinite() }
                        yawTarget = (line.params["target_yaw_degrees"] as? Number)?.toFloat()?.takeIf { it.isFinite() }
                    }
                    isUpright = line.params["is_upright"] as? Boolean
                }
                "subject_box" -> {
                    val isReal = line.params["real"] == true && line.params["anchor_kind"] == "face"
                    if (isReal) {
                        val x = (line.params["x"] as? Number)?.toFloat()
                        val y = (line.params["y"] as? Number)?.toFloat()
                        val w = (line.params["width"] as? Number)?.toFloat()
                        val h = (line.params["height"] as? Number)?.toFloat()
                        if (x != null && y != null && w != null && h != null &&
                            x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite() && w > 0f && h > 0f &&
                            x >= 0f && y >= 0f && x + w <= 1f && y + h <= 1f
                        ) {
                            subjectCx = x + w / 2f
                            subjectCy = y + h / 2f
                            hasRealSubject = true
                        }
                    }
                }
                "target_zone" -> {
                    // The live subject detector observes a face, not the complete body.
                    if (line.params["anchor_kind"] == "face") {
                        targetCx = (line.params["center_x"] as? Number)?.toFloat()
                        targetCy = (line.params["center_y"] as? Number)?.toFloat()
                        targetTol = (line.params["radius"] as? Number)?.toFloat() ?: 0.12f
                        coordinateAspectRatio = (line.params["coordinate_aspect_ratio"] as? Number)?.toFloat() ?: 1f
                    }
                }
            }
        }

        val scene = output.sceneAnalysis
        // Explicit portrait mode outranks the scene label; incidental landscape
        // faces must not replace the subject selected by scene analysis.
        val faceScene = usesFaceAnchor(output)
        val objectScene = !faceScene && scene?.objectObservation != null
        output.structureObservation?.takeIf { faceScene }?.let { structure ->
            val rawFace = structure.singleFace(android.os.SystemClock.elapsedRealtime())
            subjectCx = rawFace?.centerX
            subjectCy = rawFace?.centerY
            hasRealSubject = rawFace != null
        }
        val observedObject = scene?.objectObservation?.takeIf { objectScene && it.isFresh(android.os.SystemClock.elapsedRealtime()) }
        val objectTarget = output.objectTarget?.takeIf { target -> observedObject?.let { target.accepts(it) } == true }
        if (!faceScene) {
            subjectCx = observedObject?.box?.centerX
            subjectCy = observedObject?.box?.centerY
            hasRealSubject = observedObject != null
            targetCx = objectTarget?.center?.x
            targetCy = objectTarget?.center?.y
            targetTol = objectTarget?.tolerance ?: .06f
            coordinateAspectRatio = observedObject?.aspectRatio ?: 1f
        }
        val observation = CompositionSessionController.FrameObservation(
            frameId = nextObservationFrameId(result),
            timestampMs = android.os.SystemClock.elapsedRealtime(),
            rollDegrees = roll,
            rawRollDegrees = output.rawRollDegrees,
            pitchDegrees = pitch,
            yawDegrees = yaw,
            isUpright = isUpright,
            subjectCx = subjectCx,
            subjectCy = subjectCy,
            targetCx = targetCx,
            targetCy = targetCy,
            targetTolerance = targetTol,
            // 人像/自拍模式强制要求主体：即使模板没标注 portrait，
            // 也要给出「把人放进画面 / 移动主体到目标」的引导。
            expectsSubject = output.expectsSubject || objectTarget != null || _portraitGuide.value,
            hasRealSubject = hasRealSubject,
            modelConfidence = if (output.aiInvolved) output.confidence else 0f,
            pitchTargetDegrees = pitchTarget,
            yawTargetDegrees = yawTarget,
            coordinateAspectRatio = coordinateAspectRatio,
            subjectObservationId = if(objectScene) observedObject?.observationId ?: 0L else output.structureObservation?.observationId ?: 0L,
            subjectTrackingId = if(objectScene) observedObject?.trackingId else output.structureObservation?.trackingId.takeIf { faceScene },
            subjectSampledAtMs = if(objectScene) observedObject?.sampledAtMs ?: 0L else output.structureObservation?.sampledAtMs ?: 0L,
            imuObservationId = output.imuObservationId,
            imuSampledAtMs = output.imuSampledAtMs,
            faceCount = if(faceScene) output.structureObservation?.faceCount ?: 0 else 0,
            viewportVersion = scene?.viewportVersion ?: output.structureObservation?.viewportVersion ?: -1,
            // ★ 目标视口必须与实际视口一致，否则 validSubject 永远为 false：
            //   -2 是"没有模板目标"时的旧哨兵值，而兜底目标（三分点）只以
            //   target_zone 辅助线的形式存在，没有 compositionTarget，
            //   于是 targetViewportVersion=-2 != viewportVersion，导致主体
            //   明明已经对齐却一直卡在"正在确认可用的构图依据"。
            targetViewportVersion = objectTarget?.viewportVersion
                ?: output.compositionTarget?.viewportVersion
                ?: (scene?.viewportVersion ?: output.structureObservation?.viewportVersion ?: -1),
            targetId = objectTarget?.id ?: output.compositionTarget?.id,
            qualityScore = output.qualityScore,
            qualityAcceptable = output.qualityAcceptable,
            observedAnchorKind = if(objectScene) com.example.aicamera.guidance.ObservedAnchorKind.OBJECT
                else if(faceScene) com.example.aicamera.guidance.ObservedAnchorKind.FACE else com.example.aicamera.guidance.ObservedAnchorKind.NONE,
            rollObservable = output.rollObservable != false,
            executionQuality = output.executionQuality,
            executionQualityRequired = output.sceneAnalysis != null
        )
        val manual = output.manualTarget ?: return observation
        val visual = output.visualSubjectBox?.takeIf {
            android.os.SystemClock.elapsedRealtime() - output.visualSampledAtMs in 0..750
        }
        return observation.copy(subjectCx = visual?.centerX, subjectCy = visual?.centerY,
            targetCx = manual.x, targetCy = manual.y, targetTolerance = .12f,
            targetId = "manual:${output.manualSelectionId}", hasRealSubject = false, expectsSubject = true,
            observedAnchorKind = com.example.aicamera.guidance.ObservedAnchorKind.OBJECT,
            subjectObservationId = 0L, subjectSampledAtMs = output.visualSampledAtMs,
            targetViewportVersion = observation.viewportVersion, trackingPaused = true, visualPositionAvailable = visual != null,
            visualPositionSampledAtMs = output.visualSampledAtMs)
    }
}
