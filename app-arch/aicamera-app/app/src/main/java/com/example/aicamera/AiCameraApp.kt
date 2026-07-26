package com.example.aicamera

import android.app.Application
import android.util.Log
import com.example.aicamera.pipeline.AiEngineManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AiCameraApp : Application() {

    /** AI 引擎装配管理器（复制资产 → 校验 → 加载模型，全在后台线程） */
    @Inject
    lateinit var aiEngine: AiEngineManager

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AiCameraApp 启动")

        // 后台装配 V9 推理引擎（幂等；失败自动保持 FakeInference 兜底）
        aiEngine.start()
    }

    companion object {
        private const val TAG = "AiCamera"
    }
}
