package com.example.aicamera.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.guidance.GuidanceState
import com.example.aicamera.guidance.GuidanceStateMachine
import com.example.aicamera.overlay.model.GuideLine
import com.example.aicamera.overlay.model.GuideLineType
import com.example.aicamera.overlay.model.RenderInput
import com.example.aicamera.pipeline.FakeInferenceOutput
import com.example.aicamera.pipeline.PipelineResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 渲染 ViewModel — 连接帧处理管线输出 → 辅助线渲染引擎。
 */
class RenderViewModel : ViewModel() {

    private val _renderInput = MutableStateFlow(RenderInput())
    val renderInput: StateFlow<RenderInput> = _renderInput.asStateFlow()

    private val _renderStats = MutableStateFlow(RenderStats())
    val renderStats: StateFlow<RenderStats> = _renderStats.asStateFlow()

    /** ★ 新引导系统状态（与队友 GuidanceCue 对齐） */
    private val guidanceStateMachine = GuidanceStateMachine()
    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    data class RenderStats(
        val frameCount: Long = 0,
        val currentScene: String = "unknown",
        val confidence: Float = 0f,
        val guideLineCount: Int = 0
    )

    /** ★ 防止重复 connect */
    private var collectJob: Job? = null

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

                // ★ 更新引导状态机
                val fakeOutput = result.modelOutput as? FakeInferenceOutput
                if (fakeOutput != null) {
                    val newGuidance = guidanceStateMachine.reduceFrame(
                        current = _guidanceState.value,
                        cue = fakeOutput.guidanceCue,
                        progress = fakeOutput.compositionProgress,
                        subjectOutline = null,
                        anchors = null,
                    )
                    _guidanceState.value = newGuidance
                }
            }
        }
    }

    private fun parseResult(result: PipelineResult): RenderInput {
        return when (val output = result.modelOutput) {
            is FakeInferenceOutput -> parseFakeOutput(output, result)
            null -> RenderInput(
                isFrontCamera = result.isFrontCamera,
                rotationDegrees = result.rotationDegrees
            )
            else -> {
                Log.w(CameraConfig.TAG, "[RenderVM] 未知模型输出类型: ${output::class.simpleName}")
                // ★ 保留 isFrontCamera/rotation，但不输出辅助线（等模型适配）
                RenderInput(
                    sceneType = "unknown",
                    isFrontCamera = result.isFrontCamera,
                    rotationDegrees = result.rotationDegrees
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
            rotationDegrees = result.rotationDegrees
        )
    }
}
