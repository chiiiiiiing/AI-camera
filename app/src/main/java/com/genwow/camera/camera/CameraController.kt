package com.genwow.camera.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Owns CameraX objects so the Compose layer only receives state and callbacks. */
class CameraController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundCamera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView): CameraZoomState {
        val cameraProvider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        provider = cameraProvider
        imageCapture = capture
        boundCamera = camera
        return camera.currentZoomState()
    }

    fun toggleLens(): Boolean {
        val next = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val available = provider?.hasCamera(
            CameraSelector.Builder().requireLensFacing(next).build(),
        ) ?: false
        if (available) lensFacing = next
        return available
    }

    fun capture(onSuccess: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("相机尚未准备好"))
            return
        }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            File(directory, "GenWow_$name.jpg")
        } else {
            null
        }
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "GenWow_$name.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GenWow")
            }
            ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ).build()
        } else {
            ImageCapture.OutputFileOptions.Builder(requireNotNull(file)).build()
        }
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSuccess(
                        output.savedUri ?: file?.let(Uri::fromFile) ?: Uri.EMPTY,
                    )
                }

                override fun onError(exception: ImageCaptureException) = onError(exception)
            },
        )
    }

    fun setZoomRatio(ratio: Float): CameraZoomState? {
        val camera = boundCamera ?: return null
        val zoomState = camera.cameraInfo.zoomState.value ?: return null
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera.cameraControl.setZoomRatio(clamped)
        return CameraZoomState(
            minZoomRatio = zoomState.minZoomRatio,
            maxZoomRatio = zoomState.maxZoomRatio,
            currentZoomRatio = clamped,
        )
    }

    fun release() {
        provider?.unbindAll()
        provider = null
        imageCapture = null
        boundCamera = null
    }
}

data class CameraZoomState(
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val currentZoomRatio: Float,
)

private fun Camera.currentZoomState(): CameraZoomState {
    val zoomState = cameraInfo.zoomState.value
    return CameraZoomState(
        minZoomRatio = zoomState?.minZoomRatio ?: 1f,
        maxZoomRatio = zoomState?.maxZoomRatio ?: 1f,
        currentZoomRatio = zoomState?.zoomRatio ?: 1f,
    )
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (!continuation.isActive) return@addListener
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
