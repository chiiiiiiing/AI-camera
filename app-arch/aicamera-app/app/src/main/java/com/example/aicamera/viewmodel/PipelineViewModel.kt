package com.example.aicamera.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.guidance.geometry.GeometryGuidanceStage
import com.example.aicamera.guidance.geometry.ImuHorizonSensor
import com.example.aicamera.pipeline.AiEngineManager
import com.example.aicamera.pipeline.FramePipeline
import com.example.aicamera.pipeline.PipelineResult
import com.example.aicamera.pipeline.PipelineState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 帧处理管线的 ViewModel。
 *
 * ★ 全部依赖经 Hilt 注入（修复历史上绕过 DI 自己 new、导致 V9 装配失效的 P0）。
 * 管线默认以 FakeInference 启动；订阅 [AiEngineManager.state]，
 * V9 引擎 READY 后热替换进管线，无需重启。
 *
 * ★ 几何引导（2026-07-26）：装配 [GeometryGuidanceStage]（IMU 水平角 +
 * ML Kit 人脸主体框 → 真实 GuidanceCue），并随 resume/pause 管理
 * 水平传感器的注册/注销（后台不耗电）。
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    private val pipeline: FramePipeline,
    aiEngine: AiEngineManager,
    private val horizonSensor: ImuHorizonSensor,
    geometryStage: GeometryGuidanceStage
) : ViewModel() {

    /** AI 引擎装配状态（HUD/调试面板展示用） */
    val aiState: StateFlow<AiEngineManager.State> = aiEngine.state

    private val _pipelineState = MutableStateFlow(PipelineState())
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _pipelineResults = MutableSharedFlow<PipelineResult>(
        replay = 0, extraBufferCapacity = 2
    )
    val pipelineResults: SharedFlow<PipelineResult> = _pipelineResults.asSharedFlow()

    /** ★ 防止重复 connect 导致多个 collector */
    private var collectJob: Job? = null

    init {
        // 装配几何引导阶段（IMU 水平角 + ML Kit 人脸 → 真实 GuidanceCue）
        pipeline.setGeometryStage(geometryStage)

        // AI 引擎 READY 后热替换推理器（Fake → V9-ONNX）
        viewModelScope.launch {
            aiEngine.state.collect { state ->
                if (state.status == AiEngineManager.Status.READY) {
                    val v9 = aiEngine.v9Inference
                    if (v9 != null && pipeline.getState().inferenceType != v9.typeName) {
                        pipeline.setInference(v9)
                        _pipelineState.update { pipeline.getState() }
                        Log.i(CameraConfig.TAG, "[PipelineVM] V9 推理引擎已接入管线 (${state.templateCount} 条模板)")
                    }
                }
            }
        }
    }

    fun connect(frameFlow: Flow<CameraViewModel.FrameData>) {
        // ★ 防重入
        if (collectJob?.isActive == true) return

        pipeline.start()

        collectJob = viewModelScope.launch {
            frameFlow.collect { frameData ->
                // Always transfer ownership; the stopped scheduler closes rejected frames too.
                pipeline.process(
                    imageProxy = frameData.imageProxy,
                    rotation = frameData.rotation,
                    isFrontCamera = frameData.isFrontCamera
                ) { result ->
                    // ★ 不需要再包 launch；直接 tryEmit
                    _pipelineResults.tryEmit(result)

                    _pipelineState.update {
                        pipeline.getState()
                    }
                }
            }
        }
    }

    fun pause() {
        horizonSensor.stop()
        pipeline.stop()
    }

    fun resume() {
        horizonSensor.start()
        pipeline.start()
    }

    override fun onCleared() {
        super.onCleared()
        horizonSensor.stop()
        pipeline.release()
    }
}
