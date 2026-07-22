package com.example.aicamera.overlay.model

import android.graphics.PointF
import android.graphics.RectF

/**
 * 辅助线渲染引擎的数据模型。
 *
 * 与附录 A 的 JSON schema 对齐，同时兼容 FakeInference 的硬编码输出。
 * 队友最终确定输出 schema 后调整字段映射即可。
 */

// ── 顶层结构 ──────────────────────────────────────────────────

/** 模型输出 → 渲染引擎的输入 */
data class RenderInput(
    val sceneType: String = "unknown",
    val compositionStrategy: String = "rule_of_thirds",
    val confidence: Float = 0f,
    val guideLines: List<GuideLine> = emptyList(),
    /** 是否为前置摄像头（决定 X 轴是否镜像） */
    val isFrontCamera: Boolean = false,
    /** 帧旋转角度 */
    val rotationDegrees: Int = 0
)

/** 单条辅助线 */
data class GuideLine(
    val type: GuideLineType,
    val visible: Boolean = true,
    val params: Map<String, Any> = emptyMap()
)

// ── 辅助线类型枚举 ────────────────────────────────────────────

enum class GuideLineType(val key: String) {
    RULE_OF_THIRDS_GRID("rule_of_thirds_grid"),
    THIRDS_POINTS("thirds_points"),
    SUBJECT_BOX("subject_box"),
    TARGET_ZONE("target_zone"),
    HORIZON_LEVEL("horizon_level"),
    DIRECTION_ARROW("direction_arrow"),
    GUIDE_LINE_PATH("guide_line_path"),
    FRAMEWORK("framework"),
    UNKNOWN("unknown");

    companion object {
        fun fromKey(key: String): GuideLineType =
            entries.find { it.key == key } ?: UNKNOWN
    }
}

// ── 各类型的具体参数 ──────────────────────────────────────────

/** 三分线网格参数 */
data class GridParams(
    val verticalLines: FloatArray = floatArrayOf(0.333f, 0.667f),
    val horizontalLines: FloatArray = floatArrayOf(0.333f, 0.667f)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GridParams) return false
        return verticalLines.contentEquals(other.verticalLines) &&
            horizontalLines.contentEquals(other.horizontalLines)
    }
    override fun hashCode(): Int = verticalLines.contentHashCode() * 31 + horizontalLines.contentHashCode()
}

/** 主体定位框参数 */
data class SubjectBoxParams(
    val x: Float,      // 左上角 x（归一化 0.0–1.0）
    val y: Float,      // 左上角 y
    val width: Float,  // 宽度（归一化）
    val height: Float  // 高度（归一化）
) {
    fun toRectF(): RectF = RectF(x, y, x + width, y + height)
}

/** 目标区域参数 */
data class TargetZoneParams(
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

/** 水平仪参数 */
data class HorizonParams(
    val angleDegrees: Float  // 正值 = 顺时针偏转
)

/** 方向箭头参数 */
data class ArrowParams(
    val fromX: Float, val fromY: Float,
    val toX: Float, val toY: Float
)

/** 引导线路径参数 */
data class GuideLinePathParams(
    val points: FloatArray  // [x0, y0, x1, y1, ...]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GuideLinePathParams) return false
        return points.contentEquals(other.points)
    }
    override fun hashCode(): Int = points.contentHashCode()
}

/** 构图框架参数（对称轴 / 黄金螺旋 / 三角构图等） */
data class FrameworkParams(
    val frameworkType: String,  // "symmetry" | "golden_spiral" | "triangle"
    val points: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameworkParams) return false
        return frameworkType == other.frameworkType && points.contentEquals(other.points)
    }
    override fun hashCode(): Int = frameworkType.hashCode() * 31 + points.contentHashCode()
}

// ── 解析工具 ──────────────────────────────────────────────────

/** 从 Map<String, Any> 解析各类型参数 */
object GuideLineParser {

    fun parseGrid(params: Map<String, Any>): GridParams {
        val vLines = (params["vertical_lines"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toFloat()?.coerceIn(0f, 1f) }
            ?.toFloatArray()
        val hLines = (params["horizontal_lines"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toFloat()?.coerceIn(0f, 1f) }
            ?.toFloatArray()
        return GridParams(
            verticalLines = vLines ?: floatArrayOf(0.333f, 0.667f),
            horizontalLines = hLines ?: floatArrayOf(0.333f, 0.667f)
        )
    }

    fun parseSubjectBox(params: Map<String, Any>): SubjectBoxParams {
        val x = params.float("x", 0.25f)
        val y = params.float("y", 0.3f)
        val width = params.float("width", 0.5f)
        val height = params.float("height", 0.4f)
        return SubjectBoxParams(
            x = x,
            y = y,
            width = width.coerceIn(0f, 1f - x),
            height = height.coerceIn(0f, 1f - y)
        )
    }

    fun parseTargetZone(params: Map<String, Any>): TargetZoneParams {
        return TargetZoneParams(
            centerX = params.float("center_x", 0.333f),
            centerY = params.float("center_y", 0.333f),
            radius = params.float("radius", 0.12f).coerceIn(0f, 1f)
        )
    }

    fun parseHorizon(params: Map<String, Any>): HorizonParams {
        return HorizonParams(
            angleDegrees = params.float("angle_degrees", 0f)
        )
    }

    fun parseArrow(params: Map<String, Any>): ArrowParams {
        return ArrowParams(
            fromX = params.float("from_x", 0f),
            fromY = params.float("from_y", 0f),
            toX = params.float("to_x", 1f),
            toY = params.float("to_y", 1f)
        )
    }

    /** ★ 类型安全的数值提取 + 归一化 clamp */
    private fun Map<String, Any>.float(key: String, default: Float): Float {
        return (this[key] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: default
    }
}
