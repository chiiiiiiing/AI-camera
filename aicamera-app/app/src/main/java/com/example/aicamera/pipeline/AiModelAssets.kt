package com.example.aicamera.pipeline

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

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
 * - 由 [AiEngineManager] 在后台线程调用（首次启动或版本升级）
 *
 * ## 完整性
 * 复制后按 `android_onnx_runtime_manifest.json` 中的 sha256 校验部署模型，
 * 防止截断/损坏的模型进入推理链路（见 [verifyModelIntegrity]）。
 */
object AiModelAssets {

    private const val TAG = "AiModelAssets"
    private const val ASSET_BASE = "ai/v9"
    private const val VERSION_FILE = "ai_model_version.txt"

    /** 资产内容变化时递增，强制旧设备重新复制 */
    private const val CURRENT_VERSION = "v11-structure-guidance-1"

    // Optional capabilities fail closed in their own readers. A rejected quality head
    // must never prevent the existing retrieval model from starting.
    private val OPTIONAL_FILES = listOf(
        "reference_structures.json", "quality_policy.json", "quality_head.json"
    )

    /** 必须的部署文件；模型精度由 manifest 记录，不编码在部署文件名中。 */
    private val REQUIRED_FILES = listOf(
        MODEL_FILE,
        "android_onnx_runtime_manifest.json",
        "android_runtime_config.json"
    )

    /** 必须的索引文件（usearch 索引为 Python 原型，Android 端用全量余弦扫描，不需要） */
    private val REQUIRED_INDEX_FILES = listOf(
        "templates.embeddings.f32",
        "templates.keys.json",
        "templates.metadata.jsonl"
    )

    /** 模型文件名（sha256 校验对象） */
    private const val MODEL_FILE = "student_model.onnx"
    private const val MANIFEST_FILE = "android_onnx_runtime_manifest.json"

    // ── 公开 API ──────────────────────────────────────────────

    /**
     * 确保 AI 模型文件存在于 filesDir 中，且必需文件齐备。
     *
     * @param context App context
     * @return 模型文件所在目录的 File，失败返回 null
     */
    @Synchronized
    fun ensureModelFiles(context: Context): File? {
        val targetDir = File(context.filesDir, ASSET_BASE)
        val indexDir = File(targetDir, "template_index")

        // 检查版本，已是最新则跳过
        if (isUpToDate(targetDir) && validate(targetDir, indexDir) != null && verifyModelIntegrity(targetDir)) {
            Log.i(TAG, "AI 模型文件已是最新版本，跳过复制")
            return targetDir
        }

        // 清理旧文件
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        indexDir.mkdirs()

        // 复制模型文件（全部为必需，缺失即失败）
        var allSuccess = true
        for (fileName in REQUIRED_FILES) {
            val assetPath = "$ASSET_BASE/$fileName"
            val targetFile = File(targetDir, fileName)
            if (!copyAsset(context, assetPath, targetFile)) {
                Log.e(TAG, "模型文件复制失败: $fileName")
                allSuccess = false
            }
        }

        // 复制索引文件
        for (fileName in REQUIRED_INDEX_FILES) {
            val assetPath = "$ASSET_BASE/template_index/$fileName"
            val targetFile = File(indexDir, fileName)
            if (!copyAsset(context, assetPath, targetFile)) {
                Log.e(TAG, "索引文件复制失败: $fileName")
                allSuccess = false
            }
        }

        for (fileName in OPTIONAL_FILES) {
            val targetFile = File(targetDir, fileName)
            if (!copyAsset(context, "$ASSET_BASE/$fileName", targetFile)) {
                // Remove a partial file left by an interrupted optional copy.
                targetFile.delete()
            }
        }

        if (!allSuccess) {
            Log.e(TAG, "部分 AI 文件复制失败")
            return null
        }

        // A version marker only certifies a complete, verified copy. Missing or corrupted cache
        // files are recopied on this same startup, not left broken until a manual reinstall.
        if (validate(targetDir, indexDir) == null || !verifyModelIntegrity(targetDir)) return null

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
     * 验证必需文件完整性（模型与索引均为必需）。
     */
    fun validate(modelDir: File, indexDir: File): File? {
        val missingModel = REQUIRED_FILES.filter { !File(modelDir, it).isFile || File(modelDir, it).length() == 0L }
        val missingIndex = REQUIRED_INDEX_FILES.filter { !File(indexDir, it).isFile || File(indexDir, it).length() == 0L }

        if (missingModel.isNotEmpty()) {
            Log.e(TAG, "缺失模型文件: $missingModel")
            return null
        }
        if (missingIndex.isNotEmpty()) {
            Log.e(TAG, "缺失索引文件: $missingIndex")
            return null
        }

        return modelDir
    }

    /**
     * 按 manifest 中的 sha256 校验部署模型完整性。
     *
     * @return true = 校验通过；缺失或损坏的 manifest / 模型返回 false。
     */
    fun verifyModelIntegrity(modelDir: File): Boolean {
        val expected = expectedModelSha256(modelDir) ?: run {
            Log.w(TAG, "manifest 未提供有效 sha256，模型校验失败")
            return false
        }
        val modelFile = File(modelDir, MODEL_FILE)
        if (!modelFile.exists()) {
            Log.e(TAG, "模型文件不存在，无法校验")
            return false
        }
        val actual = try {
            sha256(modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "读取模型校验失败", e)
            return false
        }
        val match = expected.equals(actual, ignoreCase = true)
        if (!match) {
            Log.e(TAG, "模型 sha256 不匹配! 期望 $expected，实际 $actual")
        } else {
            Log.i(TAG, "模型 sha256 校验通过")
        }
        return match
    }

    /** 新 manifest 使用 deployment_onnx；仅在该字段不存在时兼容旧 manifest。 */
    private fun expectedModelSha256(modelDir: File): String? {
        return try {
            val manifest = File(modelDir, MANIFEST_FILE)
            if (!manifest.exists()) return null
            val content = JSONObject(manifest.readText())
            val deployment = if (content.has("deployment_onnx")) {
                content.optJSONObject("deployment_onnx")
            } else {
                content.optJSONObject("static_int8_onnx")
            }
            deployment
                ?.optString("sha256")
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        } catch (e: Exception) {
            Log.w(TAG, "读取 manifest sha256 失败: ${e.message}")
            null
        }
    }

    /** 流式计算文件 sha256（不一次性读入内存） */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取模型文件路径。
     */
    fun getModelPath(context: Context): String? {
        val dir = File(context.filesDir, ASSET_BASE)
        val modelFile = File(dir, MODEL_FILE)
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    /**
     * 获取索引目录路径。
     */
    fun getIndexDir(context: Context): File? {
        val dir = File(context.filesDir, "$ASSET_BASE/template_index")
        return if (dir.exists() && File(dir, "templates.embeddings.f32").exists()) dir else null
    }

    /**
     * Removes only the app-private, reproducible V9 asset cache after a failed validation/load.
     * The next [ensureModelFiles] call copies a fresh bundled version. This never touches photos,
     * user settings, or any directory outside filesDir/ai/v9.
     */
    @Synchronized
    fun invalidateModelFiles(context: Context) {
        val targetDir = File(context.filesDir, ASSET_BASE)
        if (!targetDir.exists()) return
        if (targetDir.deleteRecursively()) {
            Log.w(TAG, "已失效损坏的 AI 资产缓存，下次启动将重新复制")
        } else {
            Log.w(TAG, "无法完整删除损坏的 AI 资产缓存: $targetDir")
        }
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
