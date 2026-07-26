package com.example.aicamera.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 相机权限管理工具。
 */
class CameraPermission(
    private val fragment: Fragment
) {
    private val context: Context get() = fragment.requireContext()
    private val activity: Activity get() = fragment.requireActivity()

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        val STORAGE_PERMISSIONS: Array<String>
            get() = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            } else {
                emptyArray()
            }

        val ALL_PERMISSIONS: Array<String>
            get() = REQUIRED_PERMISSIONS + STORAGE_PERMISSIONS

        fun hasCameraPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        fun requestIfNeeded(activity: Activity) {
            val missing = ALL_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(activity, missing.toTypedArray(), 1001)
            }
        }
    }

    fun cameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun storageGranted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) return true
        return STORAGE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun allGranted(): Boolean = cameraGranted() && storageGranted()

    fun requestAll() {
        val missing = ALL_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            fragment.requestPermissions(
                missing.toTypedArray(),
                CameraConfig.REQUEST_CODE_CAMERA_PERMISSION
            )
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray,
        onResult: (allGranted: Boolean) -> Unit
    ) {
        if (requestCode != CameraConfig.REQUEST_CODE_CAMERA_PERMISSION) return

        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (!allGranted) {
            logWarning("权限被拒绝: requestCode=$requestCode")
        }

        onResult(allGranted)
    }

    fun shouldShowRationale(): Boolean {
        return ALL_PERMISSIONS.any {
            fragment.shouldShowRequestPermissionRationale(it)
        }
    }

    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logError("无法打开应用设置页", e)
        }
    }

    fun openAppSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            logError("无法打开应用设置页", e)
        }
    }
}

private fun logWarning(msg: String) = Log.w(CameraConfig.TAG, "[Permission] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Permission] $msg", e)
