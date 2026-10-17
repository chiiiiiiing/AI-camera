package com.example.aicamera.pipeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class MultiSceneDetectorAssetsTest {
    @Test fun `deployed detector loads without any reference sidecar`() = withBundle { dir, _, _ ->
        assertFalse(File(dir, "multi_scene_references.json").exists())
        assertNotNull(MultiSceneDetectorAssets.load(dir))
        File(dir, "multi_scene_references.json").writeText("broken optional references")
        assertNotNull(MultiSceneDetectorAssets.load(dir))
    }

    @Test fun `disabled unknown schema and escaped filenames fail closed`() {
        for (kind in 0..2) withBundle { dir, config, _ ->
            val module = config.getJSONObject("multi_scene_recommendation")
            when (kind) {
                0 -> module.put("enabled", false)
                1 -> module.put("schema_version", 2)
                else -> module.getJSONObject("detector").put("file", "../object_detector.onnx")
            }
            saveConfig(dir, config)
            assertNull(MultiSceneDetectorAssets.load(dir))
        }
    }

    @Test fun `size budget and modified model fail even when their config hash is updated`() {
        withBundle { dir, config, _ ->
            config.getJSONObject("multi_scene_recommendation").put("detector_budget_bytes", 1)
            saveConfig(dir, config)
            assertNull(MultiSceneDetectorAssets.load(dir))
        }
        withBundle { dir, config, _ ->
            val model = File(dir, "object_detector.onnx")
            model.writeBytes(byteArrayOf(1, 2, 3))
            config.getJSONObject("multi_scene_recommendation").getJSONObject("detector").put("sha256", sha(model))
            saveConfig(dir, config)
            assertNull(MultiSceneDetectorAssets.load(dir))
        }
    }

    @Test fun `manifest hash mismatch and missing manifest disable the detector`() = withBundle { dir, _, _ ->
        val file = File(dir, "object_detector_manifest.json")
        file.appendText(" ")
        assertNull(MultiSceneDetectorAssets.load(dir))
        assertTrue(file.delete())
        assertNull(MultiSceneDetectorAssets.load(dir))
    }

    @Test fun `incompatible input decoder and label contracts fail even with refreshed manifest hashes`() {
        for (kind in 0..4) withBundle { dir, config, manifest ->
            when (kind) {
                0 -> manifest.put("preprocessing", NanoDetRuntime.PREPROCESSING)
                1 -> manifest.put("decoder", "other_decoder")
                2 -> manifest.getJSONObject("input").put("order", "RGB")
                3 -> manifest.getJSONArray("classes").put(47, "person")
                else -> manifest.getJSONArray("heads").getJSONObject(0).put("anchors", 1)
            }
            val file = File(dir, "object_detector_manifest.json")
            file.writeText(manifest.toString())
            config.getJSONObject("multi_scene_recommendation").getJSONObject("detector_manifest").put("sha256", sha(file))
            saveConfig(dir, config)
            assertNull(MultiSceneDetectorAssets.load(dir))
        }
    }

    private fun withBundle(block: (File, JSONObject, JSONObject) -> Unit) {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "aicamera-app/app/src/main/assets/ai/v9").isDirectory }
        val assets = File(root, "aicamera-app/app/src/main/assets/ai/v9")
        val parent = File(root, "build/scene-detector-asset-tests").apply { mkdirs() }.canonicalFile
        val dir = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        try {
            for (name in listOf("object_detector.onnx", "object_detector_manifest.json", "android_runtime_config.json")) {
                File(assets, name).copyTo(File(dir, name))
            }
            block(dir, JSONObject(File(dir, "android_runtime_config.json").readText()),
                JSONObject(File(dir, "object_detector_manifest.json").readText()))
        } finally {
            check(dir.canonicalFile.toPath().startsWith(parent.toPath()))
            dir.deleteRecursively()
        }
    }

    private fun saveConfig(dir: File, config: JSONObject) = File(dir, "android_runtime_config.json").writeText(config.toString())
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
