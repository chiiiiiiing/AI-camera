package com.example.aicamera.di

import android.content.Context
import com.example.aicamera.camera.CameraCapture
import com.example.aicamera.camera.CameraManager
import com.example.aicamera.data.db.AppDatabase
import com.example.aicamera.data.db.CommunityDao
import com.example.aicamera.data.db.UserDao
import com.example.aicamera.pipeline.FramePipeline
import com.example.aicamera.pipeline.FramePreprocessor
import com.example.aicamera.pipeline.FrameScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI 模块 — 提供全局单例依赖。
 *
 * 注意：AI 推理引擎不在此装配。[AiEngineManager]（@Singleton @Inject）负责
 * 异步复制资产 → 校验 → 加载模型，PipelineViewModel 在其 READY 后把
 * V9Inference 热替换进 [FramePipeline]。此处的管线默认用 FakeInference 兜底。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── 数据库 (Room) ────────────────────────────────────────

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideUserDao(db: AppDatabase): UserDao {
        return db.userDao()
    }

    @Provides
    @Singleton
    fun provideCommunityDao(db: AppDatabase): CommunityDao {
        return db.communityDao()
    }

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
        // V9 模型输入为 224×224（见 android_runtime_config.json）
        return FramePreprocessor(targetSize = 224)
    }

    /**
     * 帧管线（默认 FakeInference 兜底，AI 就绪后由 PipelineViewModel 热替换为 V9）。
     */
    @Provides
    @Singleton
    fun provideFramePipeline(
        scheduler: FrameScheduler,
        preprocessor: FramePreprocessor
    ): FramePipeline {
        return FramePipeline(
            scheduler = scheduler,
            preprocessor = preprocessor
        )
    }
}
