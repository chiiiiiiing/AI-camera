package com.example.aicamera.viewmodel

import com.example.aicamera.guidance.SceneAnalysisObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scene label belongs to a camera session, not merely a recent wall-clock completion.
 * Take the generation before queueing inference so a late-starting old frame cannot restore
 * a label after switching cameras, pausing, or rebinding the preview.
 */
internal class SceneRecognitionState {
    private val _observation = MutableStateFlow<SceneAnalysisObservation?>(null)
    val observation: StateFlow<SceneAnalysisObservation?> = _observation.asStateFlow()
    private var generation = 0L
    private var invalidatedAtMs = Long.MIN_VALUE
    private var newestSampledAtMs = Long.MIN_VALUE
    private var expectedFrontCamera: Boolean? = null

    @Synchronized
    fun frameGeneration(): Long = generation

    @Synchronized
    fun invalidate(nowMs: Long, expectedIsFrontCamera: Boolean? = null) {
        generation++
        invalidatedAtMs = nowMs
        newestSampledAtMs = Long.MIN_VALUE
        if (expectedIsFrontCamera != null) expectedFrontCamera = expectedIsFrontCamera
        _observation.value = null
    }

    @Synchronized
    fun accept(
        frameGeneration: Long,
        scene: SceneAnalysisObservation?,
        isFrontCamera: Boolean,
        nowMs: Long,
    ) {
        // Ignore old completions; they must not erase a newer valid label either.
        if (frameGeneration != generation ||
            expectedFrontCamera?.let { it != isFrontCamera } == true) return
        if (scene == null) {
            _observation.value = null
            return
        }
        if (scene.sampledAtMs <= invalidatedAtMs || scene.sampledAtMs < newestSampledAtMs) return
        if (!scene.isFresh(nowMs)) {
            _observation.value = null
            return
        }
        newestSampledAtMs = scene.sampledAtMs
        // Preserve the real sample time: a replay cannot extend the display lease.
        _observation.value = scene
    }
}
