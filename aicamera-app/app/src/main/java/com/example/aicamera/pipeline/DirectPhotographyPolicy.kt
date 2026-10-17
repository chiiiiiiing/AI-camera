package com.example.aicamera.pipeline

import com.example.aicamera.guidance.CompositionTarget
import com.example.aicamera.guidance.NormalizedPoint
import com.example.aicamera.guidance.ObjectCompositionTarget
import com.example.aicamera.guidance.ObjectObservation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.StructureObservation
import com.example.aicamera.guidance.geometry.NormalizedBox
import kotlin.math.abs
import kotlin.math.hypot

internal enum class DirectFramingIntent { KEEP, IMPROVE_OBSERVED_MARGIN, IMPROVE_FACING_SPACE }

/** The translated box predicts geometry only, never unseen pixels or aesthetic improvement. */
internal data class DirectFramingCandidate(
    val intent: DirectFramingIntent,
    val center: NormalizedPoint,
    val expectedSubjectBox: NormalizedBox,
    val minimumMargin: Float,
    val displacement: Float,
    val reasons: List<String>
)

internal data class DirectPhotographyPlan(
    val candidates: List<DirectFramingCandidate>,
    val selected: DirectFramingCandidate?,
    val portraitTarget: CompositionTarget?,
    val objectTarget: ObjectCompositionTarget?,
    val minimumMarginBefore: Float,
    val minimumMarginExpectedAfter: Float?,
    val reasons: List<String>
)

/**
 * Template-independent objectives: retain healthy composition, relieve measured edge
 * pressure, or add space toward a reliably observed head direction. They are not a
 * learned aesthetic score. Face observations protect only the face, not hair or a full body.
 *
 * Callers lock the selected target across observations. Regenerating a KEEP target every
 * frame would make it follow the subject and incorrectly certify arbitrary movement.
 */
internal object DirectPhotographyPolicy {
    private const val EDGE_PRESSURE = .05f
    private const val TARGET_MARGIN = .13f
    private const val MAX_AXIS_SHIFT = .12f
    // The controller enforces a .06 short-side radius. TARGET_MARGIN leaves enough slack
    // that even the edge of that circle improves a triggered (< .05) margin defect.
    private const val TOLERANCE = .06f

    fun portrait(observation: StructureObservation?, nowMs: Long, viewportVersion: Long): DirectPhotographyPlan? {
        val obs = observation ?: return null
        if (obs.viewportVersion != viewportVersion || !obs.aspectRatio.isFinite() || obs.aspectRatio <= 0f) return null
        val box = obs.singleFace(nowMs)?.takeIf(::valid) ?: return null
        val baseline = propose(box, listOf("face_only_protection", "preserve_current_lighting_style"))
        val proposal = if(baseline.selected?.intent==DirectFramingIntent.KEEP)
            facingSpace(box,obs.facingDirectionX,baseline) else baseline
        val selected = proposal.selected
        val target = selected?.let {
            CompositionTarget(
                id = "rule:face:$viewportVersion:${obs.trackingId ?: "untracked"}:${obs.observationId}",
                center = it.center,
                tolerance = TOLERANCE,
                viewportVersion = viewportVersion,
                source = source(it),
                subjectTrackingId = obs.trackingId,
                referenceFaceHeight = box.height,
                referenceFaceAspectRatio = box.width / box.height,
                referenceYawDegrees = obs.faceYawDegrees?.takeIf { yaw -> yaw.isFinite() },
                requiredFacingDirectionX = obs.facingDirectionX.takeIf { selected.intent==DirectFramingIntent.IMPROVE_FACING_SPACE }
            ).takeIf { candidate -> candidate.accepts(box, obs.faceYawDegrees,obs.facingDirectionX) }
        }
        return proposal.copy(portraitTarget = target)
    }

    fun objectSubject(observation: ObjectObservation?, family: SceneFamily, nowMs: Long,
        viewportVersion: Long): DirectPhotographyPlan? {
        val obs = observation ?: return null
        if (family !in setOf(SceneFamily.FOOD, SceneFamily.STILL_LIFE) ||
            obs.viewportVersion != viewportVersion || obs.classId !in MultiScenePolicy.objectClasses ||
            !obs.isFresh(nowMs) || obs.confidence > 1f || !valid(obs.box) || obs.box.width * obs.box.height > .65f) return null
        val proposal = propose(obs.box, listOf("protect_detected_object", "background_detail_is_soft_evidence",
            "preserve_current_lighting_style"))
        val selected = proposal.selected
        val target = selected?.let {
            ObjectCompositionTarget(
                id = "rule:object:$viewportVersion:${obs.trackingId}:${obs.observationId}",
                center = it.center,
                viewportVersion = viewportVersion,
                trackingId = obs.trackingId,
                classId = obs.classId,
                referenceBox = obs.box,
                tolerance = TOLERANCE,
                source = source(it)
            ).takeIf { candidate -> candidate.accepts(obs) }
        }
        return proposal.copy(objectTarget = target)
    }

    private fun source(candidate: DirectFramingCandidate) = when (candidate.intent) {
        DirectFramingIntent.KEEP -> "photography_rule_keep"
        DirectFramingIntent.IMPROVE_OBSERVED_MARGIN -> "photography_rule_subject_margin"
        DirectFramingIntent.IMPROVE_FACING_SPACE -> "photography_technique_facing_space"
    }

    private fun facingSpace(box:NormalizedBox,direction:Float?,baseline:DirectPhotographyPlan):DirectPhotographyPlan {
        if(direction!=1f && direction!=-1f) return baseline
        val front=if(direction>0) 1f-box.right else box.left
        val behind=if(direction>0) box.left else 1f-box.right
        if(front>=.30f || behind-front<.12f) return baseline
        val dx=-direction*MAX_AXIS_SHIFT
        val moved=NormalizedBox(box.left+dx,box.top,box.right+dx,box.bottom)
        // The action is small and leaves observed face margins safe even at the tolerance edge.
        if(moved.left<.13f || moved.right>.87f) return baseline
        val reasons=listOf("head_facing_space","head_direction_not_eye_gaze","preserve_vertical_framing",
            "verify_new_background_and_quality_after_execution","face_only_protection")
        val candidate=DirectFramingCandidate(DirectFramingIntent.IMPROVE_FACING_SPACE,
            NormalizedPoint(moved.centerX,moved.centerY),moved,margin(moved),abs(dx),reasons)
        return baseline.copy(candidates=baseline.candidates+candidate,selected=candidate,
            minimumMarginExpectedAfter=margin(moved),reasons=reasons)
    }

    private fun propose(box: NormalizedBox, protections: List<String>): DirectPhotographyPlan {
        val before = margin(box)
        val current = DirectFramingCandidate(DirectFramingIntent.KEEP,
            NormalizedPoint(box.centerX, box.centerY), box, before, 0f,
            listOf("preserve_current_composition") + protections)
        if (before >= EDGE_PRESSURE) {
            return DirectPhotographyPlan(listOf(current), current, null, null, before, before,
                listOf("no_measured_subject_edge_defect", "aesthetic_preference_unverified") + protections)
        }

        // Solve each observed boundary constraint with the smallest translation. This does
        // not force centering, thirds, a gaze direction, or a particular amount of headroom.
        fun shift(start: Float, end: Float): Float? {
            if (minOf(start, 1f - end) >= EDGE_PRESSURE) return 0f
            val low = TARGET_MARGIN - start
            val high = 1f - TARGET_MARGIN - end
            if (low > high) return null
            val delta = 0f.coerceIn(low, high)
            return delta.takeIf { abs(it) <= MAX_AXIS_SHIFT }
        }
        val dx = shift(box.left, box.right)
        val dy = shift(box.top, box.bottom)
        if (dx == null || dy == null) {
            return DirectPhotographyPlan(listOf(current), null, null, null, before, null,
                listOf("subject_margin_requires_unverified_scale_change", "keep_available_without_ready_claim") + protections)
        }
        val translated = NormalizedBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
        val after = margin(translated)
        val improved = DirectFramingCandidate(DirectFramingIntent.IMPROVE_OBSERVED_MARGIN,
            NormalizedPoint(translated.centerX, translated.centerY), translated, after, hypot(dx, dy),
            listOf("relieve_measured_subject_edge_pressure", "bounded_observed_subject_translation",
                "verify_new_background_and_quality_after_execution") + protections)
        return DirectPhotographyPlan(listOf(current, improved), improved, null, null, before, after,
            improved.reasons + "geometric_gain_is_not_aesthetic_probability")
    }

    private fun margin(box: NormalizedBox) = minOf(box.left, box.top, 1f - box.right, 1f - box.bottom)
    private fun valid(box: NormalizedBox) = listOf(box.left, box.top, box.right, box.bottom).all {
        it.isFinite() && it in 0f..1f
    } && box.width > 0f && box.height > 0f
}
