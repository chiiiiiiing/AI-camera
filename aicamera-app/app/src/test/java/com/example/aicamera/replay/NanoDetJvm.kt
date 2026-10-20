package com.example.aicamera.replay

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.aicamera.pipeline.DetectionViewportTransform
import com.example.aicamera.pipeline.NanoDetRuntime
import com.example.aicamera.pipeline.NativeViewportPixels
import com.example.aicamera.pipeline.ObjectDetection
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * NanoDet 桌面执行器 — 复刻手机端 [NanoDetRuntime.runInput] 的 ORT 调用。
 * Android 版的 detect 入口依赖 Bitmap，无法在 JVM 构造；这里用同一张 ONNX 图
 * 自建 session，预处理与解码全部复用主线的 internal 实现：
 * - [NativeViewportPixels]：半像素双线性 resize + 416 letterbox（与 Python 逐位对齐）
 * - [NanoDetRuntime.decode]：类别无关 NMS
 * 输入像素与手机端 detectNative 路径逐位一致。
 */
class NanoDetJvm private constructor(private val session: OrtSession) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val outputNames = session.outputNames.toList()

    fun detect(pixels: IntArray, width: Int, height: Int): List<ObjectDetection> {
        val transform = DetectionViewportTransform.forViewport(width, height)
        val resized = NativeViewportPixels.resize(pixels, width, height, transform)
        val letterboxed = NativeViewportPixels.letterbox(resized, transform)
        val input = NanoDetRuntime.letterboxPixelsToInput(letterboxed)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 416, 416)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                // 按实际图输出名逐个取，不依赖 Set 迭代或输出顺序（与 runInput 一致）
                val outputs = outputNames.associateWith { name ->
                    @Suppress("UNCHECKED_CAST")
                    (result.get(name).orElseThrow { IllegalArgumentException("Missing NanoDet output: $name") }
                        .value as Array<Array<FloatArray>>)[0]
                }
                return NanoDetRuntime.decode(outputs).mapNotNull { detection ->
                    transform.toViewport(detection.box)?.let { detection.copy(box = it) }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        fun create(modelFile: File): NanoDetJvm? {
            if (!modelFile.exists()) return null
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val session = try {
                OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, opts)
            } finally {
                opts.close()
            }
            return NanoDetJvm(session)
        }
    }
}
