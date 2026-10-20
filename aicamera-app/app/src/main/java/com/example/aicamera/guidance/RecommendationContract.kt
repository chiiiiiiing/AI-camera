package com.example.aicamera.guidance

import com.example.aicamera.guidance.geometry.NormalizedBox

/** All times use Android elapsedRealtime; IDs identify samples, never display frames. */
data class StructureObservation(
    val observationId: Long,
    val sampledAtMs: Long,
    val viewportVersion: Long,
    val faceCount: Int,
    val face: NormalizedBox?,
    val faceYawDegrees: Float? = null,
    val trackingId: Int? = null,
    val valid: Boolean = true,
    val aspectRatio: Float = 1f
) {
    fun singleFace(nowMs: Long): NormalizedBox? = face?.takeIf {
        valid && faceCount == 1 && observationId > 0 && nowMs >= sampledAtMs &&
            nowMs - sampledAtMs <= 750 && it.left > .01f && it.top > .01f &&
            it.right < .99f && it.bottom < .99f && it.width >= .04f && it.height >= .04f
    }
}

data class CompositionTarget(
    val id: String,
    val center: NormalizedPoint,
    val tolerance: Float = .08f,
    val viewportVersion: Long,
    val source: String,
    val preserveSubject: Boolean = true,
    val subjectTrackingId: Int? = null,
    val referenceFaceHeight: Float? = null,
    val referenceFaceAspectRatio: Float? = null,
    val referenceYawDegrees: Float? = null
) {
    fun accepts(face: NormalizedBox, yaw: Float? = null): Boolean {
        val dx = center.x-face.centerX; val dy = center.y-face.centerY
        if (kotlin.math.abs(dx) > .28f || kotlin.math.abs(dy) > .28f ||
            face.left+dx < .03f || face.right+dx > .97f || face.top+dy < .03f || face.bottom+dy > .97f) return false
        if (referenceFaceHeight?.let { face.height/it !in .65f..1.55f } == true) return false
        if (referenceFaceAspectRatio?.let { kotlin.math.abs(kotlin.math.ln((face.width/face.height/it).toDouble())) > .45 } == true) return false
        return referenceYawDegrees == null || yaw == null || kotlin.math.abs(referenceYawDegrees-yaw) <= 25f
    }
}

enum class GuidanceFeedback { INSUFFICIENT_EVIDENCE, ADJUSTING, IMPROVING, VERIFYING, READY, WORSENING, NO_PROGRESS, SCENE_CHANGED }

/** Single decision emitted by the session controller; retrieval cosine is never a probability. */
data class GuidanceDecision(
    val action: CompositionSessionController.GuidanceAction,
    val targetId: String?,
    val error: Float?,
    val feedback: GuidanceFeedback,
    val observationId: Long,
    val replanRequested: Boolean = false,
    val reasons: List<String> = emptyList()
)

enum class SceneFamily { UNKNOWN, PORTRAIT, FOOD, STILL_LIFE, PLANT, NATURAL_LANDSCAPE, URBAN_LANDSCAPE }
enum class ObservedAnchorKind { FACE, OBJECT, NONE }

/** Detection geometry is distinct from a face and never certifies unseen subject parts. */
data class ObjectObservation(
    val observationId: Long,
    val sampledAtMs: Long,
    val viewportVersion: Long,
    val trackingId: Int,
    val classId: Int,
    val box: NormalizedBox,
    val confidence: Float,
    val stableSamples: Int,
    val aspectRatio: Float,
    /** Live tracking can use .4 with repeated samples; reviewed-reference defaults stay .5. */
    val minimumConfidence: Float = .5f
) {
    fun isFresh(nowMs: Long): Boolean = observationId > 0 && nowMs >= sampledAtMs &&
        nowMs-sampledAtMs <= 1500 && stableSamples >= 2 && confidence.isFinite() &&
        minimumConfidence in .4f.. .5f && confidence >= minimumConfidence &&
        box.left >= .02f && box.top >= .02f && box.right <= .98f && box.bottom <= .98f &&
        box.width > 0 && box.height > 0 && aspectRatio.isFinite() && aspectRatio > 0
}

data class ObjectCompositionTarget(
    val id: String,
    val center: NormalizedPoint,
    val viewportVersion: Long,
    val trackingId: Int,
    val classId: Int,
    val referenceBox: NormalizedBox,
    val tolerance: Float = .06f,
    val source: String = "reference_object"
) {
    fun accepts(observation: ObjectObservation): Boolean {
        if (observation.viewportVersion != viewportVersion || observation.trackingId != trackingId || observation.classId != classId) return false
        val b = observation.box
        val dx = center.x-b.centerX; val dy = center.y-b.centerY
        return kotlin.math.abs(dx) <= .20f && kotlin.math.abs(dy) <= .20f &&
            b.left+dx >= .03f && b.top+dy >= .03f && b.right+dx <= .97f && b.bottom+dy <= .97f &&
            b.height/referenceBox.height in .65f..1.55f && b.width/referenceBox.width in .65f..1.55f
    }
}

/** Measured image layout supports reference compatibility, never a claimed horizon or beauty score. */
data class SceneLayout(
    val lumaGrid: List<Float>, val edgeGrid: List<Float>,
    val meanLuma: Float, val darkFraction: Float, val lightFraction: Float
)

data class SceneAnalysisObservation(
    val observationId: Long,
    val sampledAtMs: Long,
    val viewportVersion: Long,
    val family: SceneFamily,
    val layout: SceneLayout,
    val objectObservation: ObjectObservation? = null,
    val detectorAvailable: Boolean = false
) {
    fun isFresh(nowMs: Long): Boolean = observationId > 0 && nowMs >= sampledAtMs && nowMs-sampledAtMs <= 1500
}

/** Image evidence is sampled independently of the face/IMU; a rendered cache is not a new result. */
data class ExecutionQualityEvidence(
    val observationId: Long, val sampledAtMs: Long, val viewportVersion: Long,
    val family: SceneFamily, val subjectTrackingId: Int?, val subjectBox: NormalizedBox?,
    val meanLuma: Float, val lumaStd: Float, val subjectSharpness: Float?,
    val subjectHighlights: Float?, val backgroundEdges: Float,
    val preferenceScore: Float? = null, val preferenceModelId: String? = null,
    /** Estimated image translation, never a physical-camera displacement. */
    val backgroundDx: Float? = null, val backgroundDy: Float? = null,
    val comparedObservationId: Long? = null,
    /** Detector IDs can advance more slowly than image evidence IDs. */
    val subjectObservationId: Long = observationId,
    val applicableRules: List<String> = emptyList()
) {
    fun isFresh(now: Long): Boolean = observationId > 0 && now >= sampledAtMs && now-sampledAtMs <= 1500 &&
        listOf(meanLuma,lumaStd,backgroundEdges).all { it.isFinite() && it in 0f..1f } &&
        listOfNotNull(subjectSharpness,subjectHighlights,preferenceScore,backgroundDx,backgroundDy).all { it.isFinite() } &&
        (subjectHighlights==null || subjectHighlights in 0f..1f)
}

enum class ExecutionComparison { INITIAL, COMPARABLE, CACHED, CONTEXT_CHANGED, QUALITY_REGRESSED, UNATTRIBUTED_MOTION, PHOTOMETRIC_SETTLING }

/** Relative technical checks are not an absolute aesthetic score; low/high key images are valid. */
class ExecutionQualityGuard {
    private var previous: ExecutionQualityEvidence? = null
    private var baseline: ExecutionQualityEvidence? = null
    private var preferenceBaseline: ExecutionQualityEvidence? = null
    private var motionAnchor: ExecutionQualityEvidence? = null
    private var unverifiedDx=0f
    private var unverifiedDy=0f
    var lastReasons:List<String> = emptyList()
        private set
    fun reset() { previous=null; baseline=null;preferenceBaseline=null;motionAnchor=null;unverifiedDx=0f;unverifiedDy=0f;lastReasons=emptyList() }
    fun compare(current: ExecutionQualityEvidence): ExecutionComparison {
        lastReasons=emptyList()
        val last=previous
        if(last==null) { previous=current; baseline=current;preferenceBaseline=current.takeIf{it.preferenceScore!=null};motionAnchor=current;return ExecutionComparison.INITIAL }
        if(current.observationId <= last.observationId || current.sampledAtMs <= last.sampledAtMs) return ExecutionComparison.CACHED
        previous=current
        val anchor=motionAnchor ?: last
        val a=anchor.subjectBox; val b=current.subjectBox
        val newGeometry=current.subjectObservationId!=anchor.subjectObservationId
        val dx=if(newGeometry && a!=null && b!=null)b.centerX-a.centerX else 0f
        val dy=if(newGeometry && a!=null && b!=null)b.centerY-a.centerY else 0f
        val hasMotion=current.comparedObservationId==anchor.observationId && current.backgroundDx!=null && current.backgroundDy!=null
        if(newGeometry) {
            unverifiedDx+=dx-(if(hasMotion)current.backgroundDx!! else 0f)
            unverifiedDy+=dy-(if(hasMotion)current.backgroundDy!! else 0f)
            motionAnchor=current
        }
        // Accumulate signed residuals: bounded detector jitter cancels, persistent small
        // subject drift does not. A per-step threshold would miss a slow autonomous subject.
        // ★ 手持轻微抖动不该被判成"主体自己在动"：旧阈值 0.012 过于灵敏，
        //   正常呼吸/手抖就会不断触发。放宽到 0.035。
        val unverifiedMotion=kotlin.math.hypot(unverifiedDx,unverifiedDy)>.035f
        // 跟踪 ID 变化只说明检测器重新分配了 ID（人稍微一动就会变），
        // 不等于换人/换场景。重置对比基准即可，但不要向用户报"画面发生变化"。
        if(current.subjectTrackingId!=last.subjectTrackingId) {
            baseline=current
            preferenceBaseline=current.takeIf{it.preferenceScore!=null}
            motionAnchor=current;unverifiedDx=0f;unverifiedDy=0f
            lastReasons=listOf("subject_tracking_reassigned")
            // 静默跳过这一帧：既不清空引导进度，也不给用户弹"画面发生变化"。
            return ExecutionComparison.CACHED
        }
        // 主体"出现/消失"的单帧抖动不算换场景：检测器 4Hz、人一低头就会丢一帧，
        // 旧逻辑会因此报"画面发生变化"并打断引导。这里只悄悄重置基准。
        if((a==null)!=(b==null)) {
            baseline=current
            preferenceBaseline=current.takeIf{it.preferenceScore!=null}
            motionAnchor=current;unverifiedDx=0f;unverifiedDy=0f
            lastReasons=listOf("subject_presence_changed")
            return ExecutionComparison.CACHED
        }
        // 场景分类（family）抖动、手持靠近/远离导致的主体框缩放，
        // 都不等于"换了场景"。它们只重置技术基准，不向用户报"画面发生变化"。
        if(current.family!=last.family ||
            (a!=null && b!=null && (b.width/a.width !in .65f..1.55f || b.height/a.height !in .65f..1.55f))) {
            baseline=current
            preferenceBaseline=current.takeIf{it.preferenceScore!=null}
            motionAnchor=current;unverifiedDx=0f;unverifiedDy=0f
            lastReasons=listOf("soft_context_reset")
            return ExecutionComparison.CACHED
        }
        // 只有真正的视口切换或长时间断档才视为换了场景。
        if(current.viewportVersion!=last.viewportVersion ||
            current.sampledAtMs-last.sampledAtMs>2000) {
            baseline=current
            preferenceBaseline=current.takeIf{it.preferenceScore!=null}
            motionAnchor=current;unverifiedDx=0f;unverifiedDy=0f
            lastReasons=listOf("comparison_context_changed")
            return ExecutionComparison.CONTEXT_CHANGED
        }
        // ★ 曝光/对比度容差同样放宽：手持时自动曝光持续微调，
        //   旧阈值（0.08 / 0.07）几乎每帧都触发，把引导反复打断。
        if(kotlin.math.abs(current.meanLuma-last.meanLuma)>.16f || kotlin.math.abs(current.lumaStd-last.lumaStd)>.14f) {
            // AE settling is not evidence of successful camera execution. Hold the transient
            // frame, but retain the pre-action technical baseline: persistent lost highlights
            // or blur must not become the new acceptable state merely because AE stabilized.
            // Distinct from CONTEXT_CHANGED: a brief exposure shift is not a new scene, so the
            // caller must not reset pose/position guidance progress or surface "scene changed".
            preferenceBaseline=current.takeIf{it.preferenceScore!=null}
            motionAnchor=current;unverifiedDx=0f;unverifiedDy=0f
            lastReasons=listOf("photometric_context_changed")
            return ExecutionComparison.PHOTOMETRIC_SETTLING
        }
        val start=baseline ?: last
        val sharpWorse=start.subjectSharpness?.let { old -> current.subjectSharpness?.let { old>.04f && it<old*.70f } } == true
        val clipped=start.subjectHighlights?.let { old -> current.subjectHighlights?.let { it>old+.06f && it>old*1.8f } } == true
        // Detailed cities, trees and textured tablecloths are not intrinsically bad backgrounds.
        // Background edge growth is only a soft finding in an isolated-object scene.
        val clutter=current.family in setOf(SceneFamily.FOOD,SceneFamily.STILL_LIFE) && current.subjectBox!=null &&
            current.backgroundEdges>start.backgroundEdges*1.4f && current.backgroundEdges>start.backgroundEdges+.015f
        fun margin(box:NormalizedBox)=minOf(box.left,box.top,1-box.right,1-box.bottom)
        val cramped=start.subjectBox?.let { old -> current.subjectBox?.let {
            margin(old)>=.04f && margin(it)<.025f
        } } == true
        if(current.preferenceScore!=null && current.preferenceModelId!=null &&
            (preferenceBaseline==null || preferenceBaseline?.preferenceModelId!=current.preferenceModelId))preferenceBaseline=current
        val preferredWorse=current.preferenceModelId!=null && current.preferenceModelId==preferenceBaseline?.preferenceModelId &&
            current.preferenceScore?.let { q -> preferenceBaseline?.preferenceScore?.let { q<it-.10f } } == true
        lastReasons=buildList {
            if(sharpWorse)add("subject_sharpness_regressed")
            if(clipped)add("subject_highlights_regressed")
            if(cramped)add("observed_subject_margin_regressed")
            if(clutter)add("background_detail_increased_soft")
            if(preferredWorse)add("validated_preference_regressed")
        }
        if(sharpWorse || clipped || cramped || preferredWorse)return ExecutionComparison.QUALITY_REGRESSED
        if(unverifiedMotion) {
            // A sequence of small independently moving-subject steps must not evade a
            // per-frame threshold. Stable observations can re-establish the current framing.
            unverifiedDx=0f;unverifiedDy=0f
            lastReasons=lastReasons+"subject_motion_not_attributed_to_camera"
            return ExecutionComparison.UNATTRIBUTED_MOTION
        }
        return ExecutionComparison.COMPARABLE
    }
}
