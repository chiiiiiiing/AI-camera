package com.example.aicamera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiModelAssetsValidationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun completeFiles(): Pair<File, File> {
        val modelDir = temporaryFolder.newFolder("model")
        val indexDir = File(modelDir, "template_index").apply { mkdirs() }
        listOf("student_model.static_int8.onnx", "android_onnx_runtime_manifest.json",
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
        File(modelDir, "student_model.static_int8.onnx").apply { delete(); mkdir() }
        assertNull(AiModelAssets.validate(modelDir, indexDir))
    }

    @Test
    fun `missing integrity manifest never silently passes verification`() {
        val modelDir = temporaryFolder.newFolder("missing-manifest")
        File(modelDir, "student_model.static_int8.onnx").writeText("unverified model")
        assertFalse(AiModelAssets.verifyModelIntegrity(modelDir))
    }
}
