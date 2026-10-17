package com.example.aicamera.guidance

import com.example.aicamera.overlay.model.GuideLine
import com.example.aicamera.overlay.model.GuideLineType

/** No detector, scoring or Ready logic lives in the UI. Missing/expired decisions stop motion. */
data class GuidancePresentation(
    val state: CompositionSessionController.State,
    val cue: GuidanceCue = GuidanceCue.NONE,
    val phase: GuidancePhase = GuidancePhase.NEUTRAL,
    val paused: Boolean = false,
    val verifying: Boolean = false,
    val hint: String? = null,
    val evidence: GuidanceDisplayEvidence? = null,
    val guideLines: List<GuideLine> = emptyList()
) {
    val ready get() = state is CompositionSessionController.State.Ready

    companion object {
        fun from(state: CompositionSessionController.State, decision: GuidanceDecision, nowMs: Long): GuidancePresentation {
            if (state !is CompositionSessionController.State.Adjusting &&
                state !is CompositionSessionController.State.Verifying && state !is CompositionSessionController.State.Ready)
                return GuidancePresentation(state)
            val stop = decision.feedback in setOf(GuidanceFeedback.INSUFFICIENT_EVIDENCE,
                GuidanceFeedback.WORSENING, GuidanceFeedback.NO_PROGRESS, GuidanceFeedback.SCENE_CHANGED)
            val ev = decision.displayEvidence?.takeIf { it.isFresh(nowMs) }
            val synchronized = when (state) {
                is CompositionSessionController.State.Adjusting -> state.action == decision.action &&
                    decision.feedback in setOf(GuidanceFeedback.ADJUSTING, GuidanceFeedback.IMPROVING)
                CompositionSessionController.State.Verifying -> decision.feedback == GuidanceFeedback.VERIFYING
                CompositionSessionController.State.Ready -> decision.feedback == GuidanceFeedback.READY &&
                    decision.action == CompositionSessionController.GuidanceAction.Ready
                else -> false
            }
            if (stop || ev == null || !synchronized) return GuidancePresentation(
                CompositionSessionController.State.Adjusting(CompositionSessionController.GuidanceAction.HoldStill),
                paused = true, hint = if (stop) feedbackHint(decision) else "先保持，正在确认可用的构图依据")
            val ready = state is CompositionSessionController.State.Ready
            val verifying = state is CompositionSessionController.State.Verifying
            val lines = buildList {
                ev.subjectBox?.let { b ->
                    if (listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() && it in 0f..1f } && b.width>0 && b.height>0)
                        add(GuideLine(GuideLineType.SUBJECT_BOX, params=mapOf("x" to b.left,"y" to b.top,
                            "width" to b.width,"height" to b.height,"real" to true,"aligned" to ready,
                            "anchor_kind" to ev.anchorKind.name.lowercase())))
                }
                ev.target?.let { p -> if(ev.targetRadius.isFinite() && ev.targetRadius>0 && ev.targetRadius<=1)
                    add(GuideLine(GuideLineType.TARGET_ZONE, params=mapOf("center_x" to p.x,"center_y" to p.y,
                        "radius" to ev.targetRadius,"anchor_kind" to ev.anchorKind.name.lowercase()))) }
            }
            return GuidancePresentation(state,
                if (verifying) GuidanceCue.NONE else decision.action.toGuidanceCue(),
                if (ready) GuidancePhase.ALIGNED else if (verifying) GuidancePhase.NEAR else GuidancePhase.ADJUST,
                verifying = verifying, hint = feedbackHint(decision), evidence=ev, guideLines=lines)
        }

        private fun feedbackHint(d: GuidanceDecision): String? = when(d.feedback) {
            GuidanceFeedback.WORSENING -> when {
                "observed_subject_margin_regressed" in d.reasons -> "先保持，主体太靠近画面边缘"
                "subject_sharpness_regressed" in d.reasons -> "先保持，主体清晰度下降"
                "subject_highlights_regressed" in d.reasons -> "先保持，主体亮部细节减少"
                else -> "先保持，刚才的调整没有改善构图"
            }
            GuidanceFeedback.NO_PROGRESS -> "先保持，暂未找到更有效的调整"
            GuidanceFeedback.SCENE_CHANGED -> "画面发生变化，先保持重新确认"
            GuidanceFeedback.VERIFYING -> "位置合适，请保持稳定"
            GuidanceFeedback.INSUFFICIENT_EVIDENCE -> when {
                "rule_execution_paused" in d.reasons -> "先保持，刚才的调整尚未得到有效验证"
                "subject_margin_requires_unverified_scale_change" in d.reasons -> "先保持，当前主体范围不适合小幅取景调整"
                "single_face_required" in d.reasons -> "先保持，正在确认单人拍摄主体"
                "no_verified_portrait_target" in d.reasons -> "先保持，正在确认人像的可用构图依据"
                "no_compatible_portrait_reference" in d.reasons -> "先保持，暂未找到适合当前人像的构图目标"
                "object_unstable_or_ambiguous" in d.reasons -> "先保持，正在确认完整、稳定的主体"
                "no_compatible_object_reference" in d.reasons -> "先保持，暂未找到适合当前物体的构图目标"
                "no_verified_landscape_target" in d.reasons -> "可保留当前取景，暂未找到可靠的风景调整依据"
                "unverified_complete_plant" in d.reasons -> "可保留当前取景，暂无法确认植物的完整范围"
                else -> "先保持，正在确认可用的构图依据"
            }
            GuidanceFeedback.ADJUSTING, GuidanceFeedback.IMPROVING ->
                if("head_facing_space" in d.reasons) "朝人物面朝的方向多留一些空间" else null
            else -> null
        }
    }
}
