package com.example.aicamera.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库 — 目前只存用户表（替代 SharedPreferences JSON）。
 *
 * 后续迭代可按需追加：社区帖子缓存、消息、草稿等表。
 */
@Database(
    entities = [
        LocalUser::class,
        CommunityPost::class,
        CommunityComment::class,
        CommunityLike::class,
        CommunityFavorite::class,
        UserFollow::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun communityDao(): CommunityDao

    companion object {
        private const val DB_NAME = "aicamera.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例（双重检查锁）。
         * 在 Hilt @Singleton 提供者里调用，确保进程内唯一。
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // 开发阶段允许降级重建（表结构稳定后可改为 Migration）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
