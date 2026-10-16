package com.example.aicamera.pipeline

import android.graphics.Bitmap
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Optional frozen-encoder preference head. Raw scores have no probability interpretation. */
class QualityHead private constructor(private val weights: FloatArray, val scenes: Set<String>, val cropDiagnosticsEnabled: Boolean) {
    fun score(embedding: FloatArray, features: FloatArray): Float? {
        if (embedding.size != 512 || features.size != 8 || (embedding.asSequence()+features.asSequence()).any { !it.isFinite() }) return null
        val input = embedding + features
        var score = weights.last()
        for (h in 0 until 64) {
            var hidden = weights[64*520+h]
            for (i in input.indices) hidden += weights[h*520+i]*input[i]
            score += maxOf(0f, hidden)*weights[64*520+64+h]
        }
        return score.takeIf { it.isFinite() }
    }

    companion object {
        val FEATURE_NAMES = listOf("mean_luma", "luma_std", "dark_fraction", "light_fraction",
            "face_area", "face_cx", "face_cy", "face_valid")
        fun load(directory: File): QualityHead? = try {
            val policyFile = File(directory, "quality_policy.json")
            val file = File(directory, "quality_head.json")
            if (!policyFile.isFile || policyFile.length() > 65_536 || !file.isFile || file.length() > 262_144) null else {
                val policy = JSONObject(policyFile.readText())
                require(policy.getInt("schema_version") == 1 && policy.getBoolean("enabled"))
                val metrics = policy.getJSONObject("metrics")
                require(metrics.getInt("acceptance_cluster_count") >= 30)
                val agreement = metrics.getDouble("pairwise_agreement")
                val lower = metrics.getDouble("bootstrap_lower95")
                val baseline = metrics.getDouble("baseline_pairwise_agreement")
                require(agreement.isFinite() && agreement in .7..1.0 && lower.isFinite() && lower > .5 && lower <= 1 &&
                    baseline.isFinite() && baseline in 0.0..1.0 && agreement > baseline)
                require(metrics.getBoolean("hard_constraints_passed") && metrics.getBoolean("severe_defect_non_regression"))
                val enabledScenes = policy.getJSONArray("enabled_scenes")
                val sceneCounts = metrics.getJSONObject("scene_cluster_counts")
                require(enabledScenes.length() > 0 && (0 until enabledScenes.length()).all {
                    sceneCounts.optInt(enabledScenes.getString(it)) >= 10 })
                val coverage = metrics.getDouble("coverage"); val rejection = metrics.getDouble("rejection_rate")
                require(coverage.isFinite() && coverage in 0.0..1.0 && rejection.isFinite() && rejection in 0.0..1.0)
                val bytes = file.readBytes()
                fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                require(policy.getString("head_sha256") == sha(bytes))
                val encoderSha = sha(File(directory, "student_model.onnx").readBytes())
                require(policy.getString("encoder_sha256") == encoderSha)
                val head = JSONObject(bytes.toString(Charsets.UTF_8))
                require(head.getInt("schema_version") == 1 && head.getInt("embedding_dim") == 512 && head.getInt("hidden_dim") == 64)
                require(head.getString("encoder_sha256") == encoderSha && head.getBoolean("enabled"))
                require(head.getString("activation") == "relu" && head.getString("feature_normalization") == "none")
                val headScenes = head.getJSONArray("enabled_scenes")
                require((0 until headScenes.length()).map { headScenes.getString(it) }.toSet() ==
                    (0 until enabledScenes.length()).map { enabledScenes.getString(it) }.toSet())
                val names = head.getJSONArray("feature_names")
                require(names.length() == 8 && FEATURE_NAMES.indices.all { names.getString(it) == FEATURE_NAMES[it] })
                require(head.getString("weight_encoding") == "float32-le-base64")
                val raw = decodeBase64(head.getString("weights_f32_base64"))
                require(raw.size == (64*520+64+64+1)*4)
                val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val weights = FloatArray(buffer.remaining()).also { buffer.get(it) }
                require(weights.all { it.isFinite() })
                val scenes = policy.getJSONArray("enabled_scenes")
                require(scenes.length() > 0)
                // Crop scores are diagnostic until a crop/zoom consumer is separately validated.
                require(!policy.optBoolean("crop_advice_enabled", false))
                val cropEnabled = policy.optBoolean("crop_diagnostics_enabled", false) &&
                    policy.optString("crop_preprocessing") == "resize224_then_crop_resize224" &&
                    head.optString("crop_preprocessing") == "resize224_then_crop_resize224" &&
                    metrics.optInt("crop_teacher_pair_count") > 0 && metrics.optInt("crop_source_cluster_count") >= 10 &&
                    metrics.optDouble("crop_pairwise_agreement",0.0) >= .70
                QualityHead(weights, (0 until scenes.length()).map { scenes.getString(it) }.toSet(),cropEnabled)
            }
        } catch (_: Exception) { null }

        /** Portable decoder: Android API 24 and local JVM tests share identical parsing. */
        private fun decodeBase64(value: String): ByteArray {
            require(value.isNotEmpty() && value.length % 4 == 0 && value.length <= 200_000)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val padding = value.takeLastWhile { it == '=' }.length
            require(padding <= 2 && value.dropLast(padding).all { alphabet.indexOf(it) >= 0 })
            val bytes = ByteArray(value.length/4*3-padding)
            var out = 0
            for (i in value.indices step 4) {
                var word = 0
                for (j in 0..3) word = (word shl 6) or if(value[i+j] == '=') 0 else alphabet.indexOf(value[i+j])
                for (shift in listOf(16,8,0)) if(out < bytes.size) bytes[out++] = (word ushr shift).toByte()
            }
            return bytes
        }

        fun features(bitmap: Bitmap, face: NormalizedBox?): FloatArray {
            val pixels = IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return features(pixels, face)
        }
        fun features(pixels: IntArray, face: NormalizedBox?): FloatArray {
            if (pixels.isEmpty()) return FloatArray(8)
            var sum = 0.0; var square = 0.0; var dark = 0; var light = 0
            for (p in pixels) {
                val y = (.299*((p ushr 16) and 255)+.587*((p ushr 8) and 255)+.114*(p and 255))/255
                sum += y; square += y*y
                if (y <= .05) dark++; if (y >= .95) light++
            }
            val mean = sum/pixels.size
            val f = face?.takeIf { it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 && it.width > 0 && it.height > 0 }
            return floatArrayOf(mean.toFloat(), sqrt(maxOf(0.0, square/pixels.size-mean*mean)).toFloat(),
                dark.toFloat()/pixels.size, light.toFloat()/pixels.size, f?.let { it.width*it.height } ?: 0f,
                f?.centerX ?: 0f, f?.centerY ?: 0f, if (f == null) 0f else 1f)
        }
    }
}

data class QualityCropResult(val crop: NormalizedBox, val score: Float, val baseline: Float) {
    val diagnosticOnly: Boolean get() = true
}

/** At most one independently encoded crop per two seconds, two per stable scene. */
internal class QualityCropEvaluator {
    private var scene: FloatArray? = null
    private var count = 0
    private var lastAt = Long.MIN_VALUE
    private var best: QualityCropResult? = null
    private var sceneFace: NormalizedBox? = null
    private var bestAt = 0L
    fun reset() { scene = null; count = 0; lastAt = Long.MIN_VALUE; best = null; sceneFace = null; bestAt = 0 }
    fun evaluate(bitmap: Bitmap, embedding: FloatArray, face: NormalizedBox?, head: QualityHead,
                 baseline: Float, nowMs: Long, encode: (Bitmap) -> FloatArray): QualityCropResult? {
        if (face == null) { reset(); return null }
        val previous = scene
        val priorFace = sceneFace
        val changedFace = priorFace != null && (kotlin.math.abs(face.centerX-priorFace.centerX) > .025f ||
            kotlin.math.abs(face.centerY-priorFace.centerY) > .025f ||
            kotlin.math.abs(face.width-priorFace.width) > .025f || kotlin.math.abs(face.height-priorFace.height) > .025f)
        if (previous == null || V9EmbeddingRuntime.cosineSimilarity(previous, embedding) < .985f || changedFace) {
            // A moving subject invalidates candidates, but must not defeat the global inference limiter.
            val priorAt = lastAt
            reset(); scene = embedding.copyOf(); sceneFace = face; lastAt = priorAt
        }
        best?.let { cached ->
            val crop = cached.crop
            if (nowMs-bestAt > 2000 || cached.score < baseline+.05f || face.left < crop.left+.02f ||
                face.top < crop.top+.02f || face.right > crop.right-.02f || face.bottom > crop.bottom-.02f)
                best = null
        }
        if (count >= 2 || (lastAt != Long.MIN_VALUE && nowMs-lastAt < 2000)) return best
        lastAt = nowMs
        val size = if (count++ == 0) .90f else .84f
        val x = (face.centerX-size/2).coerceIn(0f,1-size)
        val y = (face.centerY-size*.42f).coerceIn(0f,1-size)
        if (face.left < x+.02f || face.top < y+.02f || face.right > x+size-.02f || face.bottom > y+size-.02f) return best
        val left = (x*bitmap.width).toInt(); val top = (y*bitmap.height).toInt()
        val width = (size*bitmap.width).toInt().coerceAtLeast(1); val height = (size*bitmap.height).toInt().coerceAtLeast(1)
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        val resized = Bitmap.createScaledBitmap(crop,224,224,true)
        try {
            val actualX = left.toFloat()/bitmap.width; val actualY = top.toFloat()/bitmap.height
            val actualW = width.toFloat()/bitmap.width; val actualH = height.toFloat()/bitmap.height
            val mapped = NormalizedBox((face.left-actualX)/actualW,(face.top-actualY)/actualH,
                (face.right-actualX)/actualW,(face.bottom-actualY)/actualH)
            val score = head.score(encode(resized), QualityHead.features(resized,mapped)) ?: return best
            if (score >= baseline+.05f && (best == null || score > best!!.score))
                best = QualityCropResult(NormalizedBox(actualX,actualY,actualX+actualW,actualY+actualH),score,baseline).also { bestAt = nowMs }
            return best
        } finally { if (resized !== crop) resized.recycle(); if (crop !== bitmap) crop.recycle() }
    }
}
