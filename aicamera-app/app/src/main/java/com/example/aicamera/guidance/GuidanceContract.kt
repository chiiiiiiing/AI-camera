package com.example.aicamera.guidance

/**
 * 构图引导数据模型 — 来自队友设计文档 + Figma 11 画板。
 *
 * 从 com.genwow.camera.ui.camera.CameraContract 移植，
 * 与现有 PipelineResult/FakeInference 桥接。
 */

/** 单次明确的手机移动指令；MOVE 不是主体在屏幕上应移动的方向。 */
enum class GuidanceCue {
    NONE,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    MOVE_UP_LEFT,
    MOVE_UP_RIGHT,
    MOVE_DOWN_LEFT,
    MOVE_DOWN_RIGHT,
    PITCH_UP,
    PITCH_DOWN,
    YAW_LEFT,
    YAW_RIGHT,
    ROLL_CLOCKWISE,
    ROLL_COUNTERCLOCKWISE,
    /** AI 提供逐帧主体轮廓，用户需匹配 */
    SUBJECT_TARGET,
    /** 最新 AI 轮廓在推荐构图中已稳定 */
    SUBJECT_ALIGNED,
    /** 设备已倒置（180°），不能通过水平校准，需翻转手机 */
    DEVICE_UPSIDE_DOWN,
}

/** 构图引导的视觉强度 */
enum class GuidancePhase {
    NEUTRAL,
    ADJUST,
    NEAR,
    WRONG_DIRECTION,
    ALIGNED,
}

/** 归一化点（AI 输出坐标系 0.0–1.0） */
data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

/** AI 返回的单条轮廓（开放或闭合） */
data class SubjectContour(
    val points: List<NormalizedPoint>,
    val closed: Boolean = false,
)

/** 逐帧主体几何 */
data class SubjectOutline(
    val contours: List<SubjectContour>,
    val confidence: Float = 1f,
)

/** AI 提供的锚点对（current=当前位置, target=推荐位置） */
data class GuidanceAnchors(
    val current: NormalizedPoint? = null,
    val target: NormalizedPoint? = null,
)

enum class AnalysisStatus {
    IDLE,
    ANALYZING,
    TRACKING,
    FAILED,
}

data class GuidanceState(
    val cue: GuidanceCue = GuidanceCue.NONE,
    val phase: GuidancePhase = GuidancePhase.NEUTRAL,
    val progress: Float = 0f,
    val subjectOutline: SubjectOutline? = null,
    val anchors: GuidanceAnchors? = null,
    val stableFrameCount: Int = 0,
    val unstableFrameCount: Int = 0,
    val trackingPaused: Boolean = false,
)
