package com.example.aicamera.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.example.aicamera.camera.CameraConfig
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
 *     → bitmapToNchw224 (复用已有 Bitmap 或从 buffer 重建)
 *     → V9EmbeddingRuntime.embed()
 *     → V9TemplateIndex.search()
 *     → V9TemplateIndex.rerank()
 *     → top1 metadata → FakeInferenceOutput (兼容格式)
 * ```
 *
 * ## 与 FakeInference 的关系
 * - FakeInference: 基于亮度值的简单启发式，不依赖模型
 * - V9Inference: 真实 ONNX 模型 embedding → 模板检索
 * - 通过 IInference 接口热替换，FramePipeline 无需改动
 */
class V9Inference(
    private val runtime: V9EmbeddingRuntime,
    private val index: V9TemplateIndex
) : IInference, Closeable {

    override val typeName: String = "V9-ONNX"

    /** 上一帧选中的模板（用于稳定策略） */
    private var previousTemplateId: String? = null

    /** 当前模板连续选中次数 */
    private var stableCount: Int = 0

    /** 低 margin 阈值（top1/top2 margin < 0.01 时不切换） */
    private val lowMarginThreshold: Float = 0.01f

    /** 稳定切换所需连续帧数 */
    private val stableSwitchFrames: Int = 2

    // ── IInference 实现 ───────────────────────────────────────

    override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
        // Step 1: 获取 Bitmap — 优先使用 PreprocessedFrame 中保留的，否则从 buffer 重建
        val sourceBitmap = frame.originalBitmap
        val embedding = if (sourceBitmap != null) {
            // 快速路径：直接用保留的 Bitmap 做 NCHW 预处理
            val nchw = V9EmbeddingRuntime.bitmapToNchw224(sourceBitmap)
            runtime.embed(nchw)
        } else {
            // 降级路径：从 buffer 重建 Bitmap
            val reconstructed = bufferToBitmap(buffer, frame.width, frame.height)
            val nchw = V9EmbeddingRuntime.bitmapToNchw224(reconstructed)
            reconstructed.recycle()
            runtime.embed(nchw)
        }

        // Step 3: 检索 + 重排
        val candidates = index.search(embedding, topK = 8)
        if (candidates.isEmpty()) return createEmptyResult()

        val reranked = index.rerank(candidates)

        // Step 4: 稳定策略 — 低 margin 不切换
        val top1 = reranked.first()
        val top2 = reranked.getOrNull(1)
        val margin = if (top2 != null) top1.rerankScore - top2.rerankScore else 1f

        val selectedHit = if (margin < lowMarginThreshold && previousTemplateId != null) {
            // margin 太低，保持上一帧模板（如果还在 top5 中）
            val prevInTop5 = reranked.find { it.templateId == previousTemplateId }
            if (prevInTop5 != null) {
                stableCount = if (prevInTop5.templateId == previousTemplateId) stableCount + 1 else 1
                prevInTop5
            } else {
                stableCount = if (top1.templateId == previousTemplateId) stableCount + 1 else 1
                top1
            }
        } else {
            stableCount = if (top1.templateId == previousTemplateId) stableCount + 1 else 1
            top1
        }

        // 需要连续 stableSwitchFrames 次领先才真正切换
        if (previousTemplateId != null && selectedHit.templateId != previousTemplateId) {
            if (stableCount < stableSwitchFrames) {
                // 还未稳定，继续用上一个模板
                val prevMeta = index.getMetadata(previousTemplateId ?: "")
                val fallback = prevMeta ?: selectedHit.metadata
                if (fallback != null) return metaToFakeOutput(fallback, embedding, selectedHit.rerankScore)
            }
        }

        previousTemplateId = selectedHit.templateId
        val meta = selectedHit.metadata

        return if (meta != null) {
            metaToFakeOutput(meta, embedding, selectedHit.rerankScore)
        } else {
            createEmptyResult()
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
     * - horizon_position → 水平参考线
     * - negative_space=high → 留白提示
     */
    private fun metaToFakeOutput(
        meta: TemplateMeta,
        embedding: FloatArray,
        score: Float
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
            "none" -> FakeGuideLine("target_zone", true,
                mapOf("center_x" to 0.333f, "center_y" to 0.333f, "radius" to 0.12f))
            else -> FakeGuideLine("target_zone", true,
                mapOf("center_x" to 0.333f, "center_y" to 0.333f, "radius" to 0.12f))
        }
        guideLines.add(targetZone)

        // 3. 水平仪
        guideLines.add(
            FakeGuideLine("horizon_level", true,
                mapOf("angle_degrees" to 0f))
        )

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

        // 6. 水平参考线
        when (meta.horizonPosition) {
            "upper_third" -> {
                guideLines.add(FakeGuideLine("horizon_level", true,
                    mapOf("angle_degrees" to 0f, "reference_y" to 0.333f)))
            }
            "lower_third" -> {
                guideLines.add(FakeGuideLine("horizon_level", true,
                    mapOf("angle_degrees" to 0f, "reference_y" to 0.667f)))
            }
            "middle" -> {
                guideLines.add(FakeGuideLine("horizon_level", true,
                    mapOf("angle_degrees" to 0f, "reference_y" to 0.5f)))
            }
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
        )
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

    private fun createEmptyResult(): FakeInferenceOutput {
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
        )
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private fun bufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var i = 0
        while (buffer.remaining() >= 3 && i < pixels.size) {
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
        runtime.close()
        Log.i(CameraConfig.TAG, "[V9Inference] 已释放")
    }
}
