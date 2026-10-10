package com.example.aicamera.pipeline

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        clearLoadedState()
        try {
            val embFile = File(indexDir, "templates.embeddings.f32")
            val metaFile = File(indexDir, "templates.metadata.jsonl")
            val keysFile = File(indexDir, "templates.keys.json")

            if (!embFile.exists() || !metaFile.exists() || !keysFile.exists()) {
                Log.e(TAG, "模板索引文件缺失: emb=${embFile.exists()}, meta=${metaFile.exists()}, keys=${keysFile.exists()}")
                return false
            }

            loadEmbeddings(embFile)
            loadTemplateIds(keysFile)
            loadMetadata(metaFile)

            if (embeddings.size != templateIds.size) {
                throw IllegalStateException(
                    "embedding(${embeddings.size}) 与 keys(${templateIds.size}) 数量不一致"
                )
            }
            val missingMetadata = templateIds.filter { it !in metadata }
            if (missingMetadata.isNotEmpty()) {
                throw IllegalStateException("有 ${missingMetadata.size} 个 embedding 缺少 metadata")
            }

            templateCount = embeddings.size
            Log.i(TAG, "模板索引加载完成: $templateCount 条模板, ${V9EmbeddingRuntime.EMBEDDING_DIM} 维")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "模板索引加载失败", e)
            clearLoadedState()
            return false
        }
    }

    private fun loadEmbeddings(file: File) {
        val bytesPerEmbedding = V9EmbeddingRuntime.EMBEDDING_DIM * Float.SIZE_BYTES
        if (file.length() <= 0L || file.length() % bytesPerEmbedding != 0L) {
            throw IllegalStateException("embedding 文件大小不是 $bytesPerEmbedding 的整数倍: ${file.length()}")
        }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val dim = V9EmbeddingRuntime.EMBEDDING_DIM
        val count = floatBuffer.remaining() / dim

        val temp = FloatArray(floatBuffer.remaining())
        floatBuffer.get(temp)

        for (i in 0 until count) {
            val embedding = sliceEmbedding(temp, i, dim)
            if (!V9EmbeddingRuntime.isUsableEmbedding(embedding)) {
                throw IllegalStateException("第 $i 条 template embedding 非法")
            }
            // Normalize once at load, keeping per-frame retrieval a dot-product scan.
            embeddings.add(V9EmbeddingRuntime.l2Normalize(embedding))
        }
        Log.d(TAG, "从 ${file.name} 加载 ${embeddings.size} 条 embedding")
    }

    /** Uses the producer-generated keys file as the only embedding-order authority. */
    private fun loadTemplateIds(file: File) {
        val ids = parseTemplateIds(file.readText())
        templateIds.addAll(ids)
        Log.d(TAG, "从 ${file.name} 加载 ${templateIds.size} 条 template key")
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
                    if (templateId.isBlank()) {
                        throw IllegalArgumentException("metadata template_id 为空")
                    }
                    if (metadata.containsKey(templateId)) {
                        throw IllegalArgumentException("metadata template_id 重复: $templateId")
                    }

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
                        visualSummary = visualSummary ?: "",
                        lightingContrast = lighting?.optString("contrast", "medium") ?: "medium",
                        timeOfDay = lighting?.optString("time_of_day", "unknown") ?: "unknown"
                    )
                } catch (e: Exception) {
                    throw IllegalArgumentException("metadata 行非法: ${e.message}", e)
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
        if (topK <= 0 || embeddings.isEmpty() || !V9EmbeddingRuntime.isUsableEmbedding(query)) {
            return emptyList()
        }

        val normalizedQuery = V9EmbeddingRuntime.l2Normalize(query.copyOf())
        val best = ArrayList<ScoredIndex>(minOf(topK, embeddings.size))
        for (i in embeddings.indices) {
            val score = V9EmbeddingRuntime.cosineSimilarity(normalizedQuery, embeddings[i]).coerceIn(-1f, 1f)
            if (!score.isFinite()) continue
            insertTopHit(best, ScoredIndex(i, score), topK)
        }
        return best.map { scored ->
            val idx = scored.index
            val tid = templateIds[idx]
            SearchHit(
                templateId = tid,
                cosine = scored.score,
                metadata = metadata[tid]
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
        return candidates.asSequence().mapNotNull { hit ->
            val meta = hit.metadata
            var rerankScore = hit.cosine
            if (!rerankScore.isFinite() || hit.templateId.isBlank()) return@mapNotNull null
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
            hit.copy(rerankScore = rerankScore).takeIf { it.rerankScore.isFinite() }
        }.sortedWith(
            compareByDescending<SearchHit> { it.rerankScore }.thenBy { it.templateId }
        ).take(5).toList()
    }

    fun getMetadata(templateId: String): TemplateMeta? = metadata[templateId]

    private fun insertTopHit(best: MutableList<ScoredIndex>, candidate: ScoredIndex, topK: Int) {
        val insertAt = best.indexOfFirst { current ->
            candidate.score > current.score ||
                (candidate.score == current.score && candidate.index < current.index)
        }.let { if (it < 0) best.size else it }
        if (insertAt >= topK && best.size >= topK) return
        best.add(insertAt, candidate)
        if (best.size > topK) best.removeAt(best.lastIndex)
    }

    private fun clearLoadedState() {
        embeddings.clear()
        metadata.clear()
        templateIds.clear()
        templateCount = 0
    }

    private data class ScoredIndex(val index: Int, val score: Float)

    companion object {
        private const val TAG = "V9TemplateIndex"

        /**
         * 从扁平 float 数组中切出第 row 条 embedding（每条 dim 维）。
         *
         * 修复历史 bug：原实现 `(i + 1 * dim)` 因运算符优先级变成 `i + dim`，
         * i=2 时起止倒挂直接抛 IllegalArgumentException，导致索引永远加载失败。
         * 抽成纯函数以便单元测试覆盖。
         */
        fun sliceEmbedding(flat: FloatArray, row: Int, dim: Int): FloatArray {
            require(row >= 0) { "row 必须 >= 0，当前 $row" }
            require(dim > 0) { "dim 必须 > 0，当前 $dim" }
            val start = row.toLong() * dim
            val end = start + dim
            require(end <= flat.size.toLong()) {
                "第 $row 条 embedding 越界: [$start, $end)，总长度 ${flat.size}"
            }
            return flat.copyOfRange(start.toInt(), end.toInt())
        }

        /** Validates and extracts the producer-authored embedding order from templates.keys.json. */
        fun parseTemplateIds(keysJson: String): List<String> {
            val root = JSONObject(keysJson)
            val dimension = root.optInt("dimension", -1)
            require(dimension == V9EmbeddingRuntime.EMBEDDING_DIM) {
                "templates.keys.json dimension=$dimension，不匹配 ${V9EmbeddingRuntime.EMBEDDING_DIM}"
            }
            val items = root.optJSONArray("items")
                ?: throw IllegalArgumentException("templates.keys.json 缺少 items")
            val expectedCount = root.optInt("count", items.length())
            require(expectedCount == items.length() && expectedCount > 0) {
                "templates.keys.json count=$expectedCount, items=${items.length()}"
            }
            val ids = ArrayList<String>(items.length())
            val seenIds = HashSet<String>(items.length())
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index)
                    ?: throw IllegalArgumentException("templates.keys.json items[$index] 非对象")
                val id = item.optString("template_id", item.optString("photo_id", "")).trim()
                require(id.isNotEmpty()) { "templates.keys.json items[$index] 缺少 template_id" }
                require(seenIds.add(id)) { "templates.keys.json template_id 重复: $id" }
                ids.add(id)
            }
            return ids
        }
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
    val visualSummary: String,
    /** annotation.lighting.contrast; absent legacy rows default to medium. */
    val lightingContrast: String = "medium",
    /** annotation.lighting.time_of_day; absent legacy rows default to unknown. */
    val timeOfDay: String = "unknown"
)

/** 场景上下文（用于重排），后续可由独立场景分类器填充 */
data class SceneContext(
    val photoType: String? = null,          // "portrait" / "landscape" / "still_life"
    val sceneCategory: String? = null,       // 细分类别
    val sceneConfidence: Float = 0f,
    val preferredComposition: String? = null,
    val lightingQuality: String? = null
)
