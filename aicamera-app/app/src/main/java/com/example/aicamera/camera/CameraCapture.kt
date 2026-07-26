package com.example.aicamera.camera

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
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
 * 拍照与保存。
 *
 * ## 保存策略（修复 P1-8：照片不进系统相册的问题）
 * - Android 10+ 且 saveToGallery=true：经 MediaStore 插入 Pictures/AiCamera，
 *   系统相册可见，卸载不丢，缩略图/相册页查询可用；
 * - saveToGallery=false：保存到应用私有目录（不进相册，卸载即删）；
 * - Android 9-：写公共 Pictures 目录（先检查 WRITE_EXTERNAL_STORAGE 运行时权限），
 *   写完经 MediaScannerConnection 扫描进相册。
 *
 * 拍照结果通过 [OnCaptureCallback] 回调。
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
     * 拍一张照片。
     *
     * @param imageCapture CameraX 的 ImageCapture UseCase
     * @param flashMode 使用 [CameraConfig.FlashMode] 枚举，内部映射为 CameraX 常量
     * @param saveToGallery true = 保存到系统相册（Pictures/AiCamera）；
     *                      false = 仅保存到应用私有目录
     * @param callback 拍照结果回调
     */
    fun takePhoto(
        imageCapture: ImageCapture,
        flashMode: FlashMode = CameraConfig.DEFAULT_FLASH_MODE,
        saveToGallery: Boolean = true,
        callback: OnCaptureCallback
    ) {
        // 1. 映射闪光灯模式
        imageCapture.flashMode = CameraConfig.flashModeToImageCapture(flashMode)

        // 2. 构建输出选项
        val outputOptions = createOutputOptions(saveToGallery)
        if (outputOptions == null) {
            callback.onResult(CaptureResult.Error("无法创建输出文件（可能缺少存储权限）"))
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

    // ── 输出构建 ──────────────────────────────────────────────

    /**
     * 按平台版本和 saveToGallery 选择输出目标。
     */
    private fun createOutputOptions(saveToGallery: Boolean): OutputFileOptions? {
        return when {
            // Android 10+ 存相册：经 MediaStore 插入（无需任何存储权限）
            saveToGallery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                createMediaStoreOutput()
            // Android 9- 存相册：公共目录 + 运行时权限检查 + 媒体扫描
            saveToGallery -> createLegacyPublicOutput()
            // 仅应用私有目录
            else -> createAppPrivateOutput()
        }
    }

    /**
     * Android 10+：通过 MediaStore 写入系统相册。
     * CameraX 会在保存成功后返回 content:// URI，系统相册立即可见。
     */
    private fun createMediaStoreOutput(): OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, generateFileName())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + "AiCamera"
            )
        }
        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        return OutputFileOptions.Builder(context.contentResolver, collection, contentValues).build()
    }

    /**
     * Android 9 及以下：保存到公共 Pictures/AiCamera 目录。
     * ★ 先检查 WRITE_EXTERNAL_STORAGE 运行时权限；保存后需媒体扫描才进相册。
     */
    private fun createLegacyPublicOutput(): OutputFileOptions? {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            logError("缺少 WRITE_EXTERNAL_STORAGE 权限，无法保存到公共相册")
            return null
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "AiCamera"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            logError("无法创建目录: $dir")
            return null
        }

        val file = File(dir, generateFileName())
        scanOnSaved(file)
        return OutputFileOptions.Builder(file).build()
    }

    /**
     * 仅应用私有目录（不进系统相册，卸载即删）。
     */
    private fun createAppPrivateOutput(): OutputFileOptions? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            logError("无法创建应用私有图片目录")
            return null
        }
        val file = File(dir, generateFileName())
        return OutputFileOptions.Builder(file).build()
    }

    /**
     * 旧平台保存后触发媒体扫描，让照片出现在系统相册。
     * （在保存成功回调之外预先注册路径，takePicture 成功后由系统广播扫描）
     */
    private fun scanOnSaved(file: File) {
        // 延迟扫描：CameraX 写盘是异步的，这里等 500ms 再扫，确保文件已写完
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
        }, 500)
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
