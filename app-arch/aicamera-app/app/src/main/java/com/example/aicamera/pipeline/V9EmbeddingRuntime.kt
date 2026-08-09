package com.example.aicamera.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.aicamera.camera.CameraConfig
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * V9 ONNX 模型推理运行时 — 封装 ONNX Runtime Mobile。
 *
 * ## 职责
 * - 加载 `student_model.static_int8.onnx`
 * - 执行 Bitmap → NCHW 224×224 float32 预处理
 * - 推理得到 512 维 L2 normalized embedding
 *
 * ## 模型规格
 * | 项目 | 值 |
 * |---|---|
 * | 输入 tensor | `image`, [1, 3, 224, 224], float32, NCHW |
 * | 输出 tensor | `embedding`, [1, 512], float32 |
 * | 归一化 | value = (rgb/255.0 - 0.5) / 0.5 |
 * | 后处理 | 模型内已 L2 normalize，App 再做一次防御性归一化 |
 */
class V9EmbeddingRuntime(
    private val modelPath: String
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    /** 输入 tensor name（从 manifest 读取，默认 "image"） */
    val inputName: String

    /** 输出 tensor name（从 manifest 读取，默认 "embedding"） */
    val outputName: String

    /** 模型是否加载成功 */
    var isLoaded: Boolean = false
        private set

    init {
        try {
            val opts = OrtSession.SessionOptions().apply {
                // int8 模型用 CPU 执行；XNNPACK 已内置于 ONNX Runtime Mobile
                setIntraOpNumThreads(2)
                // 允许图优化
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            // ★ SessionOptions 实现 AutoCloseable，建完 session 必须关闭防泄漏
            session = try {
                env.createSession(modelPath, opts)
            } finally {
                opts.close()
            }

            // 从模型读取 tensor 名称
            inputName = session.inputNames.iterator().next()
            outputName = session.outputNames.iterator().next()

            Log.i(TAG, "V9 ONNX 模型加载成功: $modelPath")
            Log.i(TAG, "  输入: $inputName ${session.inputInfo[inputName]?.info}")
            Log.i(TAG, "  输出: $outputName ${session.outputInfo[outputName]?.info}")

            // 验证输入输出 shape
            val inputInfo = session.inputInfo[inputName]
            val outputInfo = session.outputInfo[outputName]
            if (inputInfo == null || outputInfo == null) {
                throw IllegalStateException("无法读取模型输入/输出信息")
            }
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "V9 ONNX 模型加载失败", e)
            throw e
        }
    }

    // ── 公开 API ──────────────────────────────────────────────

    /**
     * 从 Bitmap 预处理并推理，返回 512 维 embedding。
     *
     * @param bitmap 相机帧（将 resize 到 224×224）
     * @return 512 维 FloatArray，L2 normalized
     */
    fun embed(bitmap: Bitmap): FloatArray {
        val nchw = bitmapToNchw224(bitmap)
        return embed(nchw)
    }

    /**
     * 从已预处理的 NCHW FloatArray 推理。
     */
    fun embed(input: FloatArray): FloatArray {
        if (input.size != INPUT_FLOAT_COUNT || input.any { !it.isFinite() }) {
            Log.w(TAG, "忽略非法模型输入: size=${input.size}")
            return FloatArray(EMBEDDING_DIM)
        }
        val shape = longArrayOf(1, 3, 224, 224)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = (result[0].value as Array<FloatArray>)[0]
                if (raw.size != EMBEDDING_DIM) {
                    Log.w(TAG, "忽略维度错误的模型输出: ${raw.size}")
                    return FloatArray(EMBEDDING_DIM)
                }
                return l2Normalize(raw)
            }
        }
    }

    // ── 预处理 ────────────────────────────────────────────────

    companion object {
        private const val TAG = "V9Runtime"
        const val INPUT_SIZE = 224
        const val EMBEDDING_DIM = 512
        private const val INPUT_FLOAT_COUNT = 3 * INPUT_SIZE * INPUT_SIZE
        private const val MIN_NORM_SQUARED = 1e-16

        /**
         * Bitmap → NCHW 224×224 float32。
         *
         * 处理顺序：resize → 逐像素提取 RGB → NCHW 排布 → normalize。
         * 归一化公式：value = (rgb / 255.0 - 0.5) / 0.5
         */
        fun bitmapToNchw224(bitmap: Bitmap): FloatArray {
            val resized = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            } else bitmap

            val n = INPUT_SIZE * INPUT_SIZE
            val out = FloatArray(3 * n)
            val rBase = 0
            val gBase = n
            val bBase = 2 * n

            // 复用像素数组避免分配
            val pixels = IntArray(n)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            for (i in pixels.indices) {
                val pixel = pixels[i]
                // normalize: (value/255 - 0.5) / 0.5 = (value/255 - 0.5) * 2
                // = value/127.5 - 1.0
                out[rBase + i] = (Color.red(pixel) / 127.5f - 1.0f)
                out[gBase + i] = (Color.green(pixel) / 127.5f - 1.0f)
                out[bBase + i] = (Color.blue(pixel) / 127.5f - 1.0f)
            }

            if (resized !== bitmap) resized.recycle()
            return out
        }

        /**
         * L2 归一化（防御性，模型内已做过一次）。
         */
        fun l2Normalize(vec: FloatArray): FloatArray {
            var sumSq = 0.0
            for (v in vec) {
                if (!v.isFinite()) return vec.zeroFill()
                sumSq += (v * v).toDouble()
            }
            if (!sumSq.isFinite() || sumSq <= MIN_NORM_SQUARED) return vec.zeroFill()
            val norm = kotlin.math.sqrt(sumSq)
            if (!norm.isFinite() || norm <= 1e-8) return vec.zeroFill()
            for (i in vec.indices) {
                val normalized = (vec[i] / norm).toFloat()
                if (!normalized.isFinite()) return vec.zeroFill()
                vec[i] = normalized
            }
            return vec
        }

        /**
         * 计算两个向量的余弦相似度。
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return Float.NEGATIVE_INFINITY
            var dot = 0.0
            for (i in a.indices) {
                if (!a[i].isFinite() || !b[i].isFinite()) return Float.NEGATIVE_INFINITY
                dot += (a[i] * b[i]).toDouble()
            }
            return dot.toFloat().takeIf { it.isFinite() } ?: Float.NEGATIVE_INFINITY
        }

        /** Search should reject zero, malformed, or non-finite model embeddings safely. */
        fun isUsableEmbedding(vector: FloatArray): Boolean {
            if (vector.size != EMBEDDING_DIM) return false
            var sumSq = 0.0
            for (value in vector) {
                if (!value.isFinite()) return false
                sumSq += (value * value).toDouble()
            }
            return sumSq.isFinite() && sumSq > MIN_NORM_SQUARED
        }

        private fun FloatArray.zeroFill(): FloatArray {
            fill(0f)
            return this
        }
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
        Log.i(TAG, "V9 ONNX 模型已释放")
    }
}
