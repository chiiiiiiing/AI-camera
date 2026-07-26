package com.example.aicamera

import android.app.Application
import android.util.Log
import com.example.aicamera.pipeline.AiModelAssets
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AiCameraApp : Application() {

    /** App 级协程作用域（后台任务，如模型文件复制） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AiCameraApp 启动")

        // 后台复制 AI 模型文件（首次启动或版本升级）
        appScope.launch {
            val result = AiModelAssets.ensureModelFiles(this@AiCameraApp)
            if (result != null) {
                Log.i(TAG, "AI 模型文件就绪: ${result.absolutePath}")
            } else {
                Log.w(TAG, "AI 模型文件未就绪，将使用 FakeInference 降级")
            }
        }
    }

    companion object {
        private const val TAG = "AiCamera"

        lateinit var instance: AiCameraApp
            private set
    }
}
