package com.example.aicamera.guidance

/**
 * 构图状态机 — 确定性 reducer，含帧滞回防抖。
 *
 * 从 com.genwow.camera.ui.camera.GuidanceStateMachine 移植，
 * 保留原始参数和逻辑，适配本项目的 GuidanceState 类型。
 */
class GuidanceStateMachine(
    val config: GuidanceStateMachineConfig = GuidanceStateMachineConfig(),
) {
    data class GuidanceStateMachineConfig(
        /** 接近阈值：进度 >= 此值进入 NEAR 相位（原 0.72 → 宽松 0.50） */
        val nearThreshold: Float = 0.50f,
        /** 对齐阈值：进度 >= 此值进入 ALIGNED 相位（原 0.92 → 宽松 0.72） */
        val alignedThreshold: Float = 0.72f,
        val framesToAlign: Int = 3,
        val framesToExitAligned: Int = 2,
    )

    /**
     * 输入一帧评估结果，返回新的 GuidanceState。
     */
    fun reduceFrame(
        current: GuidanceState,
        cue: GuidanceCue,
        progress: Float,
        subjectOutline: SubjectOutline? = null,
        anchors: GuidanceAnchors? = null,
    ): GuidanceState {
        val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val isStable = safeProgress >= config.alignedThreshold

        if (isStable) {
            val stableFrames = (current.stableFrameCount + 1).coerceAtMost(config.framesToAlign)
            val phase = if (
                current.phase == GuidancePhase.ALIGNED || stableFrames >= config.framesToAlign
            ) {
                GuidancePhase.ALIGNED
            } else {
                GuidancePhase.NEAR
            }
            val alignedCue = when {
                phase != GuidancePhase.ALIGNED -> cue
                cue == GuidanceCue.SUBJECT_TARGET ||
                    cue == GuidanceCue.SUBJECT_ALIGNED -> GuidanceCue.SUBJECT_ALIGNED
                else -> GuidanceCue.NONE
            }
            return GuidanceState(
                cue = alignedCue,
                phase = phase,
                progress = safeProgress,
                subjectOutline = subjectOutline,
                anchors = anchors,
                stableFrameCount = stableFrames,
            )
        }

        // 从 ALIGNED 退出的滞回
        if (current.phase == GuidancePhase.ALIGNED) {
            val unstableFrames = current.unstableFrameCount + 1
            if (unstableFrames < config.framesToExitAligned) {
                return current.copy(unstableFrameCount = unstableFrames)
            }
        }

        return GuidanceState(
            cue = cue,
            phase = phaseFor(safeProgress, cue),
            progress = safeProgress,
            subjectOutline = subjectOutline,
            anchors = anchors,
            unstableFrameCount = if (current.phase == GuidancePhase.ALIGNED) {
                config.framesToExitAligned
            } else {
                0
            },
        )
    }

    private fun phaseFor(progress: Float, cue: GuidanceCue): GuidancePhase = when {
        progress >= config.nearThreshold -> GuidancePhase.NEAR
        cue == GuidanceCue.NONE -> GuidancePhase.NEUTRAL
        else -> GuidancePhase.ADJUST
    }
}
