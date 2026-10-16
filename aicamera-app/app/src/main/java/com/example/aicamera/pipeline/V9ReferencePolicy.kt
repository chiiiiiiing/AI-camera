package com.example.aicamera.pipeline

import org.json.JSONObject

/** Image-level visual review, distinct from the legacy scene-to-composition rules. */
data class V9FaceAnchor(val x: Float, val y: Float, val aspectRatio: Float)

internal object V9ReferencePolicy {
    fun isEligible(record: JSONObject): Boolean {
        val review = record.optJSONObject("reference_review") ?: return false
        return review.optString("status") == "candidate_clean" &&
            review.optString("source_sha256").matches(Regex("[0-9a-fA-F]{64}")) &&
            review.optString("source_sha256") == record.optString("source_sha256") &&
            review.optJSONObject("provenance")?.optString("method") == "ai_visual_review"
    }

    fun parseFaceAnchor(record: JSONObject): V9FaceAnchor? {
        if (!isEligible(record)) return null
        val geometry = record.optJSONObject("reference_geometry") ?: return null
        if (geometry.optString("anchor_kind") != "face" ||
            geometry.optString("coordinate_space") != "exif_upright_full_frame" ||
            geometry.optString("source_sha256") != record.optString("source_sha256") ||
            geometry.optString("method") != "ai_visual_review") return null
        val x = geometry.optDouble("center_x", Double.NaN).toFloat()
        val y = geometry.optDouble("center_y", Double.NaN).toFloat()
        val aspect = geometry.optDouble("aspect_ratio", Double.NaN).toFloat()
        return if (x.isFinite() && y.isFinite() && x in 0.05f..0.95f && y in 0.05f..0.95f &&
            aspect.isFinite() && aspect > 0f) V9FaceAnchor(x, y, aspect) else null
    }

    /** Conservative initial guard, not a calibrated probability or an aesthetic score. */
    fun faceTarget(meta: TemplateMeta, cosine: Float, frameAspectRatio: Float?): V9FaceAnchor? {
        val anchor = meta.faceAnchor ?: return null
        val aspect = frameAspectRatio ?: return null
        if (!meta.referenceEligible || meta.photoType != "portrait" || !cosine.isFinite() || cosine < 0.75f ||
            !aspect.isFinite() || aspect <= 0f) return null
        // Match the reference to the shared camera viewport by a centered crop. A normal
        // 3:4 reference can guide a 9:16 preview; severe crops and clipped faces abstain.
        val retainedWidth = minOf(1f, aspect / anchor.aspectRatio)
        val retainedHeight = minOf(1f, anchor.aspectRatio / aspect)
        if (minOf(retainedWidth, retainedHeight) < 0.7f) return null
        val x = (anchor.x - (1f - retainedWidth) / 2f) / retainedWidth
        val y = (anchor.y - (1f - retainedHeight) / 2f) / retainedHeight
        if (x !in 0.08f..0.92f || y !in 0.08f..0.92f) return null
        return V9FaceAnchor(x, y, aspect)
    }
}
