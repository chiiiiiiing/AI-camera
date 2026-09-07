package com.example.aicamera.data

import com.example.aicamera.data.db.CommunityComment
import com.example.aicamera.data.db.CommunityDao
import com.example.aicamera.data.db.CommunityFavorite
import com.example.aicamera.data.db.CommunityLike
import com.example.aicamera.data.db.CommunityPost
import com.example.aicamera.data.db.UserFollow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
    private val dao: CommunityDao
) {
    // ── 帖子 ──

    fun getAllPosts(): Flow<List<CommunityPost>> = dao.getAllPosts()

    fun getPostsByAuthor(username: String): Flow<List<CommunityPost>> = dao.getPostsByAuthor(username)

    suspend fun postCountByAuthor(username: String): Int = dao.postCountByAuthor(username)

    suspend fun insertPost(author: String, body: String, imageAssetKey: String?, elapsedText: String): Long =
        withContext(Dispatchers.IO) {
            val post = CommunityPost(
                author = author,
                body = body,
                imageAssetKey = imageAssetKey,
                elapsedText = elapsedText,
            )
            dao.insertPost(post)
        }

    // ── 评论 ──

    fun getCommentsForPost(postId: Long): Flow<List<CommunityComment>> = dao.getCommentsForPost(postId)

    suspend fun addComment(postId: Long, author: String, body: String) {
        withContext(Dispatchers.IO) {
            dao.insertComment(CommunityComment(postId = postId, author = author, body = body))
            dao.incrementCommentCount(postId)
        }
    }

    // ── 点赞 ──

    suspend fun isLiked(postId: Long, username: String): Boolean =
        dao.isLiked(postId, username)

    fun observeIsLiked(postId: Long, username: String): Flow<Boolean> =
        dao.observeIsLiked(postId, username)

    suspend fun toggleLike(postId: Long, username: String) {
        withContext(Dispatchers.IO) {
            if (dao.isLiked(postId, username)) {
                dao.deleteLike(CommunityLike(postId, username))
                dao.decrementLikes(postId)
            } else {
                dao.insertLike(CommunityLike(postId, username))
                dao.incrementLikes(postId)
            }
        }
    }

    fun getLikedPosts(username: String): Flow<List<CommunityPost>> =
        dao.getLikedPosts(username)

    // ── 收藏 ──

    suspend fun isFavorited(postId: Long, username: String): Boolean =
        dao.isFavorited(postId, username)

    fun observeIsFavorited(postId: Long, username: String): Flow<Boolean> =
        dao.observeIsFavorited(postId, username)

    suspend fun toggleFavorite(postId: Long, username: String) {
        withContext(Dispatchers.IO) {
            if (dao.isFavorited(postId, username)) {
                dao.deleteFavorite(CommunityFavorite(postId, username))
            } else {
                dao.insertFavorite(CommunityFavorite(postId, username))
            }
        }
    }

    fun getFavoritedPosts(username: String): Flow<List<CommunityPost>> =
        dao.getFavoritedPosts(username)

    // ── 评论 ──

    fun getCommentsByAuthor(username: String): Flow<List<CommunityComment>> =
        dao.getCommentsByAuthor(username)

    // ── 关注 ──

    suspend fun isFollowing(follower: String, following: String): Boolean =
        dao.isFollowing(follower, following)

    fun observeIsFollowing(follower: String, following: String): Flow<Boolean> =
        dao.observeIsFollowing(follower, following)

    suspend fun toggleFollow(follower: String, following: String) {
        withContext(Dispatchers.IO) {
            if (dao.isFollowing(follower, following)) {
                dao.deleteFollow(UserFollow(follower, following))
            } else {
                dao.insertFollow(UserFollow(follower, following))
            }
        }
    }

    fun followerCount(username: String): Flow<Int> = dao.observeFollowerCount(username)

    fun followingCount(username: String): Flow<Int> = dao.observeFollowingCount(username)

    fun getFollowers(username: String): Flow<List<String>> = dao.getFollowers(username)

    fun getFollowing(username: String): Flow<List<String>> = dao.getFollowing(username)

    // ── 种子数据 ──

    suspend fun seedIfEmpty() {
        if (dao.postCount() > 0) {
            withContext(Dispatchers.IO) {
                dao.replaceDemoAsset("figma-gallery-3", "community-cafe-portrait")
                dao.replaceDemoAsset("figma-gallery-6", "community-blue-city")
                dao.replaceDemoAsset("figma-gallery-5", "community-rainy-street")
                dao.replaceDemoAsset("figma-gallery-2", "community-forest-macro")
            }
            return
        }
        withContext(Dispatchers.IO) {
            // ★ 修复：使用 figma-* 格式的 key，匹配 communityAssetResource 映射
            val seeds = listOf(
                Triple("Helena", "窗边的下午 | 柔和肤色与环境留白，让日常人像更有呼吸感。", "community-cafe-portrait"),
                Triple("Daniel", "蓝调城市 | 对称与引导线让冷色建筑保留秩序。", "community-blue-city"),
                Triple("Helena", "雨后街角 | 冷暖对比和地面倒影共同讲故事。", "community-rainy-street"),
                Triple("Daniel", "清晨的第一缕光穿过花丛 | 低角度让花朵与天空对话。", "figma-feed-tulips"),
                Triple("Helena", "市集的色彩 | 用对角线把视觉重心引向最鲜亮的那一抹。", "figma-gallery-4"),
                Triple("Daniel", "谁说俯拍是美食唯一解？45度角让层次感翻倍。", "figma-gallery-1"),
                Triple("Helena", "雨后森林的蘑菇王国 | 逆光拍摄让菌褶透出琥珀色。", "community-forest-macro"),
            )
            val elapsedTexts = listOf("刚刚", "3 分钟前", "12 分钟前", "28 分钟前", "1 小时前", "2 小时前", "5 小时前")
            // ★ 种子评论：与每个帖子的 commentsCount 保持数据一致
            val seedComments = listOf(
                listOf("构图很棒！", "学习了", "颜色真好看"),
                listOf("城市真的太美了", "好想也拍一张"),
                listOf("倒影构图绝了", com.example.aicamera.filter.CustomFilterConfig.COMMUNITY_SAMPLE.communityText()),
                listOf("花和光线的搭配太绝了！", "低角度学到了"),
                emptyList(),
                listOf("45°真的不一样", "下次试试", "层次感好强", "已收藏"),
                listOf("蘑菇好可爱", "逆光太厉害了"),
            )
            seeds.forEachIndexed { i, (author, body, img) ->
                val postId = dao.insertPost(CommunityPost(
                    author = author,
                    body = body,
                    imageAssetKey = img,
                    elapsedText = elapsedTexts[i % elapsedTexts.size],
                    likes = (10..45).random(),
                    commentsCount = seedComments[i].size  // ★ 数据一致
                ))
                // ★ 插入对应的真实评论，commentCount 不再随机
                seedComments[i].forEach { commentBody ->
                    val commentAuthor = if (i % 2 == 0) "Daniel" else "Helena"
                    dao.insertComment(CommunityComment(
                        postId = postId,
                        author = commentAuthor,
                        body = commentBody
                    ))
                }
            }
        }
    }
}
