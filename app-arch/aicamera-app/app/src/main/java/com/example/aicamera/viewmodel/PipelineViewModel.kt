package com.example.aicamera.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.pipeline.FramePipeline
import com.example.aicamera.pipeline.FrameScheduler
import com.example.aicamera.pipeline.FramePreprocessor
import com.example.aicamera.pipeline.IInference
import com.example.aicamera.pipeline.PipelineResult
import com.example.aicamera.pipeline.PipelineState
import com.example.aicamera.pipeline.FakeInferenceAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 帧处理管线的 ViewModel。
 */
class PipelineViewModel : ViewModel() {

    private val scheduler = FrameScheduler()
    private val preprocessor = FramePreprocessor(targetSize = 320)
    private var inference: IInference = FakeInferenceAdapter()

    private val pipeline = FramePipeline(
        scheduler = scheduler,
        preprocessor = preprocessor,
        inference = inference
    )

    private val _pipelineState = MutableStateFlow(PipelineState())
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _pipelineResults = MutableSharedFlow<PipelineResult>(
        replay = 0, extraBufferCapacity = 2
    )
    val pipelineResults: SharedFlow<PipelineResult> = _pipelineResults.asSharedFlow()

    /** ★ 防止重复 connect 导致多个 collector */
    private var collectJob: Job? = null

    fun connect(frameFlow: SharedFlow<CameraViewModel.FrameData>) {
        // ★ 防重入
        if (collectJob?.isActive == true) return

        pipeline.start()

        collectJob = viewModelScope.launch {
            frameFlow.collect { frameData ->
                if (!pipeline.getState().isRunning) return@collect

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

    fun setRealInference(realInference: IInference) {
        inference = realInference
        pipeline.setInference(realInference)
        Log.i(CameraConfig.TAG, "[PipelineVM] 已切换为真实推理器")
    }

    fun pause() {
        pipeline.stop()
    }

    fun resume() {
        pipeline.start()
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.release()
    }
}

private typealias FrameData = CameraViewModel.FrameData
