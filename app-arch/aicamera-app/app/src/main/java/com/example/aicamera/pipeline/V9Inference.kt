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
import com.example.aicamera.zoom.AutoZoomReason
import com.example.aicamera.zoom.AutoZoomRecommendation
import com.example.aicamera.zoom.AutoZoomRecommendationEngine
import com.example.aicamera.zoom.AutoZoomRecommendationStabilizer
import com.example.aicamera.zoom.AutoZoomRecommendations
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
    private val filterStabilizer: FilterRecommendationStabilizer = FilterRecommendationStabilizer(),
    private val autoZoomEngine: AutoZoomRecommendationEngine = AutoZoomRecommendationEngine(),
    private val autoZoomStabilizer: AutoZoomRecommendationStabilizer = AutoZoomRecommendationStabilizer(),
    private val sceneContextEstimator: V9SceneContextEstimator = V9SceneContextEstimator()
) : IInference, Closeable {

    override val typeName: String = "V9-ONNX"

    override val minInferenceIntervalMs: Long = 500L

    private val templateStabilizer = V9TemplateStabilizer()

    // ── IInference 实现 ───────────────────────────────────────

    @Synchronized
    override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
        // Step 1: 获取 Bitmap — 优先使用预处理阶段保留的 224×224 Bitmap，
        //         否则从 buffer 重建（降级路径）
        var reconstructed: Bitmap? = null
        try {
            val sourceBitmap = frame.originalBitmap ?: bufferToBitmap(buffer, frame.width, frame.height)
                .also { reconstructed = it }
            val nchw = V9EmbeddingRuntime.bitmapToNchw224(sourceBitmap)
            val embedding = runtime.embed(nchw)

            // Step 2: 检索 + 规则重排
            val candidates = index.search(embedding, topK = 8)
            if (candidates.isEmpty()) {
                resetSession()
                return createEmptyResult(
                    FilterRecommendations.original(),
                    AutoZoomRecommendations.disabled(AutoZoomReason.EMPTY_CANDIDATES)
                )
            }
            // Step 3: 从同一张 224x224 bitmap 提取一次统计，供重排和两个推荐器复用。
            val stats = try {
                colorStatsExtractor.extract(sourceBitmap)
            } catch (e: Exception) {
                Log.w(CameraConfig.TAG, "[V9] 颜色统计失败，自动变焦将安全禁用", e)
                null
            }

            val reranked = index.rerank(
                candidates,
                stats?.let { sceneContextEstimator.estimate(candidates, it) } ?: SceneContext()
            )
            if (reranked.isEmpty()) {
                resetSession()
                return createEmptyResult(
                    FilterRecommendations.original(),
                    AutoZoomRecommendations.disabled(AutoZoomReason.EMPTY_CANDIDATES)
                )
            }

            // Step 4: 推荐滤镜。失败只降级推荐，不影响现有 V9 构图链路。
            val filterRecommendation = try {
                if (stats == null) {
                    filterStabilizer.reset()
                    FilterRecommendations.original(
                        reason = FilterReason.FALLBACK,
                        sourceTemplateId = reranked.firstOrNull()?.templateId
                    )
                } else {
                    filterStabilizer.stabilize(filterEngine.recommend(reranked, stats))
                }
            } catch (e: Exception) {
                Log.w(CameraConfig.TAG, "[V9] 滤镜推荐失败，回退 original", e)
                filterStabilizer.reset()
                FilterRecommendations.original(
                    reason = FilterReason.FALLBACK,
                    sourceTemplateId = reranked.firstOrNull()?.templateId
                )
            }

            // Step 5: 自动变焦建议只在引擎内生成并稳定，不控制 CameraX 或 UI。
            val autoZoomRecommendation = try {
                if (stats == null) {
                    autoZoomStabilizer.stabilize(
                        AutoZoomRecommendations.disabled(
                            AutoZoomReason.STATS_UNAVAILABLE,
                            reranked.firstOrNull()?.templateId
                        )
                    )
                } else {
                    autoZoomStabilizer.stabilize(autoZoomEngine.recommend(reranked, stats))
                }
            } catch (e: Exception) {
                Log.w(CameraConfig.TAG, "[V9] 自动变焦推荐失败，安全禁用", e)
                autoZoomStabilizer.reset()
                AutoZoomRecommendations.disabled(
                    AutoZoomReason.FALLBACK,
                    reranked.firstOrNull()?.templateId
                )
            }

            // Step 6: 现有模板稳定策略继续只负责构图输出。
            val selectedHit = templateStabilizer.select(reranked)
            val meta = selectedHit.metadata ?: index.getMetadata(selectedHit.templateId)
            return if (meta != null) {
                metaToFakeOutput(meta, selectedHit.rerankScore, filterRecommendation, autoZoomRecommendation)
            } else {
                createEmptyResult(filterRecommendation, autoZoomRecommendation)
            }
        } catch (e: Exception) {
            // Model/session/index failures must not terminate the camera analysis pipeline.
            Log.e(CameraConfig.TAG, "[V9] 推理或检索失败，返回安全降级结果", e)
            resetSession()
            return createEmptyResult(
                FilterRecommendations.original(reason = FilterReason.FALLBACK),
                AutoZoomRecommendations.disabled(AutoZoomReason.FALLBACK)
            )
        } finally {
            reconstructed?.recycle()
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
        filterRecommendation: FilterRecommendation,
        autoZoomRecommendation: AutoZoomRecommendation
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
        v9SubjectTarget(meta.subjectPosition)?.let { (targetX, targetY) ->
            guideLines.add(FakeGuideLine("target_zone", true,
                mapOf("center_x" to targetX, "center_y" to targetY,
                    "radius" to if (meta.subjectPosition == "center") 0.15f else 0.12f)))
        }

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

        // Subject boxes come only from the live geometry detector; template metadata cannot
        // locate a person in the current preview frame.

        // 5. 引导线提示
        if (meta.leadingLines) {
            guideLines.add(
                FakeGuideLine("direction_arrow", true,
                    mapOf("from_x" to 0.3f, "from_y" to 0.6f, "to_x" to 0.5f, "to_y" to 0.35f))
            )
        }

        return FakeInferenceOutput(
            sceneType = scene,
            compositionStrategy = strategy,
            confidence = score.coerceIn(0f, 1f),
            luminance = 128f, // V9 不输出亮度
            guideLines = guideLines,
            guidanceCue = GuidanceCue.NONE, // 等 App 侧几何检测后更新
            // Similarity is retrieval evidence; only live geometry can report alignment.
            compositionProgress = 0f,
            expectsSubject = meta.photoType == "portrait"
        ).withV9FilterRecommendation(filterRecommendation)
            .withV9AutoZoomRecommendation(autoZoomRecommendation)
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
        filterRecommendation: FilterRecommendation,
        autoZoomRecommendation: AutoZoomRecommendation
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
            .withV9AutoZoomRecommendation(autoZoomRecommendation)
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private fun bufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE / 12) {
            "非法帧尺寸: ${width}x${height}"
        }
        val input = buffer.duplicate().order(buffer.order()).apply { rewind() }
        require(input.remaining() >= width * height * 12) { "RGB 帧数据截断" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var i = 0
        while (input.remaining() >= 12 && i < pixels.size) {
            val r = (input.float.coerceIn(0f, 1f) * 255).toInt()
            val g = (input.float.coerceIn(0f, 1f) * 255).toInt()
            val b = (input.float.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (255 shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
            i++
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    @Synchronized
    override fun close() {
        resetSession()
        runtime.close()
        Log.i(CameraConfig.TAG, "[V9Inference] 已释放")
    }

    @Synchronized
    override fun resetSession() {
        templateStabilizer.reset()
        filterStabilizer.reset()
        autoZoomStabilizer.reset()
    }
}

/** Explicit adapter seam used by the real V9 path; FakeInference never calls it. */
internal fun FakeInferenceOutput.withV9FilterRecommendation(
    recommendation: FilterRecommendation
): FakeInferenceOutput = copy(filterRecommendation = recommendation)

/** Explicit adapter seam used by the real V9 path; FakeInference never calls it. */
internal fun FakeInferenceOutput.withV9AutoZoomRecommendation(
    recommendation: AutoZoomRecommendation
): FakeInferenceOutput = copy(autoZoomRecommendation = recommendation)

/** Keeps the selected template and challenger history consistent with the returned hit. */
internal class V9TemplateStabilizer(
    private val lowMarginThreshold: Float = 0.01f,
    private val stableSwitchFrames: Int = 2
) {
    private var currentTemplateId: String? = null
    private var candidateTemplateId: String? = null
    private var candidateLeadCount = 0

    fun select(reranked: List<SearchHit>): SearchHit {
        require(reranked.isNotEmpty())
        val leader = reranked.first()
        val current = reranked.find { it.templateId == currentTemplateId }
        // A scene change can remove the old template completely. Adopt the returned leader
        // immediately in this case so the next frame stabilizes against what the user saw.
        if (current == null || leader.templateId == current.templateId) return adopt(leader)
        val margin = reranked.getOrNull(1)?.let { leader.rerankScore - it.rerankScore }
            ?: Float.MAX_VALUE
        if (margin < lowMarginThreshold) {
            candidateTemplateId = null
            candidateLeadCount = 0
            return current
        }
        if (candidateTemplateId == leader.templateId) {
            candidateLeadCount++
        } else {
            candidateTemplateId = leader.templateId
            candidateLeadCount = 1
        }
        return if (candidateLeadCount >= stableSwitchFrames) adopt(leader) else current
    }

    fun reset() {
        currentTemplateId = null
        candidateTemplateId = null
        candidateLeadCount = 0
    }

    private fun adopt(hit: SearchHit): SearchHit {
        currentTemplateId = hit.templateId
        candidateTemplateId = null
        candidateLeadCount = 0
        return hit
    }
}

/** Metadata describes the intended target, never a measured subject bounding box. */
internal fun v9SubjectTarget(position: String): Pair<Float, Float>? = when (position) {
    "center" -> 0.5f to 0.5f
    "left_third" -> 1f / 3f to 0.5f
    "right_third" -> 2f / 3f to 0.5f
    "upper_third" -> 0.5f to 1f / 3f
    "lower_third" -> 0.5f to 2f / 3f
    "upper_left", "upper_left_third" -> 1f / 3f to 1f / 3f
    "upper_right", "upper_right_third" -> 2f / 3f to 1f / 3f
    "lower_left", "lower_left_third" -> 1f / 3f to 2f / 3f
    "lower_right", "lower_right_third" -> 2f / 3f to 2f / 3f
    else -> null
}
