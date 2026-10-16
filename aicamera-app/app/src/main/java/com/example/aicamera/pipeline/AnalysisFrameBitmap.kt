package com.example.aicamera.pipeline

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

fun ImageProxy.analysisCrop(): AnalysisCrop = cropRect.let {
    AnalysisCrop(width, height, it.left, it.top, it.right, it.bottom)
}

/** Owns the decoded bitmap, returning the shared preview crop before rotation/mirroring. */
fun ImageProxy.toViewPortBitmap(): Bitmap {
    val crop = analysisCrop()
    val full = toBitmap()
    try {
        require(full.width == width && full.height == height) { "Unexpected decoded analysis buffer size" }
        if (crop.isFullBuffer) return full
        val cropped = Bitmap.createBitmap(full, crop.left, crop.top, crop.width, crop.height)
        if (cropped !== full) full.recycle()
        return cropped
    } catch (error: Exception) {
        if (!full.isRecycled) full.recycle()
        throw error
    }
}
