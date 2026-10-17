package com.example.aicamera.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.data.CommunityRepository
import com.example.aicamera.data.db.CommunityComment
import com.example.aicamera.data.db.CommunityPost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val repo: CommunityRepository
) : ViewModel() {

    private val _posts = MutableStateFlow<List<CommunityPost>>(emptyList())
    val posts: StateFlow<List<CommunityPost>> = _posts.asStateFlow()

    /** 帖子是否已完成首次加载（用于区分加载中与空列表，避免首屏空态闪现） */
    private val _postsLoading = MutableStateFlow(true)
    val postsLoading: StateFlow<Boolean> = _postsLoading.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                repo.seedIfEmpty()
                repo.getAllPosts().collect {
                    _posts.value = it
                    _postsLoading.value = false
                }
            }.onFailure { e ->
                _postsLoading.value = false
                Log.w(TAG, "社区数据初始化失败: ${e.message}", e)
            }
        }
    }

    fun getComments(postId: Long): Flow<List<CommunityComment>> = repo.getCommentsForPost(postId)

    suspend fun isLiked(postId: Long, username: String): Boolean = repo.isLiked(postId, username)

    fun observeIsLiked(postId: Long, username: String): Flow<Boolean> =
        repo.observeIsLiked(postId, username)

    fun toggleLike(postId: Long, username: String) {
        viewModelScope.launch { repo.toggleLike(postId, username) }
    }

    fun getLikedPosts(username: String): Flow<List<CommunityPost>> =
        repo.getLikedPosts(username)

    /** 添加评论。返回是否成功，供 UI 做发送中 loading 与失败回滚。 */
    suspend fun addComment(postId: Long, author: String, body: String): Boolean =
        try {
            repo.addComment(postId, author, body)
            true
        } catch (e: Exception) {
            Log.w(TAG, "评论发送失败: ${e.message}", e)
            false
        }

    fun postCountByAuthor(author: String, onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(repo.postCountByAuthor(author)) }
    }

    /** 获取指定作者的所有帖子（Flow，用于 Profile 作品网格） */
    fun getPostsByAuthor(username: String): Flow<List<CommunityPost>> =
        repo.getPostsByAuthor(username)

    /** 发布新帖子 */
    fun publishPost(
        author: String,
        body: String,
        imageAssetKey: String?,
        elapsedText: String,
        onResult: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = repo.insertPost(author, body, imageAssetKey, elapsedText)
            onResult(id)
        }
    }

    // ── 收藏 ──

    suspend fun isFavorited(postId: Long, username: String): Boolean =
        repo.isFavorited(postId, username)

    fun observeIsFavorited(postId: Long, username: String): Flow<Boolean> =
        repo.observeIsFavorited(postId, username)

    fun toggleFavorite(postId: Long, username: String) {
        viewModelScope.launch { repo.toggleFavorite(postId, username) }
    }

    fun getFavoritedPosts(username: String): Flow<List<CommunityPost>> =
        repo.getFavoritedPosts(username)

    // ── 评论 ──

    fun getCommentsByAuthor(username: String): Flow<List<CommunityComment>> =
        repo.getCommentsByAuthor(username)

    // ── 关注 ──

    suspend fun isFollowing(follower: String, following: String): Boolean =
        repo.isFollowing(follower, following)

    fun observeIsFollowing(follower: String, following: String): Flow<Boolean> =
        repo.observeIsFollowing(follower, following)

    fun toggleFollow(follower: String, following: String) {
        viewModelScope.launch { repo.toggleFollow(follower, following) }
    }

    fun followerCount(username: String): Flow<Int> = repo.followerCount(username)

    fun followingCount(username: String): Flow<Int> = repo.followingCount(username)

    fun getFollowers(username: String): Flow<List<String>> = repo.getFollowers(username)

    fun getFollowing(username: String): Flow<List<String>> = repo.getFollowing(username)

    companion object {
        private const val TAG = "CommunityVM"
    }
}
