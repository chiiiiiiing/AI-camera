package com.example.aicamera.pipeline

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import com.example.aicamera.guidance.geometry.NormalizedBox
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Model evidence only: confidence is detection confidence, never beauty or completeness. */
data class ObjectDetection(val classId: Int, val confidence: Float, val box: NormalizedBox)

/**
 * Pinned OpenCV Zoo NanoDet-m-plus 1.5x. Shares ONNX Runtime with retrieval.
 * The input is our existing square 224 px viewport thumbnail, enlarged to 416;
 * this does not claim native 416 px detection detail. All boxes use that viewport.
 * Caller owns scheduling, sample times, camera generation and fallback on failure.
 */
class NanoDetRuntime(modelFile: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val outputNames: List<String>
    private var closed = false

    init {
        require(modelFile.length() == MODEL_BYTES && sha256(modelFile) == MODEL_SHA256) {
            "NanoDet model does not match pinned asset"
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        val created = try { env.createSession(modelFile.absolutePath, options) } finally { options.close() }
        try {
            require(created.inputNames == setOf(INPUT_NAME)) { "Unexpected NanoDet input name" }
            val input = created.inputInfo[INPUT_NAME]?.info as? TensorInfo
            require(input?.type == OnnxJavaType.FLOAT && input.shape.contentEquals(longArrayOf(1, 3, 416, 416))) {
                "Unexpected NanoDet input shape"
            }
            require(created.outputNames == (CLASS_NAMES + BOX_NAMES).toSet()) { "Unexpected NanoDet output names" }
            for (level in STRIDES.indices) {
                val count = (INPUT_SIZE / STRIDES[level]).let { it * it }
                for ((name, width) in listOf(CLASS_NAMES[level] to 80, BOX_NAMES[level] to 32)) {
                    val info = created.outputInfo[name]?.info as? TensorInfo
                    require(info?.type == OnnxJavaType.FLOAT && info.shape.contentEquals(longArrayOf(1, count.toLong(), width.toLong()))) {
                        "Unexpected NanoDet head shape: $name"
                    }
                }
            }
            outputNames = created.outputNames.toList()
            session = created
        } catch (error: Exception) {
            created.close()
            throw error
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<ObjectDetection> {
        check(!closed) { "NanoDet session is closed" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val input = pixelsToInput(pixels, bitmap.width, bitmap.height)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 416, 416)).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                // Lookup by actual graph output names: no Set iteration order or
                // OpenCV/ORT output-order assumption may change class/box pairing.
                val outputs = outputNames.associateWith { name ->
                    @Suppress("UNCHECKED_CAST")
                    (result.get(name).orElseThrow { IllegalArgumentException("Missing NanoDet output: $name") }.value as Array<Array<FloatArray>>)[0]
                }
                return decode(outputs)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            session.close()
        }
    }

    companion object {
        const val MODEL_FILENAME = "object_detection_nanodet_2022nov.onnx"
        const val MODEL_SHA256 = "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
        const val MODEL_BYTES = 3800954L
        const val PREPROCESSING = "viewport_rgb224_bilinear416_bgr_mean_std_v1"
        private const val INPUT_SIZE = 416
        private const val INPUT_NAME = "input.1"
        internal val STRIDES = intArrayOf(8, 16, 32)
        internal val CLASS_NAMES = listOf("792", "814", "836")
        internal val BOX_NAMES = listOf("795", "817", "839")
        private val MEANS = floatArrayOf(103.53f, 116.28f, 123.675f)
        private val STDS = floatArrayOf(57.375f, 57.12f, 58.395f)

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        /** Pure function also used to check channel order and bilinear sampling on JVM. */
        internal fun pixelsToInput(pixels: IntArray, width: Int, height: Int): FloatArray {
            require(width > 0 && height > 0 && pixels.size == width * height)
            val viewport = if (width == 224 && height == 224) pixels else {
                IntArray(224 * 224) { index ->
                    val x = index % 224; val y = index / 224
                    var pixel = -0x1000000
                    for (channel in 0..2) {
                        val value = floor(sample(pixels, width, height, x, y, 224, channel) + .5f).toInt().coerceIn(0, 255)
                        pixel = pixel or (value shl (channel * 8))
                    }
                    pixel
                }
            }
            val plane = INPUT_SIZE * INPUT_SIZE
            val result = FloatArray(3 * plane)
            for (y in 0 until INPUT_SIZE) for (x in 0 until INPUT_SIZE) for (channel in 0..2) {
                val value = sample(viewport, 224, 224, x, y, INPUT_SIZE, channel)
                result[channel * plane + y * INPUT_SIZE + x] = (value - MEANS[channel]) / STDS[channel]
            }
            return result
        }

        // Pixel shifts 0,8,16 are B,G,R; half-pixel bilinear exactly matches Python.
        private fun sample(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, size: Int, channel: Int): Float {
            val sx = max(0f, (x + .5f) * (width.toFloat() / size) - .5f)
            val sy = max(0f, (y + .5f) * (height.toFloat() / size) - .5f)
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
            val wx = sx - x0; val wy = sy - y0
            fun value(px: Int, py: Int) = ((pixels[py * width + px] ushr (channel * 8)) and 255).toFloat()
            val top = value(x0, y0) * (1 - wx) + value(x1, y0) * wx
            val bottom = value(x0, y1) * (1 - wx) + value(x1, y1) * wx
            return top * (1 - wy) + bottom * wy
        }

        internal fun decode(outputs: Map<String, Array<FloatArray>>, scoreThreshold: Float = .35f): List<ObjectDetection> {
            require(scoreThreshold in 0f..1f)
            val candidates = mutableListOf<ObjectDetection>()
            for (level in STRIDES.indices) {
                val stride = STRIDES[level]; val side = INPUT_SIZE / stride
                val scores = requireNotNull(outputs[CLASS_NAMES[level]])
                val logits = requireNotNull(outputs[BOX_NAMES[level]])
                require(scores.size == side * side && logits.size == scores.size)
                val indices = scores.indices.filter { index ->
                    require(scores[index].size == 80 && logits[index].size == 32)
                    scores[index].all { it.isFinite() } && logits[index].all { it.isFinite() } &&
                        scores[index].maxOrNull()!! in scoreThreshold..1f
                }.sortedWith(compareByDescending<Int> { scores[it].maxOrNull() }.thenBy { it }).take(1000)
                for (index in indices) {
                    val distances = FloatArray(4) { sideIndex ->
                        val start = sideIndex * 8
                        val maximum = (0..7).maxOf { logits[index][start + it] }
                        var sum = 0.0; var weighted = 0.0
                        for (bin in 0..7) {
                            val probability = exp((logits[index][start + bin] - maximum).toDouble())
                            sum += probability; weighted += probability * bin
                        }
                        (weighted / sum * stride).toFloat()
                    }
                    val cx = (index % side) * stride + .5f * (stride - 1)
                    val cy = (index / side) * stride + .5f * (stride - 1)
                    val box = NormalizedBox(
                        ((cx-distances[0])/INPUT_SIZE).coerceIn(0f, 1f), ((cy-distances[1])/INPUT_SIZE).coerceIn(0f, 1f),
                        ((cx+distances[2])/INPUT_SIZE).coerceIn(0f, 1f), ((cy+distances[3])/INPUT_SIZE).coerceIn(0f, 1f)
                    )
                    if (box.width <= 0f || box.height <= 0f) continue
                    val classId = scores[index].indices.maxByOrNull { scores[index][it] }!!
                    candidates += ObjectDetection(classId, scores[index][classId], box)
                }
            }
            val selected = mutableListOf<ObjectDetection>()
            for (candidate in candidates.sortedByDescending { it.confidence }) {
                if (selected.all { intersectionOverUnion(candidate.box, it.box) <= .6f }) {
                    selected += candidate
                    if (selected.size == 100) break
                }
            }
            return selected
        }

        internal fun intersectionOverUnion(a: NormalizedBox, b: NormalizedBox): Float {
            val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val union = a.width * a.height + b.width * b.height - intersection
            return if (union > 0f) intersection / union else 0f
        }
    }
}
