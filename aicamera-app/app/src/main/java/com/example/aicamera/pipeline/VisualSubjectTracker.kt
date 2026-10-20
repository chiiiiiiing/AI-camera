package com.example.aicamera.pipeline

import com.example.aicamera.guidance.geometry.NormalizedBox
import kotlin.math.sqrt

/** Local normalized-correlation tracking. A match is visual evidence, never a new semantic detection. */
class VisualSubjectTracker {
    val hasTemplate: Boolean get() = template != null
    private var template: FloatArray? = null
    private var box: NormalizedBox? = null
    private var viewport = Long.MIN_VALUE
    private var detectedAt = Long.MIN_VALUE
    fun reset() { template = null; box = null; viewport = Long.MIN_VALUE; detectedAt = Long.MIN_VALUE }

    fun update(pixels: IntArray, width: Int, height: Int, now: Long, viewportVersion: Long,
               detected: NormalizedBox? = null): NormalizedBox? {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (viewportVersion != viewport) { reset(); viewport = viewportVersion }
        val gw = 64
        val gh = (gw * height / width).coerceIn(32, 128)
        val gray = FloatArray(gw * gh) { n ->
            val x = ((n % gw + .5f) * width / gw).toInt().coerceIn(0, width - 1)
            val y = ((n / gw + .5f) * height / gh).toInt().coerceIn(0, height - 1)
            val p = pixels[y * width + x]
            (.299f * ((p ushr 16) and 255) + .587f * ((p ushr 8) and 255) + .114f * (p and 255)) / 255f
        }
        fun patch(b: NormalizedBox): FloatArray? {
            if (b.left < 0 || b.top < 0 || b.right > 1 || b.bottom > 1 || b.width <= 0 || b.height <= 0) return null
            val values = FloatArray(81) { i ->
                val x = ((b.left + b.width * ((i % 9 + .5f) / 9)) * gw).toInt().coerceIn(0, gw - 1)
                val y = ((b.top + b.height * ((i / 9 + .5f) / 9)) * gh).toInt().coerceIn(0, gh - 1)
                gray[y * gw + x]
            }
            val mean = values.average().toFloat()
            var energy = 0f
            for (i in values.indices) { values[i] -= mean; energy += values[i] * values[i] }
            if (energy / values.size < .0004f) return null
            val length = sqrt(energy)
            for (i in values.indices) values[i] /= length
            return values
        }
        if (detected != null) {
            template = patch(detected)
            box = detected
            detectedAt = now
            return null // The caller already has independent detector evidence.
        }
        val original = template ?: return null
        val before = box ?: return null
        if (now < detectedAt) return null
        var bestScore = .78f
        var best: NormalizedBox? = null
        val matches = ArrayList<Pair<NormalizedBox, Float>>()
        for (scale in listOf(.9f, 1f, 1.1f)) for (dy in -12..12 step 2) for (dx in -12..12 step 2) {
            val cx = before.centerX + dx.toFloat() / gw
            val cy = before.centerY + dy.toFloat() / gh
            val candidate = NormalizedBox(cx - before.width * scale / 2, cy - before.height * scale / 2,
                cx + before.width * scale / 2, cy + before.height * scale / 2)
            val values = patch(candidate) ?: continue
            var score = 0f
            for (i in values.indices) score += original[i] * values[i]
            matches += candidate to score
            if (score > bestScore) { bestScore = score; best = candidate }
        }
        val chosen = best ?: return null
        val competing = matches.filter { (candidate, _) ->
            kotlin.math.hypot(candidate.centerX - chosen.centerX, candidate.centerY - chosen.centerY) > .06f
        }.maxOfOrNull { it.second } ?: -1f
        if (bestScore - competing < .04f) return null
        box = best
        return best
    }
}
