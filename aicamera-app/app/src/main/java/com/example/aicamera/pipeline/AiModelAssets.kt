package com.example.aicamera.pipeline

import android.content.Context
import android.util.Log
import com.example.aicamera.camera.CameraConfig
import java.io.File
import java.io.FileOutputStream

/**
 * AI 模型资产复制工具 — 将 assets 中的模型和索引文件复制到 app-private files 目录。
 *
 * ## 原因
 * ONNX Runtime 需要文件路径加载模型，不能直接从 assets 读取。
 * 索引文件（embeddings.f32 / metadata.jsonl）也是普通文件读写。
 *
 * ## 源 → 目标
 * ```
 * assets/ai/v9/ → filesDir/ai/v9/
 * ```
 *
 * ## 何时调用
 * - App 首次启动（检查 filesDir/ai/v9/ 是否存在）
 * - 版本升级后（检查版本标记）
 */
object AiModelAssets {

    private const val TAG = "AiModelAssets"
    private const val ASSET_BASE = "ai/v9"
    private const val VERSION_FILE = "ai_model_version.txt"
    private const val CURRENT_VERSION = "v9-static-int8-onnx-1"

    /** 必须的模型文件列表 */
    private val REQUIRED_FILES = listOf(
        "student_model.static_int8.onnx",
        "student_model.fp32.onnx",
        "android_onnx_runtime_manifest.json",
        "android_runtime_config.json"
    )

    /** 必须的索引文件列表 */
    private val REQUIRED_INDEX_FILES = listOf(
        "templates.embeddings.f32",
        "templates.keys.json",
        "templates.metadata.jsonl",
        "templates.usearch.idx",
        "templates.usearch.json"
    )

    // ── 公开 API ──────────────────────────────────────────────

    /**
     * 确保 AI 模型文件存在于 filesDir 中。
     *
     * @param context App context
     * @return 模型文件所在目录的 File，失败返回 null
     */
    fun ensureModelFiles(context: Context): File? {
        val targetDir = File(context.filesDir, ASSET_BASE)
        val indexDir = File(targetDir, "template_index")

        // 检查版本，已是最新则跳过
        if (isUpToDate(targetDir)) {
            Log.i(TAG, "AI 模型文件已是最新版本，跳过复制")
            return validate(targetDir, indexDir)
        }

        // 清理旧文件
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        indexDir.mkdirs()

        // 复制模型文件
        var allSuccess = true
        for (fileName in REQUIRED_FILES) {
            val assetPath = "$ASSET_BASE/$fileName"
            val targetFile = File(targetDir, fileName)
            if (!copyAsset(context, assetPath, targetFile)) {
                Log.w(TAG, "模型文件缺失 (可能非必需): $fileName")
                // 非必需文件的缺失不阻塞
            }
        }

        // 复制索引文件
        for (fileName in REQUIRED_INDEX_FILES) {
            val assetPath = "$ASSET_BASE/template_index/$fileName"
            val targetFile = File(indexDir, fileName)
            if (!copyAsset(context, assetPath, targetFile)) {
                Log.e(TAG, "索引文件缺失: $fileName")
                allSuccess = false
            }
        }

        if (!allSuccess) {
            Log.e(TAG, "部分 AI 文件复制失败")
            return null
        }

        // 写版本标记
        try {
            File(targetDir, VERSION_FILE).writeText(CURRENT_VERSION)
        } catch (e: Exception) {
            Log.w(TAG, "写入版本文件失败", e)
        }

        Log.i(TAG, "AI 模型文件复制完成: $targetDir")
        return targetDir
    }

    /**
     * 验证模型文件完整性。
     */
    fun validate(modelDir: File, indexDir: File): File? {
        val missingModel = REQUIRED_FILES.filter { !File(modelDir, it).exists() }
        val missingIndex = REQUIRED_INDEX_FILES.filter { !File(indexDir, it).exists() }

        if (missingModel.isNotEmpty()) {
            Log.w(TAG, "缺失模型文件: $missingModel")
        }
        if (missingIndex.isNotEmpty()) {
            Log.e(TAG, "缺失索引文件: $missingIndex")
            return null
        }

        return modelDir
    }

    /**
     * 获取模型文件路径。
     */
    fun getModelPath(context: Context): String? {
        val dir = File(context.filesDir, ASSET_BASE)
        val modelFile = File(dir, "student_model.static_int8.onnx")
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    /**
     * 获取索引目录路径。
     */
    fun getIndexDir(context: Context): File? {
        val dir = File(context.filesDir, "$ASSET_BASE/template_index")
        return if (dir.exists() && File(dir, "templates.embeddings.f32").exists()) dir else null
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun isUpToDate(targetDir: File): Boolean {
        val versionFile = File(targetDir, VERSION_FILE)
        if (!versionFile.exists()) return false
        return try {
            versionFile.readText().trim() == CURRENT_VERSION
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAsset(context: Context, assetPath: String, targetFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
