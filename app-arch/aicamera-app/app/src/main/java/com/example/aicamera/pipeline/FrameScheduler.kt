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

    private val recentPipelineTimes = ArrayDeque<Long>(20)
    private val recentPreprocessTimes = ArrayDeque<Long>(20)
    private val recentInferenceTimes = ArrayDeque<Long>(20)

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

        inferenceExecutor.execute {
            val startTime = SystemClock.elapsedRealtime()

            try {
                val result = onProcess(imageProxy)
                val totalMs = SystemClock.elapsedRealtime() - startTime

                val finalResult = result.copy(
                    frameIndex = frameIdx,
                    timing = result.timing.copy(totalMs = totalMs)
                )

                recordTiming(finalResult.timing)

                mainHandler.post {
                    onComplete(finalResult)
                }
            } catch (e: Exception) {
                logError("管线处理异常", e)
                val totalMs = SystemClock.elapsedRealtime() - startTime
                mainHandler.post {
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
        isRunning = true
        resetStats()
        logInfo("帧处理管线已启动")
    }

    fun stop() {
        isRunning = false
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

        return PipelineState(
            totalFrames = total,
            droppedFrames = dropped,
            processedFrames = processed,
            avgPreprocessMs = recentPreprocessTimes.averageOrZero(),
            avgInferenceMs = recentInferenceTimes.averageOrZero(),
            avgPipelineMs = recentPipelineTimes.averageOrZero(),
            lastPipelineMs = recentPipelineTimes.lastOrNull() ?: 0,
            isProcessing = processing.get(),
            isRunning = isRunning,
            inferenceType = "pipeline",
            // ★ 新增统计
            dropRate = if (total > 0) dropped.toFloat() / total else 0f,
            effectiveFps = 0f // 后续由外部计算
        )
    }

    fun getExecutor(): ExecutorService = inferenceExecutor

    private fun recordTiming(timing: FrameTiming) {
        recentPreprocessTimes.addLast(timing.preprocessMs)
        if (recentPreprocessTimes.size > 20) recentPreprocessTimes.removeFirst()

        recentInferenceTimes.addLast(timing.inferenceMs)
        if (recentInferenceTimes.size > 20) recentInferenceTimes.removeFirst()

        recentPipelineTimes.addLast(timing.totalMs)
        if (recentPipelineTimes.size > 20) recentPipelineTimes.removeFirst()
    }

    private fun resetStats() {
        totalFrames.set(0)
        droppedFrames.set(0)
        processedFrames.set(0)
        recentPipelineTimes.clear()
        recentPreprocessTimes.clear()
        recentInferenceTimes.clear()
    }

    /** ★ 如果线程池已关闭，重建 */
    private fun ensureExecutor() {
        if (inferenceExecutor.isShutdown || inferenceExecutor.isTerminated) {
            inferenceExecutor = Executors.newSingleThreadExecutor()
        }
    }
}

private fun ArrayDeque<Long>.averageOrZero(): Float {
    if (isEmpty()) return 0f
    return map { it.toFloat() }.average().toFloat()
}

private fun logInfo(msg: String) = Log.i(CameraConfig.TAG, "[Scheduler] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Scheduler] $msg", e)
