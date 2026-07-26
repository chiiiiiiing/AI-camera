package com.example.aicamera.di

import android.content.Context
import android.util.Log
import com.example.aicamera.camera.CameraCapture
import com.example.aicamera.camera.CameraManager
import com.example.aicamera.pipeline.AiModelAssets
import com.example.aicamera.pipeline.FakeInferenceAdapter
import com.example.aicamera.pipeline.FramePipeline
import com.example.aicamera.pipeline.FramePreprocessor
import com.example.aicamera.pipeline.FrameScheduler
import com.example.aicamera.pipeline.IInference
import com.example.aicamera.pipeline.V9EmbeddingRuntime
import com.example.aicamera.pipeline.V9Inference
import com.example.aicamera.pipeline.V9TemplateIndex
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

    /**
     * 推理器提供者 — 自动检测 V9 ONNX 模型。
     *
     * 优先级：
     * 1. V9Inference（ONNX 模型 + 模板索引可用时）
     * 2. FakeInferenceAdapter（降级）
     */
    @Provides
    @Singleton
    fun provideInference(@ApplicationContext context: Context): IInference {
        val modelPath = AiModelAssets.getModelPath(context)
        val indexDir = AiModelAssets.getIndexDir(context)

        if (modelPath != null && indexDir != null) {
            return try {
                val runtime = V9EmbeddingRuntime(modelPath)
                val index = V9TemplateIndex(indexDir)
                if (index.load()) {
                    Log.i("AppModule", "V9 ONNX 推理器就绪: ${index.templateCount} 条模板")
                    V9Inference(runtime, index)
                } else {
                    Log.w("AppModule", "模板索引加载失败，降级到 FakeInference")
                    runtime.close()
                    FakeInferenceAdapter()
                }
            } catch (e: Exception) {
                Log.w("AppModule", "V9 模型加载失败: ${e.message}，降级到 FakeInference")
                FakeInferenceAdapter()
            }
        } else {
            Log.i("AppModule", "V9 模型文件未就绪 (model=${modelPath != null}, index=${indexDir != null})，使用 FakeInference")
            return FakeInferenceAdapter()
        }
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
