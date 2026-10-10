package com.example.aicamera.pipeline

import android.util.Log
import com.example.aicamera.camera.CameraConfig
import java.nio.ByteBuffer

/**
 * 熔断推理器 — 包装真实推理器（V9-ONNX），失败自动回退 Fake。
 *
 * ## 为什么需要它（2026-07-31 真机问题）
 * V9 在真机上 READY 后，若逐帧推理抛异常或持续产出空结果，
 * 帧会被调度器静默丢弃/渲染层拿到空辅助线 —— 用户看到「AI 指引全部消失」，
 * 而状态栏还显示 V9 已就绪，完全无法定位。
 *
 * ## 熔断策略（本地判定，无需外部信号）
 * - 连续 [ERROR_BREAKER_THRESHOLD] 帧抛异常 → 熔断，整链路回退 Fake；
 * - 连续 [EMPTY_BREAKER_THRESHOLD] 帧空输出（无辅助线） → 熔断
 *   （模板索引损坏/embedding 异常的症状，继续跑也没有意义）；
 * - 熔断前**每一帧**都在 try/catch 中兜底：即使 V9 偶发抛异常，
 *   该帧也立即用 Fake 结果顶上，指引永不中断；
 * - 熔断不可逆（进程内）：V9 坏了就是坏了，抖动切换只会更糟；
 *   下次启动重新尝试（AiEngineManager 重新装配）。
 */
class ResilientInference(
    private val primary: IInference,
    private val fallback: IInference = FakeInferenceAdapter(),
    private val onDegraded: (reason: String) -> Unit = {}
) : IInference {

    @Volatile
    private var degraded = false

    private var consecutiveErrors = 0
    private var consecutiveEmpty = 0

    /** 对外展示的类型名：熔断后如实显示兜底引擎 */
    override val typeName: String
        get() = if (degraded) fallback.typeName else primary.typeName

    override val minInferenceIntervalMs: Long
        get() = if (degraded) fallback.minInferenceIntervalMs else primary.minInferenceIntervalMs

    val isDegraded: Boolean get() = degraded

    @Synchronized
    override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
        if (degraded) return predictFallback(buffer, frame)

        val output = try {
            primary.predict(buffer, frame)
        } catch (e: Exception) {
            consecutiveEmpty = 0
            consecutiveErrors++
            Log.w(CameraConfig.TAG, "[Resilient] V9 推理异常 ($consecutiveErrors/$ERROR_BREAKER_THRESHOLD): ${e.message}")
            if (consecutiveErrors >= ERROR_BREAKER_THRESHOLD) {
                degrade("V9 连续 $ERROR_BREAKER_THRESHOLD 帧异常 (${e.javaClass.simpleName})")
            }
            return predictFallback(buffer, frame)
        }
        consecutiveErrors = 0
        if (isEmptyOutput(output)) {
            consecutiveEmpty++
            if (consecutiveEmpty >= EMPTY_BREAKER_THRESHOLD) {
                degrade("V9 连续 $EMPTY_BREAKER_THRESHOLD 帧空输出")
            }
            // A safe V9 failure contains a grid but no recommendation; fall back immediately,
            // including the frame which trips the breaker.
            return predictFallback(buffer, frame)
        }
        consecutiveEmpty = 0
        return output
    }

    @Synchronized
    override fun resetSession() {
        consecutiveErrors = 0
        consecutiveEmpty = 0
        // A lens/session change clears temporal state without resurrecting a broken primary.
        try {
            primary.resetSession()
        } finally {
            if (fallback !== primary) fallback.resetSession()
        }
    }

    private fun predictFallback(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
        val output = fallback.predict(buffer.duplicate().order(buffer.order()).apply { rewind() }, frame)
        return if (output is FakeInferenceOutput) output.copy(
            confidence = 0f,
            compositionProgress = 0f,
            guidanceCue = com.example.aicamera.guidance.GuidanceCue.NONE,
            expectsSubject = false,
            filterRecommendation = null,
            autoZoomRecommendation = null
        ) else output
    }

    private fun degrade(reason: String) {
        if (degraded) return
        degraded = true
        Log.e(CameraConfig.TAG, "[Resilient] ★ V9 已熔断回退 Fake: $reason")
        try {
            onDegraded(reason)
        } catch (e: Exception) {
            Log.w(CameraConfig.TAG, "[Resilient] 熔断状态通知失败", e)
        }
    }

    /** V9 returns a zero-confidence grid when embedding/index evaluation fails safely. */
    private fun isEmptyOutput(output: Any?): Boolean {
        if (output == null) return true
        return output is FakeInferenceOutput &&
            (output.guideLines.isEmpty() || !output.confidence.isFinite() || output.confidence <= 0f)
    }

    companion object {
        const val ERROR_BREAKER_THRESHOLD = 5
        const val EMPTY_BREAKER_THRESHOLD = 30
    }
}
