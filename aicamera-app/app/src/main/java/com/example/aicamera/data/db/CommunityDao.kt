package com.example.aicamera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityDao {

    // ── 帖子 ──
    @Query("SELECT * FROM community_posts ORDER BY created_at DESC")
    fun getAllPosts(): Flow<List<CommunityPost>>

    @Query("SELECT * FROM community_posts WHERE author = :username ORDER BY created_at DESC")
    fun getPostsByAuthor(username: String): Flow<List<CommunityPost>>

    @Query("SELECT COUNT(*) FROM community_posts WHERE author = :username")
    suspend fun postCountByAuthor(username: String): Int

    @Insert
    suspend fun insertPost(post: CommunityPost): Long

    @Query("UPDATE community_posts SET likes = likes + 1 WHERE id = :postId")
    suspend fun incrementLikes(postId: Long)

    @Query("UPDATE community_posts SET likes = likes - 1 WHERE id = :postId AND likes > 0")
    suspend fun decrementLikes(postId: Long)

    @Query("UPDATE community_posts SET comments_count = comments_count + 1 WHERE id = :postId")
    suspend fun incrementCommentCount(postId: Long)

    // ── 评论 ──
    @Query("SELECT * FROM community_comments WHERE post_id = :postId ORDER BY created_at ASC")
    fun getCommentsForPost(postId: Long): Flow<List<CommunityComment>>

    @Insert
    suspend fun insertComment(comment: CommunityComment): Long

    // ── 点赞 ──
    @Query("SELECT COUNT(*) FROM community_likes WHERE post_id = :postId AND username = :username")
    suspend fun isLiked(postId: Long, username: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM community_likes WHERE post_id = :postId AND username = :username)")
    fun observeIsLiked(postId: Long, username: String): Flow<Boolean>

    @Query("SELECT * FROM community_posts WHERE id IN (SELECT post_id FROM community_likes WHERE username = :username) ORDER BY created_at DESC")
    fun getLikedPosts(username: String): Flow<List<CommunityPost>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLike(like: CommunityLike)

    @Delete
    suspend fun deleteLike(like: CommunityLike)

    // ── 收藏 ──
    @Query("SELECT COUNT(*) FROM community_favorites WHERE post_id = :postId AND username = :username")
    suspend fun isFavorited(postId: Long, username: String): Boolean

    @Query("SELECT * FROM community_posts WHERE id IN (SELECT post_id FROM community_favorites WHERE username = :username) ORDER BY created_at DESC")
    fun getFavoritedPosts(username: String): Flow<List<CommunityPost>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorite(favorite: CommunityFavorite)

    @Delete
    suspend fun deleteFavorite(favorite: CommunityFavorite)

    @Query("UPDATE community_posts SET likes = likes + 1 WHERE id = :postId")
    suspend fun incrementFavorites(postId: Long)

    @Query("UPDATE community_posts SET likes = likes - 1 WHERE id = :postId AND likes > 0")
    suspend fun decrementFavorites(postId: Long)

    // ── 评论 ──
    @Query("SELECT * FROM community_comments WHERE author = :username ORDER BY created_at DESC")
    fun getCommentsByAuthor(username: String): Flow<List<CommunityComment>>
    @Query("SELECT COUNT(*) FROM user_follows WHERE follower = :follower AND following = :following")
    suspend fun isFollowing(follower: String, following: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFollow(follow: UserFollow)

    @Delete
    suspend fun deleteFollow(follow: UserFollow)

    @Query("SELECT COUNT(*) FROM user_follows WHERE following = :username")
    suspend fun followerCount(username: String): Int

    @Query("SELECT COUNT(*) FROM user_follows WHERE follower = :username")
    suspend fun followingCount(username: String): Int

    @Query("SELECT COUNT(*) FROM user_follows WHERE following = :username")
    fun observeFollowerCount(username: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_follows WHERE follower = :username")
    fun observeFollowingCount(username: String): Flow<Int>

    @Query("SELECT follower FROM user_follows WHERE following = :username ORDER BY follower ASC")
    fun getFollowers(username: String): Flow<List<String>>

    @Query("SELECT following FROM user_follows WHERE follower = :username ORDER BY following ASC")
    fun getFollowing(username: String): Flow<List<String>>

    // ── 种子数据 ──
    @Query("SELECT COUNT(*) FROM community_posts")
    suspend fun postCount(): Int

    @Query("UPDATE community_posts SET image_asset_key = :newKey WHERE image_asset_key = :oldKey")
    suspend fun replaceDemoAsset(oldKey: String, newKey: String)
}
