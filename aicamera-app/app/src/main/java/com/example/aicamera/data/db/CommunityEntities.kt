package com.example.aicamera.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 社区帖子 */
@Entity(tableName = "community_posts")
data class CommunityPost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "author") val author: String,
    @ColumnInfo(name = "body") val body: String,
    /** drawable 资源名，如 "gallery_flowers" */
    @ColumnInfo(name = "image_asset_key") val imageAssetKey: String?,
    @ColumnInfo(name = "elapsed_text") val elapsedText: String,  // "3 分钟前"
    @ColumnInfo(name = "likes") val likes: Int = 0,
    /** 收藏数，与点赞数分开统计 */
    @ColumnInfo(name = "favorites", defaultValue = "0") val favorites: Int = 0,
    @ColumnInfo(name = "comments_count") val commentsCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

/** 社区评论 */
@Entity(tableName = "community_comments")
data class CommunityComment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "post_id") val postId: Long,
    @ColumnInfo(name = "author") val author: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

/** 用户点赞记录（防重复点赞） */
@Entity(tableName = "community_likes", primaryKeys = ["post_id", "username"])
data class CommunityLike(
    @ColumnInfo(name = "post_id") val postId: Long,
    @ColumnInfo(name = "username") val username: String
)

/** 用户收藏记录 */
@Entity(tableName = "community_favorites", primaryKeys = ["post_id", "username"])
data class CommunityFavorite(
    @ColumnInfo(name = "post_id") val postId: Long,
    @ColumnInfo(name = "username") val username: String
)

/** 用户关注关系 */
@Entity(tableName = "user_follows", primaryKeys = ["follower", "following"])
data class UserFollow(
    @ColumnInfo(name = "follower") val follower: String,    // 关注者
    @ColumnInfo(name = "following") val following: String  // 被关注者
)
