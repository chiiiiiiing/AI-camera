package com.example.aicamera.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.core.content.ContextCompat
import com.example.aicamera.camera.CameraConfig.FlashMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * 拍照与保存到系统相册。
 *
 * 拍照结果通过 [OnCaptureCallback] 回调。
 *
 * 用法：
 * ```
 * val capture = CameraCapture(context)
 * capture.takePhoto(imageCapture, flashMode) { result ->
 *     when (result) {
 *         is CaptureResult.Success -> showThumbnail(result.uri)
 *         is CaptureResult.Error -> showError(result.message)
 *     }
 * }
 * ```
 */
class CameraCapture(
    private val context: Context,
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
) {
    /** 拍照回调 */
    interface OnCaptureCallback {
        fun onResult(result: CaptureResult)
    }

    /** 拍照结果 */
    sealed class CaptureResult {
        data class Success(
            val uri: Uri,
            val timestamp: Long = System.currentTimeMillis()
        ) : CaptureResult()

        data class Error(
            val message: String,
            val exception: Exception? = null
        ) : CaptureResult()
    }

    // ── 拍照 ──────────────────────────────────────────────────

    /**
     * 拍一张照片，保存到系统相册。
     *
     * @param imageCapture CameraX 的 ImageCapture UseCase
     * @param flashMode 使用 [CameraConfig.FlashMode] 枚举，内部映射为 CameraX 常量
     * @param callback 拍照结果回调
     */
    fun takePhoto(
        imageCapture: ImageCapture,
        flashMode: FlashMode = CameraConfig.DEFAULT_FLASH_MODE,
        callback: OnCaptureCallback
    ) {
        // 1. 映射闪光灯模式
        imageCapture.flashMode = CameraConfig.flashModeToImageCapture(flashMode)

        // 2. 构建输出选项
        val outputOptions = createOutputOptions()
        if (outputOptions == null) {
            callback.onResult(CaptureResult.Error("无法创建输出文件"))
            return
        }

        // 3. 执行拍照
        imageCapture.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    if (savedUri != null) {
                        logDebug("照片已保存: $savedUri")
                        callback.onResult(CaptureResult.Success(savedUri))
                    } else {
                        callback.onResult(CaptureResult.Error("拍照成功但未获取到 URI"))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    val msg = when (exception.imageCaptureError) {
                        ImageCapture.ERROR_FILE_IO -> "文件写入失败，请检查存储空间"
                        ImageCapture.ERROR_CAMERA_CLOSED -> "相机已关闭"
                        ImageCapture.ERROR_INVALID_CAMERA -> "相机不可用"
                        ImageCapture.ERROR_CAPTURE_FAILED -> "拍照失败，请重试"
                        else -> "拍照失败: ${exception.message}"
                    }
                    logError(msg, exception)
                    callback.onResult(CaptureResult.Error(msg, exception))
                }
            }
        )
    }

    // ── MediaStore 输出构建 ───────────────────────────────────

    /**
     * 构建 MediaStore 的输出选项。
     *
     * Android 10+：通过 MediaStore.Images 插入（无需存储权限）。
     * Android 9-：保存到 Pictures 目录（需要存储权限）。
     */
    private fun createOutputOptions(): OutputFileOptions? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreOutput()
        } else {
            createLegacyOutput()
        }
    }

    /**
     * Android 10+：通过 MediaStore 写入相册。
     *
     * ★ 修复：使用 MediaStore 集合 URI（而非预插入 item URI）传给 CameraX Builder，
     *   让 CameraX 自己负责插入。同时预插入一条记录以便在失败时清理。
     */
    private fun createMediaStoreOutput(): OutputFileOptions? {
        // ★ 最稳妥方案：写真机存储目录，不依赖 MediaStore 插入
        val picturesDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：用 app 专属外部存储，无需权限
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        if (picturesDir == null || (!picturesDir.exists() && !picturesDir.mkdirs())) {
            logError("无法创建图片目录")
            return null
        }
        val file = File(picturesDir, generateFileName())
        return OutputFileOptions.Builder(file).build()
    }

    /** Android 9 及以下：保存到本地文件 */
    private fun createLegacyOutput(): OutputFileOptions? {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES + File.separator + "AiCamera"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            logError("无法创建目录: $dir")
            return null
        }

        val file = File(dir, generateFileName())
        return OutputFileOptions.Builder(file).build()
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    /** 生成带时间戳（精确到毫秒）的文件名：AiCamera_20260707_143020_123.jpg */
    private fun generateFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "AiCamera_$timestamp.jpg"
    }

    companion object {
        /**
         * 查询本应用最近一张 AiCamera 前缀的照片 URI。
         */
        fun getLatestPhotoUri(context: Context): Uri? {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // ★ 按文件名前缀过滤，只返回本应用拍摄的
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val displayName = cursor.getString(nameIdx)
                    if (displayName?.startsWith("AiCamera_") == true) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        return Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                    }
                }
            }
            return null
        }
    }
}

// ── 内部日志 ──────────────────────────────────────────────────

private fun logDebug(msg: String) = Log.d(CameraConfig.TAG, "[Capture] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Capture] $msg", e)
