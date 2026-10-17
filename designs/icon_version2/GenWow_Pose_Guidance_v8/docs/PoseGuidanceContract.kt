package com.genwow.pose.designcontract

/** Design contract ONLY. Not part of existing AI-camera source. */
data class PosePoint(val x: Float, val y: Float, val confidence: Float)
data class PoseLandmarks(val points: Map<String, PosePoint>)
enum class PoseState { TARGET_VISIBLE, ADJUSTING, VERIFYING, ALIGNED, TRACKING_LOST }
data class PoseCorrection(
    val joint: String,
    val pivotX: Float,
    val pivotY: Float,
    val signedSweepDegrees: Float,
    val confidence: Float
)
data class PoseGuidanceFrame(
    val targetPoseId: String,
    val target: PoseLandmarks,
    val current: PoseLandmarks?,
    val state: PoseState,
    val correction: PoseCorrection?,
    val timestampMs: Long,
    val trackingValid: Boolean
)

/* UI rules:
 * 1. Map BOTH target and current points through the SAME camera-to-preview transform.
 * 2. Do not mirror twice for the front camera.
 * 3. Target silhouette: SVG template with runtime subject transform.
 * 4. Current outline: segmentation boundary if available; else labeled schematic keypoint-based contour.
 * 5. Correction arc: draw only with validated guidance and sufficient confidence.
 * 6. Green only on independent pose verifier ALIGNED; NEVER on animation completion.
 * 7. On lost tracking/scene change, clear stale correction immediately.
 */
