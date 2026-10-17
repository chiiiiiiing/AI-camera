package com.example.aicamera.pipeline

import com.example.aicamera.guidance.PhotographyTechniqueAdvice
import com.example.aicamera.guidance.SceneAnalysisObservation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.StructureObservation
import com.example.aicamera.guidance.TechniqueEvidenceKind
import com.example.aicamera.guidance.ObservedAnchorKind

/**
 * Craft coaching has no target coordinates or completion authority. Each suggestion states
 * an intention/condition: image detail, symmetry and brightness are not aesthetic defects.
 * Measured movement remains the responsibility of DirectPhotographyPolicy and the controller.
 */
internal class PhotographyTechniquePolicy {
    private var context: String? = null
    private var lastId = 0L
    private var lastSampleAt = 0L
    private var current: PhotographyTechniqueAdvice? = null
    private var adoptedAt = 0L
    private var pendingId: String? = null
    private var pendingSince = 0L

    fun reset() {
        context = null; lastId = 0; lastSampleAt = 0; current = null
        adoptedAt = 0; pendingId = null; pendingSince = 0
    }

    fun recommend(scene: SceneAnalysisObservation, structure: StructureObservation?,
        visual: TechniqueVisualEvidence?, nowMs: Long): PhotographyTechniqueAdvice? {
        if (!scene.isFresh(nowMs) || scene.viewportVersion < 0 || scene.family == SceneFamily.UNKNOWN) {
            reset(); return null
        }
        val face = structure?.takeIf { it.viewportVersion == scene.viewportVersion && it.singleFace(nowMs) != null }
        val item = scene.objectObservation?.takeIf { it.viewportVersion == scene.viewportVersion && it.isFresh(nowMs) }
        val key = "${scene.viewportVersion}:${scene.family}:${face!=null}:${if(scene.family==SceneFamily.PORTRAIT) face?.trackingId else item?.trackingId}:${item?.classId}"
        if (context != key || (lastSampleAt > 0 && scene.sampledAtMs - lastSampleAt > 1500)) {
            reset(); context = key
        }
        // Neither repeated camera results nor out-of-order samples stabilize/renew advice.
        if (scene.observationId < lastId || scene.sampledAtMs < lastSampleAt) return null
        if (scene.observationId == lastId || scene.sampledAtMs == lastSampleAt) {
            current = current?.takeIf { it.isValidFor(scene, structure, nowMs) }
            return current
        }
        lastId = scene.observationId; lastSampleAt = scene.sampledAtMs
        val subjectExpiresAt = if(scene.family==SceneFamily.PORTRAIT) face?.sampledAtMs?.plus(750L)
            else item?.sampledAtMs?.plus(1500L)
        val candidates = candidates(scene, face, item?.classId, visual, subjectExpiresAt)
        val existing = current?.let { old -> candidates.firstOrNull { it.id == old.id } }
        current = existing // A lost condition disappears immediately; never wait for a timer.
        val leader = candidates.firstOrNull() ?: run { pendingId = null; return null }
        if (existing?.id == leader.id) { pendingId = null; return existing }
        if (pendingId != leader.id) {
            pendingId = leader.id; pendingSince = scene.sampledAtMs
        } else if (scene.sampledAtMs - pendingSince >= 500 &&
            (existing == null || scene.sampledAtMs - adoptedAt >= 4000)) {
            current = leader; adoptedAt = scene.sampledAtMs; pendingId = null
        }
        return current
    }

    private fun candidates(scene: SceneAnalysisObservation, face: StructureObservation?,
        classId: Int?, visual: TechniqueVisualEvidence?, subjectExpiresAt: Long?): List<PhotographyTechniqueAdvice> = buildList {
        fun tip(id: String, title: String, suggestion: String, kind: TechniqueEvidenceKind,
            vararg reasons: String) {
            val sceneExpiresAt = scene.sampledAtMs + 1500L
            val expiresAt = if(kind != TechniqueEvidenceKind.SCENE_RECIPE && subjectExpiresAt != null)
                minOf(sceneExpiresAt, subjectExpiresAt) else sceneExpiresAt
            val anchor = when {
                kind == TechniqueEvidenceKind.SCENE_RECIPE -> ObservedAnchorKind.NONE
                face != null && scene.family == SceneFamily.PORTRAIT -> ObservedAnchorKind.FACE
                classId != null && scene.family in setOf(SceneFamily.FOOD, SceneFamily.STILL_LIFE) -> ObservedAnchorKind.OBJECT
                else -> ObservedAnchorKind.NONE
            }
            add(PhotographyTechniqueAdvice(id, title, suggestion, kind, scene.sampledAtMs,
                scene.viewportVersion, reasons.toList(), expiresAt, anchor,
                if(anchor==ObservedAnchorKind.FACE) face?.trackingId else scene.objectObservation?.trackingId,
                classId, face?.facingDirectionX.takeIf { id == "portrait_facing_room" }))
        }
        val isolated = scene.family in setOf(SceneFamily.PORTRAIT, SceneFamily.FOOD, SceneFamily.STILL_LIFE)
        val subjectObserved = if(scene.family==SceneFamily.PORTRAIT) face!=null else classId!=null
        val backgrounds = listOfNotNull(visual?.leftBackground, visual?.rightBackground)
        // The measured box is only a face/object region, never a full-person segmentation.
        if (isolated && subjectObserved && backgrounds.any { it.brightFraction >= .22f } &&
            visual?.subjectMeanLuma?.let { it in .12f.. .65f } == true) {
            tip("background_bright_attention", "让主体更突出", "如果侧边亮区抢眼，可以尝试避开它，同时保留主体。",
                TechniqueEvidenceKind.MEASURED_IMAGE, "side_bright_region", "brightness_not_a_defect")
        }
        if (isolated && subjectObserved && backgrounds.any { it.edgeDensity >= .08f }) {
            tip("background_detail_attention", "检查背景是否抢眼", "想突出主体时，可以试着减少旁边无关的细节。",
                TechniqueEvidenceKind.MEASURED_IMAGE, "side_detail_measured", "detail_not_a_defect", "observed_box_only")
        }
        if (scene.family == SceneFamily.FOOD) {
            when (classId) {
                53 -> tip("food_surface_angle", "展示表面配料", "想突出披萨的配料，可以试试俯拍。",
                    TechniqueEvidenceKind.DETECTED_SUBJECT, "detected_pizza", "angle_outcome_unobserved")
                48, 52, 55 -> tip("food_layers_angle", "展示食物层次", "如果侧面的层次是重点，可以试试从侧面拍。",
                    TechniqueEvidenceKind.DETECTED_SUBJECT, "detected_food_class", "layers_not_verified", "angle_outcome_unobserved")
            }
        }
        if (isolated && subjectObserved && visual?.subjectBackgroundLumaDifference?.let { it < .06f } == true) {
            tip("subject_separation", "突出主体轮廓", "想让轮廓更清楚，可以试试与主体明暗不同的背景。",
                TechniqueEvidenceKind.MEASURED_IMAGE, "local_luma_similar", "preserve_low_contrast_style")
        }
        when (scene.family) {
            SceneFamily.PORTRAIT -> {
                if (face?.facingDirectionX in listOf(-1f, 1f)) {
                    tip("portrait_facing_room", "给朝向留白", "侧脸人像可以在人物面朝的方向多留一些空间。",
                        TechniqueEvidenceKind.DETECTED_SUBJECT, "head_direction_not_eye_gaze")
                }
                tip("portrait_background", "检查人物周围", "可以检查头部周围，避开抢眼的线条或杂物。",
                    TechniqueEvidenceKind.SCENE_RECIPE, "head_background_not_segmented")
            }
            SceneFamily.FOOD, SceneFamily.STILL_LIFE -> tip("still_life_corners", "让画面更简洁",
                "可以检查画面四角，减少与主体无关的物件。", TechniqueEvidenceKind.SCENE_RECIPE, "corners_not_semantically_verified")
            SceneFamily.PLANT -> tip("plant_outline", "突出枝叶轮廓",
                "想突出枝叶形状时，可以试试更简洁的背景。", TechniqueEvidenceKind.SCENE_RECIPE, "full_plant_extent_unverified")
            SceneFamily.NATURAL_LANDSCAPE, SceneFamily.URBAN_LANDSCAPE -> {
                if (visual?.bilateralSymmetry?.let { it >= .88f } == true) {
                    tip("landscape_symmetry", "保留对称构图", "如果画面有对称结构，可以保留居中，不必强套三分法。",
                        TechniqueEvidenceKind.MEASURED_IMAGE, "intensity_symmetry_only", "reflection_not_detected")
                }
                if (visual?.upperTexture?.let { it < .012f } == true &&
                    visual.lowerTexture?.let { it > .04f } == true) {
                    tip("landscape_emphasis", "决定画面的重点", "如果天空平淡、地面更精彩，可以考虑多保留地面。",
                        TechniqueEvidenceKind.MEASURED_IMAGE, "upper_lower_texture_difference", "sky_and_horizon_not_detected")
                }
                if (scene.family == SceneFamily.URBAN_LANDSCAPE) {
                    tip("urban_leading_lines", "利用线条引导视线", "如果有道路或建筑线条，可以让它们引向想突出的景物。",
                        TechniqueEvidenceKind.SCENE_RECIPE, "line_direction_not_detected")
                } else {
                    tip("landscape_depth", "增加远近层次", "如果近处有合适景物，可以让它与远景形成层次。",
                        TechniqueEvidenceKind.SCENE_RECIPE, "depth_and_foreground_not_detected")
                }
            }
            SceneFamily.UNKNOWN -> Unit
        }
    }
}
