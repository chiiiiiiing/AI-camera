package com.example.aicamera.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun communityDao(): CommunityDao

    companion object {
        private const val DB_NAME = "aicamera.db"

        /**
         * v3 → v4：架构未变（当初靠 destructive fallback 只提了版本号）。
         * 补一个空迁移，让旧安装升级时不再删库。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // no-op：v3 与 v4 表结构一致
            }
        }

        /**
         * v4 → v5：community_posts 增加独立收藏数列。
         *
         * 旧实现的 increment/decrementFavorites 误写了 likes，收藏数从未真正记录；
         * 这里新增 favorites 列并按收藏表回填历史数据。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE community_posts ADD COLUMN favorites INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE community_posts SET favorites = (" +
                        "SELECT COUNT(*) FROM community_favorites " +
                        "WHERE community_favorites.post_id = community_posts.id)"
                )
            }
        }

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
                    // 显式迁移；不再用 destructive fallback 静默删掉用户数据
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
