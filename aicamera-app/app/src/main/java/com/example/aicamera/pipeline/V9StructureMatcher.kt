package com.example.aicamera.pipeline

import com.example.aicamera.guidance.CompositionTarget
import com.example.aicamera.guidance.NormalizedPoint
import com.example.aicamera.guidance.StructureObservation
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

data class ReferenceStructure(val face: NormalizedBox, val aspect: Float, val yaw: Float?)

/** Hash-bound, optional annotation sidecar. Invalid rows never supply geometry. */
internal object ReferenceStructures {
    fun load(file: File, metadata: Map<String, TemplateMeta>): Map<String, ReferenceStructure> = try {
        if (!file.isFile || file.length() > 4_000_000) emptyMap() else {
            val root = JSONObject(file.readText())
            require(root.getInt("schema_version") == 1)
            require(root.getString("coordinate_space") == "exif_upright_full_frame")
            val rows = root.getJSONArray("records")
            buildMap {
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val id = r.getString("template_id")
                    val meta = metadata[id] ?: continue
                    if (!meta.referenceEligible || meta.imageSha256.isBlank() ||
                        r.optString("image_sha256") != meta.imageSha256 || r.optBoolean("synthetic", true) ||
                        !r.optBoolean("structure_eligible") || r.optInt("face_count") != 1) continue
                    val faces = r.optJSONArray("faces") ?: continue
                    if (faces.length() != 1) continue
                    val f = faces.getJSONObject(0)
                    val confidence = f.optDouble("confidence", Double.NaN)
                    if (!confidence.isFinite() || confidence < .75 || f.optBoolean("clipped", true)) continue
                    val b = f.getJSONArray("bbox")
                    if (b.length() != 4) continue
                    val x = b.getDouble(0).toFloat(); val y = b.getDouble(1).toFloat()
                    val w = b.getDouble(2).toFloat(); val h = b.getDouble(3).toFloat()
                    if (listOf(x,y,w,h).any { !it.isFinite() } || x < 0 || y < 0 ||
                        w < .04f || h < .04f || x+w > 1 || y+h > 1) continue
                    val aspect = r.getDouble("image_width").toFloat() / r.getDouble("image_height").toFloat()
                    if (!aspect.isFinite() || aspect <= 0) continue
                    val yaw = f.optDouble("yaw_degrees", Double.NaN).toFloat().takeIf { it.isFinite() }
                    put(id, ReferenceStructure(NormalizedBox(x,y,x+w,y+h), aspect, yaw))
                }
            }
        }
    } catch (_: Exception) { emptyMap() }
}

/** Compatibility describes reachable framing, not how close the subject already is to a target. */
internal class V9StructureMatcher {
    private var locked: CompositionTarget? = null
    private var trackingId: Int? = null
    private var excludedId: String? = null

    fun reset() { locked = null; trackingId = null; excludedId = null }
    fun replan() { excludedId = locked?.id; locked = null }

    fun rank(hits: List<SearchHit>, obs: StructureObservation?, nowMs: Long): List<SearchHit> {
        val face = obs?.singleFace(nowMs) ?: return hits.take(8)
        return hits.map { hit ->
            val ref = hit.metadata?.structure
            val mapped = ref?.let { mapFace(it, obs.aspectRatio) }
            val compatible = mapped != null && compatible(face, mapped, obs.faceYawDegrees, ref.yaw)
            hit.copy(rerankScore = hit.rerankScore + if (compatible) .12f else -.04f)
        }.sortedWith(compareByDescending<SearchHit> { it.rerankScore }.thenBy { it.templateId }).take(8)
    }

    fun target(hits: List<SearchHit>, obs: StructureObservation?, nowMs: Long): CompositionTarget? {
        val face = obs?.singleFace(nowMs)
        if (obs == null || face == null) { reset(); return null }
        if ((locked != null && locked?.viewportVersion != obs.viewportVersion) ||
            (trackingId != null && obs.trackingId != null && trackingId != obs.trackingId)) reset()
        trackingId = obs.trackingId
        locked?.let { target ->
            val supportingHit = hits.firstOrNull { it.templateId == target.id && it.cosine >= .70f }
            val ref = supportingHit?.metadata?.structure
            val mapped = ref?.let { mapFace(it, obs.aspectRatio) }
            if (target.accepts(face, obs.faceYawDegrees) && mapped != null && compatible(face,mapped,obs.faceYawDegrees,ref.yaw)) return target
            locked = null
            return null
        }
        val hit = hits.firstOrNull { h ->
            val ref = h.metadata?.structure
            val mapped = ref?.let { mapFace(it, obs.aspectRatio) }
            h.templateId != excludedId && h.cosine >= .75f && mapped != null && compatible(face, mapped, obs.faceYawDegrees, ref.yaw)
        } ?: return null
        val targetFace = mapFace(hit.metadata!!.structure!!, obs.aspectRatio) ?: return null
        val dx = targetFace.centerX - face.centerX
        val dy = targetFace.centerY - face.centerY
        // Only small view adjustments are supported; no invented walking or 3D translations.
        if (abs(dx) > .28f || abs(dy) > .28f || face.left + dx < .03f || face.right + dx > .97f ||
            face.top + dy < .03f || face.bottom + dy > .97f) return null
        // Current composition is an explicit candidate: when within tolerance, keep it.
        val keep = abs(dx) <= .06f && abs(dy) <= .06f
        return CompositionTarget(hit.templateId, NormalizedPoint(
            if (keep) face.centerX else targetFace.centerX,
            if (keep) face.centerY else targetFace.centerY),
            viewportVersion = obs.viewportVersion, subjectTrackingId = obs.trackingId, referenceFaceHeight = targetFace.height, referenceFaceAspectRatio = targetFace.width/targetFace.height,
            referenceYawDegrees = hit.metadata.structure?.yaw, source = if (keep) "current_compatible" else "reference_structure"
        ).also { locked = it }
    }

    companion object {
        fun compatible(live: NormalizedBox, ref: NormalizedBox, liveYaw: Float?, refYaw: Float?): Boolean {
            val ratio = live.height / ref.height
            if (!ratio.isFinite() || ratio < .65f || ratio > 1.55f) return false
            if (abs(ln((live.width / live.height / (ref.width / ref.height)).toDouble())) > .45) return false
            return liveYaw == null || refYaw == null || abs(liveYaw-refYaw) <= 25f
        }
        fun mapFace(ref: ReferenceStructure, aspect: Float): NormalizedBox? {
            if (!aspect.isFinite() || aspect <= 0) return null
            val keptW = minOf(1f, aspect/ref.aspect); val keptH = minOf(1f, ref.aspect/aspect)
            if (keptW < .7f || keptH < .7f) return null
            val b = ref.face
            val mapped = NormalizedBox((b.left-(1-keptW)/2)/keptW, (b.top-(1-keptH)/2)/keptH,
                (b.right-(1-keptW)/2)/keptW, (b.bottom-(1-keptH)/2)/keptH)
            return mapped.takeIf { it.left >= .03f && it.top >= .03f && it.right <= .97f && it.bottom <= .97f }
        }
    }
}
