package com.example.aicamera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class AiModelAssetsValidationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun completeFiles(): Pair<File, File> {
        val modelDir = temporaryFolder.newFolder("model")
        val indexDir = File(modelDir, "template_index").apply { mkdirs() }
        listOf("student_model.onnx", "android_onnx_runtime_manifest.json",
            "android_runtime_config.json").forEach { File(modelDir, it).writeText("fixture") }
        listOf("templates.embeddings.f32", "templates.keys.json", "templates.metadata.jsonl")
            .forEach { File(indexDir, it).writeText("fixture") }
        return modelDir to indexDir
    }

    @Test
    fun `file presence validation accepts a complete nonempty set`() {
        val (modelDir, indexDir) = completeFiles()
        assertEquals(modelDir, AiModelAssets.validate(modelDir, indexDir))
    }

    @Test
    fun `truncated cached index is rejected even when its path exists`() {
        val (modelDir, indexDir) = completeFiles()
        File(indexDir, "templates.embeddings.f32").writeBytes(byteArrayOf())
        assertNull(AiModelAssets.validate(modelDir, indexDir))
    }

    @Test
    fun `a directory in place of a required model file is rejected`() {
        val (modelDir, indexDir) = completeFiles()
        File(modelDir, "student_model.onnx").apply { delete(); mkdir() }
        assertNull(AiModelAssets.validate(modelDir, indexDir))
    }

    @Test
    fun `missing integrity manifest never silently passes verification`() {
        val modelDir = temporaryFolder.newFolder("missing-manifest")
        File(modelDir, "student_model.onnx").writeText("unverified model")
        assertFalse(AiModelAssets.verifyModelIntegrity(modelDir))
    }

    private fun fixtureSha256(modelDir: File): String = MessageDigest.getInstance("SHA-256")
        .digest(File(modelDir, "student_model.onnx").readBytes())
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `deployment hash verifies the generic model file regardless of precision`() {
        val (modelDir, _) = completeFiles()
        File(modelDir, "android_onnx_runtime_manifest.json").writeText(
            """{"deployment_onnx":{"precision":"fp32","sha256":"${fixtureSha256(modelDir)}"}}"""
        )
        assertTrue(AiModelAssets.verifyModelIntegrity(modelDir))
        File(modelDir, "student_model.onnx").appendText("corruption")
        assertFalse(AiModelAssets.verifyModelIntegrity(modelDir))
    }

    @Test
    fun `deployment entry takes precedence over the old calibration model hash`() {
        val (modelDir, _) = completeFiles()
        File(modelDir, "android_onnx_runtime_manifest.json").writeText(
            """{"deployment_onnx":{"sha256":"${fixtureSha256(modelDir)}"},"static_int8_onnx":{"sha256":"${"0".repeat(64)}"}}"""
        )
        assertTrue(AiModelAssets.verifyModelIntegrity(modelDir))
    }

    @Test
    fun `malformed deployment entry cannot pass using a legacy hash`() {
        val (modelDir, _) = completeFiles()
        val hash = fixtureSha256(modelDir)
        for (entry in listOf("null", "{}", """{"sha256":"invalid"}""", """"not an object"""")) {
            File(modelDir, "android_onnx_runtime_manifest.json").writeText(
                """{"deployment_onnx":$entry,"static_int8_onnx":{"sha256":"$hash"}}"""
            )
            assertFalse(AiModelAssets.verifyModelIntegrity(modelDir))
        }
    }

    @Test
    fun `legacy manifest hash is supported only when deployment entry is absent`() {
        val (modelDir, _) = completeFiles()
        File(modelDir, "android_onnx_runtime_manifest.json").writeText(
            """{"static_int8_onnx":{"sha256":"${fixtureSha256(modelDir)}"}}"""
        )
        assertTrue(AiModelAssets.verifyModelIntegrity(modelDir))
    }

    @Test
    fun `a legacy named model does not satisfy the new asset bundle`() {
        val (modelDir, indexDir) = completeFiles()
        assertTrue(File(modelDir, "student_model.onnx").renameTo(File(modelDir, "student_model.static_int8.onnx")))
        assertNull(AiModelAssets.validate(modelDir, indexDir))
    }
}
