package com.example.aicamera

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AiCameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AiCameraApp 启动")
    }

    companion object {
        private const val TAG = "AiCamera"

        lateinit var instance: AiCameraApp
            private set
    }
}
