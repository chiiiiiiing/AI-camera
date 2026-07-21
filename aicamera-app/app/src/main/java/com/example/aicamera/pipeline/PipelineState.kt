package com.example.aicamera.pipeline

/**
 * 帧处理管线的状态与性能统计。
 */
data class PipelineState(

    // ── 帧统计 ──
    val totalFrames: Long = 0,
    val droppedFrames: Long = 0,
    val processedFrames: Long = 0,

    // ── 耗时统计 ──
    val avgPreprocessMs: Float = 0f,
    val avgInferenceMs: Float = 0f,
    val avgPipelineMs: Float = 0f,
    val lastPipelineMs: Long = 0,

    // ── 管线状态 ──
    val isProcessing: Boolean = false,
    val isRunning: Boolean = false,

    /** 当前使用的推理器类型（来自 IInference.typeName） */
    val inferenceType: String = "none",

    // ── 实时性能指标 ──
    /** 丢帧率 (0.0 ~ 1.0) */
    val dropRate: Float = 0f,

    /** 有效帧率（每秒推理输出帧数，后续由外部计算） */
    val effectiveFps: Float = 0f
)

/**
 * 单帧处理耗时拆解（用于性能调优）。
 */
data class FrameTiming(
    val frameIndex: Long,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val parseMs: Long,
    val totalMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 管线处理结果——下游模块（辅助线渲染）的输入。
 */
data class PipelineResult(
    val frameIndex: Long,
    val rotationDegrees: Int,
    val isFrontCamera: Boolean,

    /**
     * 模型输出。MVP 阶段为 [FakeInferenceOutput]。
     * 等队友定义好 schema 后替换为具体类型。
     */
    val modelOutput: Any?,

    val timing: FrameTiming
)
