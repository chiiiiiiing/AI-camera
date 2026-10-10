package com.example.aicamera.pipeline

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 引擎装配管理器 — 统一负责 V9 推理引擎的生命周期。
 *
 * ## 解决的问题（历史 P0）
 * 1. 旧实现在 Application.onCreate 异步复制资产、在 DI 装配时同步检查文件，
 *    首启竞态导致 Fake 推理器作为单例锁死一整局；
 * 2. 旧实现把 ONNX session 创建 + 3.3MB 索引加载放在主线程，首次进相机页卡顿；
 * 3. 资产复制不校验完整性，截断文件会静默进入推理链路。
 *
 * ## 现在的工作方式
 * ```
 * AiCameraApp.onCreate → aiEngine.start()（幂等）
 *   → IO 线程: 复制资产 → sha256 校验 → 加载 ONNX → 加载模板索引
 *   → StateFlow<State> 发射 READY
 * PipelineViewModel 订阅 state，READY 时 pipeline.setInference(v9) 热替换。
 * 任何一步失败 → FAILED，管线保持 FakeInference 兜底，App 可用。
 * ```
 */
@Singleton
class AiEngineManager @Inject constructor(
    @ApplicationContext private val context: Context
) : Closeable {

    enum class Status {
        IDLE,       // 未启动
        COPYING,    // 正在复制资产
        LOADING,    // 正在加载模型与索引
        READY,      // V9 引擎可用
        FAILED      // 失败（保持 FakeInference 兜底）
    }

    data class State(
        val status: Status = Status.IDLE,
        val templateCount: Int = 0,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** READY 后可用的 V9 推理器（由 PipelineViewModel 取走装配进管线） */
    @Volatile
    var v9Inference: V9Inference? = null
        private set

    private val engineJob = SupervisorJob()
    private val scope = CoroutineScope(engineJob + Dispatchers.IO)

    @Volatile
    private var closed = false

    /**
     * 启动装配（幂等）。FAILED 后允许再次调用重试。
     */
    @Synchronized
    fun start() {
        if (closed) {
            Log.w(TAG, "AI 引擎已释放，忽略 start()")
            return
        }
        val current = _state.value.status
        if (current == Status.COPYING || current == Status.LOADING || current == Status.READY) {
            return
        }

        // Publish COPYING before dispatching so concurrent start() calls cannot enqueue a second
        // model/session load while the first coroutine has not yet started.
        _state.value = State(Status.COPYING)

        scope.launch {
            var runtime: V9EmbeddingRuntime? = null
            try {
                // Include disk I/O and hash checks in the failure boundary: a permission or I/O
                // error must leave COPYING and allow a later start() retry.
                val modelDir = AiModelAssets.ensureModelFiles(context)
                    ?: throw IllegalStateException("AI 资产复制或完整性校验失败")
                coroutineContext.ensureActive()
                synchronized(this@AiEngineManager) {
                    if (closed) return@launch
                    _state.value = State(Status.LOADING)
                }
                val modelPath = AiModelAssets.getModelPath(context)
                    ?: throw IllegalStateException("模型路径缺失: $modelDir")
                val indexDir = AiModelAssets.getIndexDir(context)
                    ?: throw IllegalStateException("模板索引路径缺失")
                runtime = V9EmbeddingRuntime(modelPath)
                coroutineContext.ensureActive()
                val index = V9TemplateIndex(indexDir)
                if (!index.load()) {
                    AiModelAssets.invalidateModelFiles(context)
                    throw IllegalStateException("模板索引加载失败")
                }
                coroutineContext.ensureActive()
                // Publication and close share the same lock, so a completed background load
                // can never resurrect an engine after close() has returned.
                synchronized(this@AiEngineManager) {
                    if (closed) return@launch
                    v9Inference = V9Inference(requireNotNull(runtime), index)
                    runtime = null // ownership transferred to v9Inference
                    _state.value = State(Status.READY, templateCount = index.templateCount)
                }
                Log.i(TAG, "V9 推理引擎就绪: ${index.templateCount} 条模板")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("模型加载异常: ${e.message}")
            } finally {
                try { runtime?.close() } catch (_: Exception) {}
            }
        }
    }

    @Synchronized
    private fun fail(message: String) {
        if (closed) return
        Log.w(TAG, "$message — 保持 FakeInference 兜底")
        _state.value = State(Status.FAILED, error = message)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        engineJob.cancel()
        try { v9Inference?.close() } catch (_: Exception) {}
        v9Inference = null
        _state.value = State(Status.IDLE)
    }

    companion object {
        private const val TAG = "AiEngineManager"
    }
}
