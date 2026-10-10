package com.example.aicamera.pipeline

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.aicamera.camera.CameraConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 帧处理管线编排器 — 相机链路和 AI 推理之间的桥梁。
 *
 * ## 管线数据流
 * ```
 * CameraViewModel.frameFlow (ImageProxy)
 *     │
 *     ▼
 * FramePipeline.process(ImageProxy, rotation, isFrontCamera)
 *     │
 *     ├─ FrameScheduler.schedule()        ← 跳帧决策 + 线程调度
 *     │      │
 *     │      ▼
 *     ├─ FramePreprocessor.process()      ← YUV → Float Tensor
 *     │      │
 *     │      ▼
 *     ├─ IInference.predict()             ← 推理（真实模型 或 假推理）
 *     │      │
 *     │      ▼
 *     └─ onResult (主线程)                ← PipelineResult → 渲染
 * ```
 */
class FramePipeline(
    private val scheduler: FrameScheduler = FrameScheduler(),
    private val preprocessor: FramePreprocessor = FramePreprocessor(),
    inference: IInference = FakeInferenceAdapter()
) {
    /** ★ 可变推理器，支持热替换 */
    @Volatile
    private var inference: IInference = inference

    /**
     * ★ 几何引导阶段（可选）：推理后对输出注入真实几何信号
     * （IMU 水平角、人脸主体框 → GuidanceCue/进度）。
     * 由 PipelineViewModel 装配。
     */
    @Volatile
    private var geometryStage: com.example.aicamera.guidance.geometry.GeometryGuidanceStage? = null

    /** 管线状态变化回调（调试 / 性能面板用） */
    private var onStateChanged: ((PipelineState) -> Unit)? = null

    /** 用于在镜头切换时清空跨帧推荐状态；调度器保证 process 串行执行。 */
    private var lastIsFrontCamera: Boolean? = null
    private var lastRotation: Int? = null
    private var lastInference: IInference? = null
    private val resetGeneration = AtomicLong(0)
    private var appliedResetGeneration = -1L
    private val modelSampler = ModelOutputSampler()

    // ── 公开 API ──────────────────────────────────────────────

    /**
     * 处理一帧。
     *
     * @param imageProxy CameraX 传出的帧
     * @param rotation 旋转角度
     * @param isFrontCamera 是否前置摄像头
     * @param onResult 处理完成后的回调（主线程）
     */
    fun process(
        imageProxy: ImageProxy,
        rotation: Int,
        isFrontCamera: Boolean,
        onResult: (PipelineResult) -> Unit
    ) {
        scheduler.schedule(
            imageProxy = imageProxy,
            onProcess = { proxy ->
                // Capture one engine for this entire frame; hot swaps happen on the next frame.
                val frameInference = inference
                val generation = resetGeneration.get()
                if (appliedResetGeneration != generation || lastInference !== frameInference ||
                    lastIsFrontCamera != isFrontCamera || lastRotation != rotation
                ) {
                    modelSampler.reset()
                    frameInference.resetSession()
                    geometryStage?.reset()
                    appliedResetGeneration = generation
                    lastInference = frameInference
                    lastIsFrontCamera = isFrontCamera
                    lastRotation = rotation
                }

                var preprocessMs = 0L
                var inferenceMs = 0L
                var modelSampled = false
                var modelOutput = modelSampler.sample(
                    SystemClock.elapsedRealtime(), frameInference.minInferenceIntervalMs
                ) {
                    modelSampled = true
                    val preprocessStart = SystemClock.elapsedRealtime()
                    val preprocessed = preprocessor.process(proxy, rotation, isFrontCamera)
                    preprocessMs = SystemClock.elapsedRealtime() - preprocessStart
                    try {
                        val inferenceStart = SystemClock.elapsedRealtime()
                        frameInference.predict(preprocessed.buffer, preprocessed).also {
                            inferenceMs = SystemClock.elapsedRealtime() - inferenceStart
                        }
                    } finally {
                        preprocessed.originalBitmap?.recycle()
                    }
                }

                // Fresh geometry on every processed frame; never cache mutated overlays.
                val geometryStart = SystemClock.elapsedRealtime()
                geometryStage?.let { stage ->
                    modelOutput = stage.apply(modelOutput, proxy, isFrontCamera)
                }
                val geometryMs = SystemClock.elapsedRealtime() - geometryStart
                PipelineResult(
                    frameIndex = 0,
                    rotationDegrees = rotation,
                    isFrontCamera = isFrontCamera,
                    modelOutput = modelOutput,
                    timing = FrameTiming(
                        frameIndex = 0,
                        preprocessMs = preprocessMs,
                        inferenceMs = inferenceMs,
                        parseMs = 0,
                        totalMs = 0,
                        modelSampled = modelSampled,
                        geometryMs = geometryMs
                    )
                )
            },
            onComplete = { result ->
                // ★ 使用闭包而非共享字段，避免覆盖风险
                onResult(result)
                onStateChanged?.invoke(getState())
            }
        )
    }

    // ── 生命周期 ──────────────────────────────────────────────

    fun start() {
        scheduler.start()
        logInfo("管道已启动")
    }

    fun stop() {
        scheduler.stop()
        resetGeneration.incrementAndGet()
        logInfo("管道已停止")
    }

    fun release() {
        scheduler.release()
        resetGeneration.incrementAndGet()
        onStateChanged = null
        logInfo("管道已释放")
    }

    // ── 查询 ──────────────────────────────────────────────────

    fun getState(): PipelineState = scheduler.getState().copy(
        inferenceType = inference.typeName
    )

    fun setOnStateChanged(block: (PipelineState) -> Unit) {
        onStateChanged = block
    }

    /**
     * ★ 真正可替换的推理器。
     *
     * MVP 阶段用 FakeInference，队友模型到位后替换为真实 LiteRT 推理器。
     */
    fun setInference(newInference: IInference) {
        this.inference = newInference
        resetGeneration.incrementAndGet()
        logInfo("推理器已替换: ${newInference.typeName}")
    }

    /** 装配几何引导阶段（null 表示关闭） */
    fun setGeometryStage(stage: com.example.aicamera.guidance.geometry.GeometryGuidanceStage?) {
        this.geometryStage = stage
        resetGeneration.incrementAndGet()
        logInfo("几何引导阶段已${if (stage != null) "装配" else "移除"}")
    }
}

// ── 推理接口 ──────────────────────────────────────────────────

/**
 * 推理器抽象接口。
 */
interface IInference {
    /** 推理器标识名（调试/状态面板用） */
    val typeName: String

    /** Minimum model sampling interval; geometry remains independent. Zero processes every frame. */
    val minInferenceIntervalMs: Long get() = 0L

    /**
     * 执行推理。
     *
     * @param buffer Float32 ByteBuffer（[1, H, W, 3]）
     * @param frame 预处理的帧（含尺寸等元信息）
     * @return 推理结果
     */
    fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any?

    /** 切换镜头、暂停或开始新会话时清空跨帧状态。无状态推理器无需覆写。 */
    fun resetSession() = Unit
}

/**
 * ★ 假推理适配器 — 直接从 FloatBuffer 计算亮度，无需 Bitmap 反向转换。
 */
class FakeInferenceAdapter : IInference {

    override val typeName: String = "FakeInference"

    private val fake = FakeInference()

    override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
        // ★ 直接从 FloatBuffer 计算亮度（去除 buffer→Bitmap→亮度 的冗余路径）
        buffer.rewind()
        var sum = 0f
        var count = 0

        while (buffer.remaining() >= 12) {
            val r = buffer.float
            val g = buffer.float
            val b = buffer.float
            sum += 0.299f * r + 0.587f * g + 0.114f * b
            count++
        }

        buffer.rewind()
        val luminance255 = if (count > 0) sum / count * 255f else 128f

        return fake.predictFromLuminance(luminance255, frame.rotation)
    }
}

// ── 日志 ──────────────────────────────────────────────────────

private fun logInfo(msg: String) = Log.i(CameraConfig.TAG, "[Pipeline] $msg")
