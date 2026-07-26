package com.example.aicamera.pipeline

import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.guidance.GuidanceCue
import kotlin.math.abs
import kotlin.random.Random

/**
 * 假推理器 — 模型没到位时验证管线的替代方案。
 *
 * ## 功能
 * - 基于亮度模拟场景识别（明亮/正常/暗光）
 * - 输出模拟的辅助线参数（三分线、目标区、水平仪、主体框），验证下游渲染管线
 *
 * ## 使用
 * ```kotlin
 * val fake = FakeInference()
 * val result = fake.predictFromLuminance(luminance255, rotation)
 * ```
 *
 * 等队友给模型后：替换为真实的 LiteRT 推理，管线其余代码不动。
 */
class FakeInference {

    private val random = Random(System.currentTimeMillis())

    /** 估计的推理耗时（ms），仅用于性能面板展示，不实际阻塞线程 */
    var estimatedInferenceMs: Long = 0
        private set

    /** ★ 稳定的水平仪角度（避免每帧随机跳变） */
    private var stableAngle: Float = 0f

    // ── 公开 API ──────────────────────────────────────────────

    /**
     * ★ 直接从亮度值生成假推理结果（无需 Bitmap）。
     * 由 [FakeInferenceAdapter] 从 FloatBuffer 计算亮度后调用。
     */
    fun predictFromLuminance(luminance: Float, rotation: Int = 0): FakeInferenceOutput {
        // 1. 根据亮度选场景（稳定判断，无随机）
        val scene = when {
            luminance > 180f -> SceneType.BRIGHT
            luminance > 80f -> SceneType.NORMAL
            else -> SceneType.DARK
        }

        // 2. 根据场景选构图策略
        val strategy = when (scene) {
            SceneType.BRIGHT -> CompositionStrategy.RULE_OF_THIRDS
            SceneType.NORMAL -> CompositionStrategy.RULE_OF_THIRDS
            SceneType.DARK -> CompositionStrategy.CENTER
        }

        // 3. 生成辅助线
        val guideLines = buildGuideLines(scene, strategy)

        estimatedInferenceMs = (50 + random.nextInt(30)).toLong()

        // ★ 稳定进度和引导：基于场景类型给出固定值，不随机跳变
        val progress = when (scene) {
            SceneType.BRIGHT -> 0.80f
            SceneType.NORMAL -> 0.65f
            SceneType.DARK -> 0.45f
        }

        // ★ 关键修复：不再随机生成左右移动指令
        // GuidanceCue NONE = 告诉 ComposeGuidanceOverlay "当前构图尚可，无需大调整"
        // Overlay 只会显示静态辅助线（三分线+目标区+水平仪），不会显示闪烁的移动箭头
        val cue = GuidanceCue.NONE

        return FakeInferenceOutput(
            sceneType = scene,
            compositionStrategy = strategy,
            confidence = 0.85f,
            luminance = luminance,
            guideLines = guideLines,
            guidanceCue = cue,
            compositionProgress = progress
        )
    }

    // ── 辅助线生成 ────────────────────────────────────────────

    private fun buildGuideLines(
        scene: SceneType,
        strategy: CompositionStrategy
    ): List<FakeGuideLine> {
        val lines = mutableListOf<FakeGuideLine>()

        // 三分线（始终绘制）
        lines.add(
            FakeGuideLine(
                type = "rule_of_thirds_grid",
                visible = true,
                params = mapOf(
                    "vertical_lines" to listOf(0.333f, 0.667f),
                    "horizontal_lines" to listOf(0.333f, 0.667f)
                )
            )
        )

        // 目标区域
        lines.add(
            FakeGuideLine(
                type = "target_zone",
                visible = true,
                params = mapOf(
                    "center_x" to 0.333f,
                    "center_y" to 0.333f,
                    "radius" to 0.12f
                )
            )
        )

        // ★ 水平仪：平滑变化而非每帧随机跳
        val jitter = (random.nextFloat() - 0.5f) * 0.3f
        stableAngle = (stableAngle + jitter).coerceIn(-2f, 2f)
        lines.add(
            FakeGuideLine(
                type = "horizon_level",
                visible = true,
                params = mapOf("angle_degrees" to stableAngle)
            )
        )

        // 暗光场景：额外绘制中央主体框
        if (scene == SceneType.DARK) {
            lines.add(
                FakeGuideLine(
                    type = "subject_box",
                    visible = true,
                    params = mapOf(
                        "x" to 0.25f, "y" to 0.3f,
                        "width" to 0.5f, "height" to 0.4f
                    )
                )
            )
        }

        return lines
    }
}

// ── 数据类型 ──────────────────────────────────────────────────

enum class SceneType(val label: String) {
    BRIGHT("明亮"),
    NORMAL("正常"),
    DARK("暗光")
}

enum class CompositionStrategy(val label: String) {
    RULE_OF_THIRDS("三分法"),
    CENTER("中心构图"),
    SYMMETRY("对称构图"),
    LEADING_LINE("引导线"),
    FRAMING("框架构图")
}

data class FakeInferenceOutput(
    val sceneType: SceneType,
    val compositionStrategy: CompositionStrategy,
    val confidence: Float,
    val luminance: Float,
    val guideLines: List<FakeGuideLine>,
    /** ★ 构图引导指令（与队友 GuidanceCue 对齐） */
    val guidanceCue: GuidanceCue = GuidanceCue.NONE,
    /** 构图匹配度 (0.0–1.0)，用于 GuidanceStateMachine */
    val compositionProgress: Float = 0.5f
)

data class FakeGuideLine(
    val type: String,
    val visible: Boolean,
    val params: Map<String, Any>
)
