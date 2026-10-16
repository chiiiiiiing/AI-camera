package com.example.aicamera.pipeline

/** The CameraX shared ViewPort crop, expressed in unrotated ImageProxy pixels. */
data class AnalysisCrop(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(bufferWidth > 0 && bufferHeight > 0 && left >= 0 && top >= 0 &&
            right <= bufferWidth && bottom <= bufferHeight && right > left && bottom > top) {
            "Invalid analysis ViewPort crop"
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isFullBuffer: Boolean get() = left == 0 && top == 0 && right == bufferWidth && bottom == bufferHeight

    fun displayAspectRatio(rotationDegrees: Int): Float {
        require(rotationDegrees in listOf(0, 90, 180, 270)) { "Unsupported frame rotation" }
        return if (rotationDegrees % 180 == 0) width.toFloat() / height else height.toFloat() / width
    }

    /** Reference conversion for buffer-space landmarks: crop, rotate, then mirror once.
     * Points outside the viewport stay outside [0,1]; callers must not clamp them
     * into false edge detections.
     */
    fun pointInDisplay(bufferX: Float, bufferY: Float, rotationDegrees: Int, mirrored: Boolean): Pair<Float, Float> {
        require(bufferX.isFinite() && bufferY.isFinite())
        val x = (bufferX - left) / width
        val y = (bufferY - top) / height
        val rotated = when (rotationDegrees) {
            0 -> x to y
            90 -> 1f - y to x
            180 -> 1f - x to 1f - y
            270 -> y to 1f - x
            else -> throw IllegalArgumentException("Unsupported frame rotation")
        }
        return (if (mirrored) 1f - rotated.first else rotated.first) to rotated.second
    }
}
