package com.example.aicamera.pipeline

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Detection has its own validated assets; optional reference metadata cannot disable direct rules. */
internal object MultiSceneDetectorAssets {
    fun load(directory: File): File? = try {
        val configFile = File(directory, "android_runtime_config.json")
        require(configFile.isFile && configFile.length() in 1..131_072)
        val config = JSONObject(configFile.readText()).getJSONObject("multi_scene_recommendation")
        require(config.getInt("schema_version") == 1 && config.getBoolean("enabled"))
        val budget = config.optLong("detector_budget_bytes", 4_194_304L)
        require(budget in 1..4_194_304L)
        fun asset(key: String, expectedName: String, maxBytes: Long): File {
            val descriptor = config.getJSONObject(key)
            require(descriptor.getString("file") == expectedName)
            val file = File(directory, expectedName)
            require(file.isFile && file.length() in 1..maxBytes)
            require(sha(file) == descriptor.getString("sha256"))
            return file
        }
        val model = asset("detector", "object_detector.onnx", budget)
        require(model.length() == NanoDetRuntime.MODEL_BYTES && sha(model) == NanoDetRuntime.MODEL_SHA256)
        val manifestFile = asset("detector_manifest", "object_detector_manifest.json", 65_536)
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.getInt("schema_version") == 1 && manifest.getString("model") == NanoDetRuntime.MODEL_FILENAME)
        require(manifest.getString("model_sha256") == NanoDetRuntime.MODEL_SHA256 && manifest.getLong("bytes") == model.length())
        require(manifest.getString("preprocessing") == NanoDetRuntime.NATIVE_PREPROCESSING)
        require(manifest.getString("decoder") == "named_heads_distribution8_anchor_half_stride_v1")
        val input = manifest.getJSONObject("input")
        require(input.getString("name") == "input.1" && input.getString("dtype") == "float32" && input.getString("order") == "BGR")
        val shape = input.getJSONArray("shape")
        require(shape.length() == 4 && (0..3).map { shape.getInt(it) } == listOf(1, 3, 416, 416))
        for ((key, values) in listOf("mean" to listOf(103.53, 116.28, 123.675), "std" to listOf(57.375, 57.12, 58.395))) {
            val actual = input.getJSONArray(key)
            require(actual.length() == values.size && values.indices.all { abs(actual.getDouble(it) - values[it]) <= .001 })
        }
        val heads = manifest.getJSONArray("heads")
        require(heads.length() == 3)
        val expectedHeads = listOf(Triple(8, "792", "795"), Triple(16, "814", "817"), Triple(32, "836", "839"))
        expectedHeads.forEachIndexed { index, (stride, classes, boxes) ->
            val head = heads.getJSONObject(index)
            require(head.getInt("stride") == stride && head.getString("classes") == classes && head.getString("boxes") == boxes &&
                head.getInt("anchors") == (416 / stride) * (416 / stride))
        }
        val labels = manifest.getJSONArray("classes")
        require(labels.length() == 80)
        val usedLabels = mapOf(0 to "person", 39 to "bottle", 40 to "wine glass", 41 to "cup", 45 to "bowl",
            46 to "banana", 47 to "apple", 48 to "sandwich", 49 to "orange", 50 to "broccoli", 51 to "carrot",
            52 to "hot dog", 53 to "pizza", 54 to "donut", 55 to "cake", 58 to "potted plant", 73 to "book",
            74 to "clock", 75 to "vase", 77 to "teddy bear")
        require(usedLabels.all { (id, name) -> labels.getString(id) == name })
        model
    } catch (_: Exception) { null }

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
