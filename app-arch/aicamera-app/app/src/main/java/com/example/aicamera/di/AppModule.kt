package com.example.aicamera.di

import android.content.Context
import com.example.aicamera.camera.CameraCapture
import com.example.aicamera.camera.CameraManager
import com.example.aicamera.pipeline.FakeInferenceAdapter
import com.example.aicamera.pipeline.FramePipeline
import com.example.aicamera.pipeline.FramePreprocessor
import com.example.aicamera.pipeline.FrameScheduler
import com.example.aicamera.pipeline.IInference
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI 模块 — 提供全局单例依赖。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── 相机链路 ──────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideCameraManager(@ApplicationContext context: Context): CameraManager {
        return CameraManager(context)
    }

    @Provides
    @Singleton
    fun provideCameraCapture(@ApplicationContext context: Context): CameraCapture {
        return CameraCapture(context)
    }

    // ── 帧处理管线 ────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideFrameScheduler(): FrameScheduler {
        return FrameScheduler()
    }

    @Provides
    @Singleton
    fun provideFramePreprocessor(): FramePreprocessor {
        return FramePreprocessor(targetSize = 320)
    }

    @Provides
    @Singleton
    fun provideInference(): IInference {
        // MVP 阶段使用假推理，队友给模型后替换
        return FakeInferenceAdapter()
    }

    @Provides
    @Singleton
    fun provideFramePipeline(
        scheduler: FrameScheduler,
        preprocessor: FramePreprocessor,
        inference: IInference
    ): FramePipeline {
        return FramePipeline(
            scheduler = scheduler,
            preprocessor = preprocessor,
            inference = inference
        )
    }
}
