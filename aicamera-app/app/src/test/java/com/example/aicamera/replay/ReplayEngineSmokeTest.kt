package com.example.aicamera.replay

import com.example.aicamera.pipeline.V9EmbeddingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面功能回放台冒烟测试 — 验证主机 JVM 上的桌面版 ONNX Runtime
 * 能加载真实 student_model.onnx 并校验输入输出契约。
 *
 * 通过系统属性 `replay.assets` 可覆盖资产目录（默认从 app 模块目录出发）。
 */
class ReplayEngineSmokeTest {

    @Test
    fun desktopOrtLoadsRealStudentModel() {
        // 单测 workingDir 为 app 模块目录（aicamera-app/app），资产在其 src/main/assets 下
        val assetsDir = File(System.getProperty("replay.assets") ?: "src/main/assets/ai/v9")
        val modelFile = File(assetsDir, "student_model.onnx")
        assertTrue("模型文件不存在: ${modelFile.absolutePath}", modelFile.exists())

        V9EmbeddingRuntime(modelFile.absolutePath).use { runtime ->
            assertTrue("桌面 ONNX Runtime 未能加载模型", runtime.isLoaded)
            assertEquals("输入 tensor 名", "image", runtime.inputName)
            assertEquals("输出 tensor 名", "embedding", runtime.outputName)
            println("REPLAY-SMOKE OK: ${runtime.inputName} -> ${runtime.outputName} @ ${modelFile.name}")
        }
    }
}
