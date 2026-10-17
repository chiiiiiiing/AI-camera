package com.example.aicamera.pipeline

import com.example.aicamera.guidance.geometry.NormalizedBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Measured appearance, not a verdict that detail or bright lighting is undesirable. */
internal data class TechniqueRegionEvidence(
    val sampleCount: Int,
    val edgeDensity: Float,
    val brightFraction: Float,
    val meanLuma: Float,
)

/**
 * All non-null measurements are finite and normalized to 0..1. Left/right refer to the outer
 * thirds of the current image, with an expanded observed subject excluded. Upper/lower texture
 * also excludes that subject. Neither field identifies sky, a horizon, foliage, or empty space.
 * A small subject-to-surroundings difference describes lighting, not a universal defect.
 */
internal data class TechniqueVisualEvidence(
    val leftBackground: TechniqueRegionEvidence?,
    val rightBackground: TechniqueRegionEvidence?,
    val subjectMeanLuma: Float?,
    val surroundingsMeanLuma: Float?,
    val subjectBackgroundLumaDifference: Float?,
    val bilateralSymmetry: Float?,
    val upperTexture: Float?,
    val lowerTexture: Float?,
    val sampleWidth: Int,
    val sampleHeight: Int,
)

/**
 * Bounded, stateless appearance analysis. It samples at most 96x96 cells from an existing RGB
 * array and does not retain camera pixels or allocate Android Bitmaps. Measurements describe
 * only the current view and cannot predict the background revealed by a physical camera move.
 */
internal object TechniqueVisualAnalyzer {
    private const val MAX_SIDE = 96
    private const val MIN_REGION_SUPPORT = 64

    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        subject: NormalizedBox? = null,
    ): TechniqueVisualEvidence? {
        if (width < 16 || height < 16 || width.toLong() * height != pixels.size.toLong()) return null
        if (subject != null && (!listOf(subject.left, subject.top, subject.right, subject.bottom)
                .all { it.isFinite() && it in 0f..1f } || subject.width <= 0f || subject.height <= 0f)) return null
        val scale = min(1f, MAX_SIDE.toFloat() / max(width, height))
        val sw = (width * scale).toInt()
        val sh = (height * scale).toInt()
        if (sw < 12 || sh < 12) return null

        // Four taps reduce single-pixel aliasing without scanning an unbounded full camera frame.
        // These are low-resolution appearance features, not a full-resolution sharpness test.
        val luma = FloatArray(sw * sh) { index ->
            val sx = index % sw
            val sy = index / sw
            var sum = 0f
            for (oy in 0..1) for (ox in 0..1) {
                val x = ((sx + .25f + ox * .5f) * width / sw).toInt().coerceIn(0, width - 1)
                val y = ((sy + .25f + oy * .5f) * height / sh).toInt().coerceIn(0, height - 1)
                val pixel = pixels[y * width + x]
                sum += (.299f * ((pixel ushr 16) and 255) + .587f * ((pixel ushr 8) and 255) +
                    .114f * (pixel and 255)) / 255f
            }
            (sum / 4f).coerceIn(0f, 1f)
        }
        fun inside(box: NormalizedBox?, x: Int, y: Int, pad: Float = 0f): Boolean = box != null &&
            (x + .5f) / sw in (box.left - pad)..(box.right + pad) &&
            (y + .5f) / sh in (box.top - pad)..(box.bottom + pad)
        val excluded = BooleanArray(luma.size) { inside(subject, it % sw, it / sw, .025f) }
        val edge = FloatArray(luma.size) { Float.NaN }
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            // Do not count the subject silhouette itself as background clutter.
            if (excluded[i] || excluded[i - 1] || excluded[i + 1] || excluded[i - sw] || excluded[i + sw]) continue
            edge[i] = (abs(luma[i + 1] - luma[i]) + abs(luma[i - 1] - luma[i]) +
                abs(luma[i + sw] - luma[i]) + abs(luma[i - sw] - luma[i])) / 4f
        }
        fun region(select: (Int, Int) -> Boolean): TechniqueRegionEvidence? {
            var count = 0
            var edgeCount = 0
            var mean = 0f
            var detail = 0f
            var bright = 0
            for (y in 0 until sh) for (x in 0 until sw) {
                val i = y * sw + x
                if (excluded[i] || !select(x, y)) continue
                count++
                mean += luma[i]
                if (luma[i] >= .92f) bright++
                if (edge[i].isFinite()) { detail += edge[i]; edgeCount++ }
            }
            if (count < MIN_REGION_SUPPORT || edgeCount < MIN_REGION_SUPPORT) return null
            return TechniqueRegionEvidence(count, (detail / edgeCount).coerceIn(0f, 1f),
                (bright.toFloat() / count).coerceIn(0f, 1f), (mean / count).coerceIn(0f, 1f))
        }
        val left = region { x, _ -> (x + .5f) / sw < 1f / 3f }
        val right = region { x, _ -> (x + .5f) / sw > 2f / 3f }
        val upper = region { _, y -> (y + .5f) / sh < 1f / 3f }
        val lower = region { _, y -> (y + .5f) / sh > 2f / 3f }

        var subjectCount = 0
        var subjectSum = 0f
        var surroundingsCount = 0
        var surroundingsSum = 0f
        if (subject != null) for (y in 0 until sh) for (x in 0 until sw) {
            val i = y * sw + x
            if (inside(subject, x, y)) { subjectCount++; subjectSum += luma[i] }
            else if (!excluded[i] && inside(subject, x, y, .12f)) {
                surroundingsCount++; surroundingsSum += luma[i]
            }
        }
        val subjectLuma = if (subjectCount >= 36) (subjectSum / subjectCount).coerceIn(0f, 1f) else null
        val surroundingsLuma = if (surroundingsCount >= MIN_REGION_SUPPORT)
            (surroundingsSum / surroundingsCount).coerceIn(0f, 1f) else null

        // A blank wall must not become strong evidence for a symmetric composition. Require
        // visible variation and enough gradient support before comparing mirrored image halves.
        val mean = luma.average().toFloat()
        val deviation = sqrt(luma.sumOf { ((it - mean) * (it - mean)).toDouble() } / luma.size).toFloat()
        var symmetryDifference = 0f
        var symmetryCount = 0
        var fullImageDetail = 0f
        var detailCount = 0
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            fullImageDetail += (abs(luma[i + 1] - luma[i]) + abs(luma[i + sw] - luma[i])) / 2f
            detailCount++
            if (x < sw / 2) {
                symmetryDifference += abs(luma[i] - luma[y * sw + sw - 1 - x])
                symmetryCount++
            }
        }
        val symmetry = if (symmetryCount >= 256 && deviation >= .04f &&
            fullImageDetail / max(1, detailCount) >= .006f)
            (1f - symmetryDifference / symmetryCount / max(.08f, deviation * 2f)).coerceIn(0f, 1f)
        else null
        return TechniqueVisualEvidence(left, right, subjectLuma, surroundingsLuma,
            if (subjectLuma != null && surroundingsLuma != null) abs(subjectLuma - surroundingsLuma) else null,
            symmetry, upper?.edgeDensity, lower?.edgeDensity, sw, sh)
    }
}
