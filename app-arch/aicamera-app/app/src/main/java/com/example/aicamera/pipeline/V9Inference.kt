package com.example.aicamera.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.filter.FilterReason
import com.example.aicamera.filter.FilterRecommendation
import com.example.aicamera.filter.FilterRecommendationEngine
import com.example.aicamera.filter.FilterRecommendationStabilizer
import com.example.aicamera.filter.FilterRecommendations
import com.example.aicamera.filter.FrameColorStatsExtractor
import com.example.aicamera.guidance.GuidanceCue
import java.nio.ByteBuffer
import java.io.Closeable

/**
 * V9 ONNX 推理器 — 实现 IInference 接口，接入帧处理管线。
 *
 * ## 完整链路
 * ```
 * FramePipeline.process()
 *   → V9Inference.predict(buffer, frame)
 *     → bitmapToNchw224 (复用预处理阶段保留的 224×224 Bitmap)
 *     → V9EmbeddingRuntime.embed()
 *     → V9TemplateIndex.search(topK=8)
 *     → V9TemplateIndex.rerank()
 *     → 稳定切换策略（候选连续领先才换模板）
 *     → 模板 metadata → FakeInferenceOutput (兼容格式)
 * ```
 *
 * ## 与 FakeInference 的关系
 * - FakeInference: 基于亮度值的简单启发式，不依赖模型
 * - V9Inference: 真实 ONNX 模型 embedding → 模板检索
 * - 通过 IInference 接口热替换（AiEngineManager READY 后由 PipelineViewModel 换入）
 */
class V9Inference(
    private val runtime: V9EmbeddingRuntime,
    private val index: V9TemplateIndex,
    private val colorStatsExtractor: FrameColorStatsExtractor = FrameColorStatsExtractor(),
    private val filterEngine: FilterRecommendationEngine = FilterRecommendationEngine(),
    private val filterStabilizer: FilterRecommendationStabilizer = FilterRecommendationStabilizer()
) : IInference, Closeable {

    override val typeName: String = "V9-ONNX"

    // ── 稳定切换状态 ──────────────────────────────────────────
    //
    // 修复历史死锁（P1-4）：旧实现用「当前模板」计数，新模板领先时计数器
    // 永远被重置为 1，导致模板选定后永不切换。
    // 正确做法：给「挑战者候选」单独计数，连续 N 次领先才真正换王。

    /** 当前生效的模板 */
    private var currentTemplateId: String? = null

    /** 挑战者候选模板 */
    private var candidateTemplateId: String? = null

    /** 候选连续领先次数 */
    private var candidateLeadCount: Int = 0

    /** 低 margin 阈值（top1/top2 margin < 0.01 时不切换） */
    private val lowMarginThreshold: Float = 0.01f

    /** 稳定切换所需候选连续领先帧数 */
    private val stableSwitchFrames: Int = 2

    // ── IInference 实现 ───────────────────────────────────────

    override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
        // Step 1: 获取 Bitmap — 优先使用预处理阶段保留的 224×224 Bitmap，
        //         否则从 buffer 重建（降级路径）
        val reconstructed = if (frame.originalBitmap == null) {
            bufferToBitmap(buffer, frame.width, frame.height)
        } else null
        val sourceBitmap = frame.originalBitmap ?: reconstructed!!

        try {
            val nchw = V9EmbeddingRuntime.bitmapToNchw224(sourceBitmap)
            val embedding = runtime.embed(nchw)

            // Step 2: 检索 + 规则重排
            val candidates = index.search(embedding, topK = 8)
            if (candidates.isEmpty()) {
                filterStabilizer.reset()
                return createEmptyResult(FilterRecommendations.original())
            }
            val reranked = index.rerank(candidates)
            if (reranked.isEmpty()) {
                filterStabilizer.reset()
                return createEmptyResult(FilterRecommendations.original())
            }

            // Step 3: 推荐滤镜。失败只降级推荐，不影响现有 V9 构图链路。
            val filterRecommendation = try {
                val stats = colorStatsExtractor.extract(sourceBitmap)
                filterStabilizer.stabilize(filterEngine.recommend(reranked, stats))
            } catch (e: Exception) {
                Log.w(CameraConfig.TAG, "[V9] 滤镜推荐失败，回退 original", e)
                filterStabilizer.reset()
                FilterRecommendations.original(
                    reason = FilterReason.FALLBACK,
                    sourceTemplateId = reranked.firstOrNull()?.templateId
                )
            }

            // Step 4: 现有模板稳定策略继续只负责构图输出。
            val selectedHit = selectStable(reranked)
            val meta = selectedHit.metadata ?: index.getMetadata(selectedHit.templateId)
            return if (meta != null) {
                metaToFakeOutput(meta, selectedHit.rerankScore, filterRecommendation)
            } else {
                createEmptyResult(filterRecommendation)
            }
        } finally {
            reconstructed?.recycle()
        }
    }

    /**
     * 稳定选择：低 margin 不换、候选连续 [stableSwitchFrames] 次领先才换。
     */
    private fun selectStable(reranked: List<SearchHit>): SearchHit {
        val top1 = reranked.first()
        val top2 = reranked.getOrNull(1)
        val margin = if (top2 != null) top1.rerankScore - top2.rerankScore else Float.MAX_VALUE
        val current = currentTemplateId

        // 首个模板直接生效
        if (current == null) {
            currentTemplateId = top1.templateId
            return top1
        }

        // 现任仍是 top1 → 清空挑战者
        if (top1.templateId == current) {
            candidateTemplateId = null
            candidateLeadCount = 0
            return top1
        }

        // margin 太低 → 不换，但现任若掉出候选列表则允许下一帧重新评估
        if (margin < lowMarginThreshold) {
            candidateTemplateId = null
            candidateLeadCount = 0
            return reranked.find { it.templateId == current } ?: top1
        }

        // 挑战者连续领先计数
        if (candidateTemplateId == top1.templateId) {
            candidateLeadCount++
        } else {
            candidateTemplateId = top1.templateId
            candidateLeadCount = 1
        }

        return if (candidateLeadCount >= stableSwitchFrames) {
            // 换王
            currentTemplateId = top1.templateId
            candidateTemplateId = null
            candidateLeadCount = 0
            Log.i(CameraConfig.TAG, "[V9] 模板切换 → ${top1.templateId} (score=${"%.3f".format(top1.rerankScore)})")
            top1
        } else {
            // 未稳定，继续用现任
            reranked.find { it.templateId == current } ?: top1
        }
    }

    // ── 结果映射 ──────────────────────────────────────────────

    /**
     * 从模板 metadata 生成 FakeInferenceOutput（兼容现有渲染管线）。
     *
     * metadata 字段 → 辅助线映射：
     * - rule_of_thirds → 三分线 + 三分交点目标区
     * - centered_subject → 中心线 + 中心目标区
     * - leading_lines=true → 引导线提示
     * - horizon_position → 水平参考线（reference_y）
     * - negative_space=high → 留白提示
     */
    private fun metaToFakeOutput(
        meta: TemplateMeta,
        score: Float,
        filterRecommendation: FilterRecommendation
    ): FakeInferenceOutput {
        val strategy = compositionTypeToStrategy(meta.compositionType)
        val scene = sceneCategoryToSceneType(meta.sceneCategory)

        val guideLines = mutableListOf<FakeGuideLine>()

        // 1. 三分线（几乎所有模板都适用）
        guideLines.add(
            FakeGuideLine(
                type = "rule_of_thirds_grid", visible = true,
                params = mapOf("vertical_lines" to listOf(0.333f, 0.667f),
                    "horizontal_lines" to listOf(0.333f, 0.667f))
            )
        )

        // 2. 目标区域 — 根据构图类型选位置
        val targetZone = when (meta.subjectPosition) {
            "center" -> FakeGuideLine("target_zone", true,
                mapOf("center_x" to 0.5f, "center_y" to 0.5f, "radius" to 0.15f))
            else -> FakeGuideLine("target_zone", true,
                mapOf("center_x" to 0.333f, "center_y" to 0.333f, "radius" to 0.12f))
        }
        guideLines.add(targetZone)

        // 3. 水平仪 + 水平参考线（reference_y 合并进同一条线，避免重复绘制）
        val horizonRefY = when (meta.horizonPosition) {
            "upper_third" -> 0.333f
            "lower_third" -> 0.667f
            "middle" -> 0.5f
            else -> null
        }
        val horizonParams = mutableMapOf<String, Any>("angle_degrees" to 0f)
        if (horizonRefY != null) horizonParams["reference_y"] = horizonRefY
        guideLines.add(FakeGuideLine("horizon_level", true, horizonParams))

        // 4. 主体框（人像模式）
        if (meta.photoType == "portrait") {
            guideLines.add(
                FakeGuideLine("subject_box", true,
                    mapOf("x" to 0.2f, "y" to 0.25f, "width" to 0.6f, "height" to 0.5f))
            )
        }

        // 5. 引导线提示
        if (meta.leadingLines) {
            guideLines.add(
                FakeGuideLine("direction_arrow", true,
                    mapOf("from_x" to 0.3f, "from_y" to 0.6f, "to_x" to 0.5f, "to_y" to 0.35f))
            )
        }

        val progress = score.coerceIn(0f, 1f)

        return FakeInferenceOutput(
            sceneType = scene,
            compositionStrategy = strategy,
            confidence = score.coerceIn(0f, 1f),
            luminance = 128f, // V9 不输出亮度
            guideLines = guideLines,
            guidanceCue = GuidanceCue.NONE, // 等 App 侧几何检测后更新
            compositionProgress = progress
        ).withV9FilterRecommendation(filterRecommendation)
    }

    private fun compositionTypeToStrategy(type: String): CompositionStrategy = when (type) {
        "centered_subject", "centered" -> CompositionStrategy.CENTER
        "symmetry" -> CompositionStrategy.SYMMETRY
        "leading_lines" -> CompositionStrategy.LEADING_LINE
        "framing" -> CompositionStrategy.FRAMING
        else -> CompositionStrategy.RULE_OF_THIRDS
    }

    private fun sceneCategoryToSceneType(category: String): SceneType = when {
        category.contains("portrait") -> SceneType.BRIGHT
        category.contains("landscape") -> SceneType.NORMAL
        category.contains("food") || category.contains("still_life") -> SceneType.NORMAL
        else -> SceneType.NORMAL
    }

    private fun createEmptyResult(
        filterRecommendation: FilterRecommendation
    ): FakeInferenceOutput {
        return FakeInferenceOutput(
            sceneType = SceneType.NORMAL,
            compositionStrategy = CompositionStrategy.RULE_OF_THIRDS,
            confidence = 0f,
            luminance = 128f,
            guideLines = listOf(
                FakeGuideLine("rule_of_thirds_grid", true,
                    mapOf("vertical_lines" to listOf(0.333f, 0.667f),
                        "horizontal_lines" to listOf(0.333f, 0.667f)))
            ),
            guidanceCue = GuidanceCue.NONE,
            compositionProgress = 0f
        ).withV9FilterRecommendation(filterRecommendation)
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private fun bufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var i = 0
        while (buffer.remaining() >= 12 && i < pixels.size) {
            val r = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
            val g = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
            val b = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (255 shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
            i++
        }
        buffer.rewind()
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    override fun close() {
        resetSession()
        runtime.close()
        Log.i(CameraConfig.TAG, "[V9Inference] 已释放")
    }

    override fun resetSession() {
        currentTemplateId = null
        candidateTemplateId = null
        candidateLeadCount = 0
        filterStabilizer.reset()
    }
}

/** Explicit adapter seam used by the real V9 path; FakeInference never calls it. */
internal fun FakeInferenceOutput.withV9FilterRecommendation(
    recommendation: FilterRecommendation
): FakeInferenceOutput = copy(filterRecommendation = recommendation)
