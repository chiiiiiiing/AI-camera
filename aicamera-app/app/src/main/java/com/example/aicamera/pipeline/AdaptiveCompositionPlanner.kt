package com.example.aicamera.pipeline

import com.example.aicamera.guidance.*
import kotlin.math.abs

enum class RecommendationRoute { OBSERVATION_ONLY, PHOTOGRAPHY_RULE, REFERENCE_STRUCTURE }

internal data class SelectedComposition(
    val portrait: CompositionTarget? = null,
    val objectTarget: ObjectCompositionTarget? = null,
    val route: RecommendationRoute = RecommendationRoute.OBSERVATION_ONLY,
    val reasons: List<String> = emptyList()
)

/** Locks a real objective, not a template ID requirement or a target following every frame. */
internal class AdaptiveCompositionPlanner {
    private var key: String? = null
    private var locked: SelectedComposition? = null
    private var blocked = false
    fun reset() { key=null; locked=null; blocked=false }
    fun replan() { locked=null; blocked=true }

    fun select(scene: SceneAnalysisObservation, structure: StructureObservation?,
        referenceFace: CompositionTarget?, referenceObject: ObjectCompositionTarget?, nowMs: Long): SelectedComposition {
        if (!scene.isFresh(nowMs)) { reset(); return SelectedComposition(reasons=listOf("stale_scene_evidence")) }
        val face = structure?.takeIf { it.viewportVersion==scene.viewportVersion }?.singleFace(nowMs)
        val obj = scene.objectObservation?.takeIf { it.isFresh(nowMs) && it.viewportVersion==scene.viewportVersion }
        val identity = when(scene.family) {
            SceneFamily.PORTRAIT -> if(face!=null) "face:${structure?.trackingId}" else null
            SceneFamily.FOOD, SceneFamily.STILL_LIFE -> obj?.let { "object:${it.trackingId}:${it.classId}" }
            else -> null
        }
        if (identity==null) {
            reset()
            return SelectedComposition(reasons=scene.guidanceLimitations)
        }
        val nextKey="${scene.viewportVersion}:${scene.family}:$identity"
        if (key!=nextKey) { reset(); key=nextKey }
        if(blocked) return SelectedComposition(reasons=listOf("rule_execution_paused"))
        val plan = if(scene.family==SceneFamily.PORTRAIT)
            DirectPhotographyPolicy.portrait(structure,nowMs,scene.viewportVersion)
        else DirectPhotographyPolicy.objectSubject(obj,scene.family,nowMs,scene.viewportVersion)
        if(plan==null) { locked=null; return SelectedComposition(reasons=listOf("no_verified_subject_rule")) }
        locked?.let { current ->
            val valid = current.portrait?.let { target -> face!=null && target.accepts(face,structure?.faceYawDegrees) } == true ||
                current.objectTarget?.let { target -> obj!=null && target.accepts(obj) } == true
            val kept = (current.portrait?.source ?: current.objectTarget?.source) in
                setOf("photography_rule_keep","current_compatible","current_compatible_object")
            // A new edge defect invalidates a KEEP lease even when still inside its circle.
            val newDefect = kept && plan!=null && plan.minimumMarginBefore<.05f
            if(valid && !newDefect) return current
            locked=null
            if(!newDefect) return SelectedComposition(reasons=listOf("composition_target_invalidated"))
        }
        if (plan!=null) {
            if(plan.selected==null || (plan.portraitTarget==null && plan.objectTarget==null))
                return SelectedComposition(reasons=plan.reasons)
            // Reference agreement can endorse keeping this framing. Mere similarity cannot
            // force a healthy off-centre composition into thirds or a different position.
            if(plan.selected.intent==DirectFramingIntent.KEEP) {
                if(face!=null && referenceFace!=null && referenceFace.viewportVersion==scene.viewportVersion &&
                    referenceFace.subjectTrackingId==structure?.trackingId &&
                    referenceFace.accepts(face,structure?.faceYawDegrees) &&
                    abs(referenceFace.center.x-face.centerX)<=.04f && abs(referenceFace.center.y-face.centerY)<=.04f)
                    return SelectedComposition(portrait=referenceFace.copy(center=NormalizedPoint(face.centerX,face.centerY),
                        source="current_compatible"),route=RecommendationRoute.REFERENCE_STRUCTURE,
                        reasons=listOf("reference_agrees_with_current_framing")+plan.reasons).also { locked=it }
                if(obj!=null && referenceObject!=null && referenceObject.accepts(obj) &&
                    abs(referenceObject.center.x-obj.box.centerX)<=.04f && abs(referenceObject.center.y-obj.box.centerY)<=.04f)
                    return SelectedComposition(objectTarget=referenceObject.copy(center=NormalizedPoint(obj.box.centerX,obj.box.centerY),
                        source="current_compatible_object"),route=RecommendationRoute.REFERENCE_STRUCTURE,
                        reasons=listOf("reference_agrees_with_current_framing")+plan.reasons).also { locked=it }
            }
            return SelectedComposition(plan.portraitTarget,plan.objectTarget,RecommendationRoute.PHOTOGRAPHY_RULE,
                plan.reasons).also { locked=it }
        }
        return SelectedComposition(reasons=listOf("no_verified_subject_rule"))
    }
}
