package com.example.aicamera.pipeline

import android.content.Context
import android.util.Log
import com.example.aicamera.camera.CameraConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * V9 模板索引 — 加载 728 条模板 embedding + metadata，执行检索和规则重排。
 *
 * ## 工作流
 * ```
 * query embedding [512]
 *   → cosine similarity × 728 templates
 *   → top 8 candidates
 *   → rule-based reranking (photo_type / scene / composition / lighting)
 *   → top 5 stabilized results
 * ```
 *
 * ## 索引文件（从 assets 复制到 filesDir/ai/v9/template_index/）
 * - templates.embeddings.f32  (728 × 512 float32, ~1.5MB)
 * - templates.metadata.jsonl   (728 行 JSON, ~1.8MB)
 */
class V9TemplateIndex(
    private val indexDir: File
) {
    /** 模板数量 */
    var templateCount: Int = 0
        private set

    /** 是否加载成功 */
    val isLoaded: Boolean get() = embeddings.isNotEmpty() && metadata.isNotEmpty()

    /** embedding 列表 (N × 512) */
    private val embeddings = mutableListOf<FloatArray>()

    /** template_id → metadata JSON 映射 */
    private val metadata = mutableMapOf<String, TemplateMeta>()

    /** template_id 列表（与 embeddings 索引对应） */
    private val templateIds = mutableListOf<String>()

    // ── 加载索引 ──────────────────────────────────────────────

    fun load(): Boolean {
        try {
            val embFile = File(indexDir, "templates.embeddings.f32")
            val metaFile = File(indexDir, "templates.metadata.jsonl")

            if (!embFile.exists() || !metaFile.exists()) {
                Log.e(TAG, "模板索引文件缺失: emb=${embFile.exists()}, meta=${metaFile.exists()}")
                return false
            }

            loadEmbeddings(embFile)
            loadMetadata(metaFile)

            if (embeddings.size != metadata.size) {
                Log.w(TAG, "embedding 数量(${embeddings.size}) ≠ metadata 数量(${metadata.size})，将按 embedding 对齐")
            }

            templateCount = embeddings.size
            Log.i(TAG, "模板索引加载完成: $templateCount 条模板, ${V9EmbeddingRuntime.EMBEDDING_DIM} 维")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "模板索引加载失败", e)
            return false
        }
    }

    private fun loadEmbeddings(file: File) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val dim = V9EmbeddingRuntime.EMBEDDING_DIM
        val count = floatBuffer.remaining() / dim

        val temp = FloatArray(floatBuffer.remaining())
        floatBuffer.get(temp)

        for (i in 0 until count) {
            val emb = temp.copyOfRange(i * dim, (i + 1 * dim).coerceAtMost(temp.size))
            if (emb.size == dim) {
                embeddings.add(emb)
            }
        }
        Log.d(TAG, "从 ${file.name} 加载 ${embeddings.size} 条 embedding")
    }

    private fun loadMetadata(file: File) {
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val json = JSONObject(line)
                    val templateId = json.optString("template_id", json.optString("photo_id", ""))
                    val photoType = json.optString("photo_type", "unknown")
                    val sceneCategory = json.optString("scene_category", "unknown")
                    val annotation = json.optJSONObject("annotation")
                    val composition = annotation?.optJSONObject("composition")
                    val lighting = annotation?.optJSONObject("lighting")
                    val camera = annotation?.optJSONObject("camera")
                    val visualSummary = annotation?.optString("visual_summary", "")

                    metadata[templateId] = TemplateMeta(
                        templateId = templateId,
                        photoType = photoType,
                        sceneCategory = sceneCategory,
                        compositionType = composition?.optString("type", "rule_of_thirds") ?: "rule_of_thirds",
                        subjectPosition = composition?.optString("subject_position", "center") ?: "center",
                        horizonPosition = composition?.optString("horizon_position", "not_visible") ?: "not_visible",
                        leadingLines = composition?.optBoolean("leading_lines", false) ?: false,
                        negativeSpace = composition?.optString("negative_space", "medium") ?: "medium",
                        lightingQuality = lighting?.optString("quality", "soft") ?: "soft",
                        lightingDirection = lighting?.optString("direction", "front") ?: "front",
                        cameraAngle = camera?.optString("angle", "eye_level") ?: "eye_level",
                        cameraDistance = camera?.optString("distance", "medium") ?: "medium",
                        cameraOrientation = camera?.optString("orientation", "portrait") ?: "portrait",
                        visualSummary = visualSummary ?: ""
                    )
                    templateIds.add(templateId)
                } catch (e: Exception) {
                    Log.w(TAG, "解析 metadata 行失败: ${e.message}")
                }
            }
        }
        Log.d(TAG, "从 ${file.name} 加载 ${metadata.size} 条 metadata")
    }

    // ── 检索 ──────────────────────────────────────────────────

    /**
     * 检索 topK 个最相似的模板。
     *
     * @param query 512 维 query embedding (L2 normalized)
     * @param topK 返回前 K 个候选
     * @return 按 cosine similarity 降序排列的候选列表
     */
    fun search(query: FloatArray, topK: Int = 8): List<SearchHit> {
        if (embeddings.isEmpty()) return emptyList()

        // 计算所有模板的 cosine similarity
        val scores = FloatArray(embeddings.size)
        for (i in embeddings.indices) {
            scores[i] = V9EmbeddingRuntime.cosineSimilarity(query, embeddings[i])
        }

        // 用部分排序取 topK（避免全量排序）
        val indices = scores.indices.toList()
            .sortedByDescending { scores[it] }
            .take(topK)

        return indices.map { idx ->
            val tid = if (idx < templateIds.size) templateIds[idx] else ""
            SearchHit(
                templateId = tid,
                cosine = scores[idx],
                metadata = if (tid.isNotEmpty()) metadata[tid] else null
            )
        }
    }

    // ── 规则重排 ──────────────────────────────────────────────

    /**
     * 对候选列表做规则重排。
     *
     * @param candidates 原始 top8 候选
     * @param context 当前场景上下文（可先传默认值）
     * @return top5 重排结果
     */
    fun rerank(
        candidates: List<SearchHit>,
        context: SceneContext = SceneContext()
    ): List<SearchHit> {
        return candidates.map { hit ->
            val meta = hit.metadata
            var rerankScore = hit.cosine
            if (meta != null) {
                // photo_type 匹配 / 不匹配
                if (context.photoType != null && meta.photoType == context.photoType) {
                    rerankScore += 0.08f
                } else if (context.photoType != null) {
                    rerankScore -= 0.08f
                }
                // scene_category 弱加分
                if (context.sceneConfidence >= 0.7f && context.sceneCategory != null) {
                    if (meta.sceneCategory == context.sceneCategory) {
                        rerankScore += 0.06f
                    } else {
                        rerankScore -= 0.04f
                    }
                }
                // 构图匹配
                if (context.preferredComposition != null &&
                    meta.compositionType == context.preferredComposition
                ) {
                    rerankScore += 0.03f
                }
                // 光线匹配
                if (context.lightingQuality != null &&
                    meta.lightingQuality == context.lightingQuality
                ) {
                    rerankScore += 0.02f
                }
            }
            hit.copy(rerankScore = rerankScore)
        }.sortedByDescending { it.rerankScore }.take(5)
    }

    fun getMetadata(templateId: String): TemplateMeta? = metadata[templateId]

    companion object {
        private const val TAG = "V9TemplateIndex"
    }
}

// ── 数据类 ──────────────────────────────────────────────────

/** 检索命中结果 */
data class SearchHit(
    val templateId: String,
    val cosine: Float,
    val rerankScore: Float = 0f,
    val metadata: TemplateMeta? = null
)

/** 模板元数据（从 metadata.jsonl 提取的 App 需要字段） */
data class TemplateMeta(
    val templateId: String,
    val photoType: String,
    val sceneCategory: String,
    val compositionType: String,
    val subjectPosition: String,
    val horizonPosition: String,
    val leadingLines: Boolean,
    val negativeSpace: String,
    val lightingQuality: String,
    val lightingDirection: String,
    val cameraAngle: String,
    val cameraDistance: String,
    val cameraOrientation: String,
    val visualSummary: String
)

/** 场景上下文（用于重排），后续可由独立场景分类器填充 */
data class SceneContext(
    val photoType: String? = null,          // "portrait" / "landscape" / "still_life"
    val sceneCategory: String? = null,       // 细分类别
    val sceneConfidence: Float = 0f,
    val preferredComposition: String? = null,
    val lightingQuality: String? = null
)
