package com.example.aicamera.pipeline

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.aicamera.camera.CameraConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 帧调度器 — 跳帧决策 + 单线程推理调度。
 *
 * ## 调度策略
 * - 单线程保序执行（每帧串行处理）
 * - 推理中丢弃新帧（KEEP_ONLY_LATEST 已在 CameraX 层生效）
 * - stop 后无条件拒收帧
 */
class FrameScheduler {

    /** ★ 可重建线程池，避免 release 后不可复用 */
    private var inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** ★ 原子门闩：防止多线程并发进入处理 */
    private val processing = AtomicBoolean(false)

    @Volatile
    var isRunning: Boolean = false
        private set

    private val totalFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val processedFrames = AtomicLong(0)

    /** Increments across pause/resume so completed old frames cannot update a new camera session. */
    private val sessionGeneration = AtomicLong(0)

    /** ★ 统计队列锁：推理线程写、主线程读，旧代码无同步存在并发风险 */
    private val statsLock = Any()
    private val recentPipelineTimes = ArrayDeque<Long>(20)
    private val recentPreprocessTimes = ArrayDeque<Long>(20)
    private val recentInferenceTimes = ArrayDeque<Long>(20)
    private val recentCompletionTimes = ArrayDeque<Long>(20)
    private val recentModelCompletionTimes = ArrayDeque<Long>(20)

    fun schedule(
        imageProxy: ImageProxy,
        onProcess: (ImageProxy) -> PipelineResult,
        onComplete: (PipelineResult) -> Unit
    ) {
        totalFrames.incrementAndGet()

        // ★ P0: stop 后拒收帧
        if (!isRunning) {
            droppedFrames.incrementAndGet()
            safeClose(imageProxy)
            return
        }

        // ★ P0: 原子 CAS 防止并发进入
        if (!processing.compareAndSet(false, true)) {
            droppedFrames.incrementAndGet()
            safeClose(imageProxy)
            return
        }

        val frameIdx = processedFrames.incrementAndGet()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val frameGeneration = sessionGeneration.get()

        // ★ 修复竞态：release() 与帧到达并发时 execute() 会抛
        //   RejectedExecutionException——旧代码此处异常会导致 processing 门闩
        //   永久卡死 + ImageProxy 泄漏。
        try {
            inferenceExecutor.execute {
                runPipeline(imageProxy, frameIdx, rotation, frameGeneration, onProcess, onComplete)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            logError("推理线程池已关闭，丢弃帧", e)
            processing.set(false)
            droppedFrames.incrementAndGet()
            processedFrames.decrementAndGet()
            safeClose(imageProxy)
        }
    }

    private fun runPipeline(
        imageProxy: ImageProxy,
        frameIdx: Long,
        rotation: Int,
        frameGeneration: Long,
        onProcess: (ImageProxy) -> PipelineResult,
        onComplete: (PipelineResult) -> Unit
    ) {
        val startTime = SystemClock.elapsedRealtime()

        try {
            if (!isCurrentSession(frameGeneration)) return
            val result = onProcess(imageProxy)
            val totalMs = SystemClock.elapsedRealtime() - startTime

            val finalResult = result.copy(
                frameIndex = frameIdx,
                timing = result.timing.copy(frameIndex = frameIdx, totalMs = totalMs)
            )

            if (!isCurrentSession(frameGeneration)) return
            recordTiming(finalResult.timing, SystemClock.elapsedRealtime())

            mainHandler.post {
                if (isCurrentSession(frameGeneration)) onComplete(finalResult)
            }
        } catch (e: Exception) {
            logError("管线处理异常", e)
            val totalMs = SystemClock.elapsedRealtime() - startTime
            mainHandler.post {
                if (!isCurrentSession(frameGeneration)) return@post
                onComplete(
                    PipelineResult(
                        frameIndex = frameIdx,
                        rotationDegrees = rotation,
                        isFrontCamera = false, // ★ 由外部进程时传入，此处仅兜底
                        modelOutput = null,
                        timing = FrameTiming(
                            frameIndex = frameIdx,
                            preprocessMs = 0,
                            inferenceMs = 0,
                            parseMs = 0,
                            totalMs = totalMs
                        )
                    )
                )
            }
        } finally {
            processing.set(false)
            safeClose(imageProxy)
        }
    }

    private fun safeClose(imageProxy: ImageProxy) {
        try {
            imageProxy.close()
        } catch (e: Exception) {
            Log.w(CameraConfig.TAG, "[Scheduler] close imageProxy 失败: ${e.message}")
        }
    }

    fun start() {
        // ★ 确保线程池可用（release 后可复用）
        ensureExecutor()
        sessionGeneration.incrementAndGet()
        isRunning = true
        resetStats()
        logInfo("帧处理管线已启动")
    }

    fun stop() {
        isRunning = false
        sessionGeneration.incrementAndGet()
        logInfo("帧处理管线已停止: processed=${processedFrames.get()}, dropped=${droppedFrames.get()}")
    }

    fun release() {
        stop()
        inferenceExecutor.shutdown()
        logInfo("帧处理管线已释放")
    }

    fun getState(): PipelineState {
        val processed = processedFrames.get()
        val total = totalFrames.get()
        val dropped = droppedFrames.get()

        // ★ 在锁内取快照，避免与推理线程的 recordTiming 并发
        val snapshot = synchronized(statsLock) {
            TimingSnapshot(
                averagePreprocessMs = recentPreprocessTimes.averageOrZero(),
                averageInferenceMs = recentInferenceTimes.averageOrZero(),
                averagePipelineMs = recentPipelineTimes.averageOrZero(),
                lastPipelineMs = recentPipelineTimes.lastOrNull() ?: 0L,
                effectiveFps = effectiveFpsFromCompletionTimes(recentCompletionTimes),
                modelFps = effectiveFpsFromCompletionTimes(recentModelCompletionTimes),
                inferenceP50Ms = latencyPercentile(recentInferenceTimes, 0.5),
                inferenceP90Ms = latencyPercentile(recentInferenceTimes, 0.9)
            )
        }

        return PipelineState(
            totalFrames = total,
            droppedFrames = dropped,
            processedFrames = processed,
            avgPreprocessMs = snapshot.averagePreprocessMs,
            avgInferenceMs = snapshot.averageInferenceMs,
            avgPipelineMs = snapshot.averagePipelineMs,
            lastPipelineMs = snapshot.lastPipelineMs,
            isProcessing = processing.get(),
            isRunning = isRunning,
            inferenceType = "pipeline",
            // ★ 新增统计
            dropRate = if (total > 0) dropped.toFloat() / total else 0f,
            effectiveFps = if (isRunning) snapshot.effectiveFps else 0f,
            modelFps = if (isRunning) snapshot.modelFps else 0f,
            inferenceP50Ms = snapshot.inferenceP50Ms,
            inferenceP90Ms = snapshot.inferenceP90Ms
        )
    }

    fun getExecutor(): ExecutorService = inferenceExecutor

    private fun recordTiming(timing: FrameTiming, completionTimeMs: Long) {
        synchronized(statsLock) {
            // Cached model outputs are cheap geometry frames, not zero-latency inferences.
            if (timing.modelSampled) {
                recentPreprocessTimes.addLast(timing.preprocessMs)
                if (recentPreprocessTimes.size > 20) recentPreprocessTimes.removeFirst()
                recentInferenceTimes.addLast(timing.inferenceMs)
                if (recentInferenceTimes.size > 20) recentInferenceTimes.removeFirst()
                recentModelCompletionTimes.addLast(completionTimeMs)
                if (recentModelCompletionTimes.size > 20) recentModelCompletionTimes.removeFirst()
            }

            recentPipelineTimes.addLast(timing.totalMs)
            if (recentPipelineTimes.size > 20) recentPipelineTimes.removeFirst()

            recentCompletionTimes.addLast(completionTimeMs)
            if (recentCompletionTimes.size > 20) recentCompletionTimes.removeFirst()
        }
    }

    private fun resetStats() {
        totalFrames.set(0)
        droppedFrames.set(0)
        processedFrames.set(0)
        synchronized(statsLock) {
            recentPipelineTimes.clear()
            recentPreprocessTimes.clear()
            recentInferenceTimes.clear()
            recentCompletionTimes.clear()
            recentModelCompletionTimes.clear()
        }
    }

    /** ★ 如果线程池已关闭，重建 */
    private fun ensureExecutor() {
        if (inferenceExecutor.isShutdown || inferenceExecutor.isTerminated) {
            inferenceExecutor = Executors.newSingleThreadExecutor()
        }
    }

    private fun isCurrentSession(frameGeneration: Long): Boolean =
        isRunning && sessionGeneration.get() == frameGeneration

    private data class TimingSnapshot(
        val averagePreprocessMs: Float,
        val averageInferenceMs: Float,
        val averagePipelineMs: Float,
        val lastPipelineMs: Long,
        val effectiveFps: Float,
        val modelFps: Float,
        val inferenceP50Ms: Long,
        val inferenceP90Ms: Long
    )
}

private fun ArrayDeque<Long>.averageOrZero(): Float {
    if (isEmpty()) return 0f
    return map { it.toFloat() }.average().toFloat()
}

/** Pure timestamp-window calculation, usable by JVM tests and the runtime performance HUD. */
internal fun effectiveFpsFromCompletionTimes(completionTimesMs: Collection<Long>): Float {
    if (completionTimesMs.size < 2) return 0f
    val first = completionTimesMs.first()
    val last = completionTimesMs.last()
    val elapsedMs = last - first
    if (elapsedMs <= 0L) return 0f
    return (((completionTimesMs.size - 1) * 1_000.0) / elapsedMs).toFloat()
        .takeIf { it.isFinite() && it >= 0f } ?: 0f
}

/** Nearest-rank percentile over the rolling sample window. */
internal fun latencyPercentile(samplesMs: Collection<Long>, fraction: Double): Long {
    require(fraction > 0.0 && fraction <= 1.0)
    if (samplesMs.isEmpty()) return 0
    val sorted = samplesMs.sorted()
    return sorted[kotlin.math.ceil(sorted.size * fraction).toInt() - 1]
}

private fun logInfo(msg: String) = Log.i(CameraConfig.TAG, "[Scheduler] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Scheduler] $msg", e)
