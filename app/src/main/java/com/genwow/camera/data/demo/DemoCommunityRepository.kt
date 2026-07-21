package com.genwow.camera.data.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DemoCommunityRepository {
    var posts: List<DemoPost> by mutableStateOf(
        listOf(
            DemoPost(
                id = "helena-tulips",
                author = "Helena",
                elapsedTime = "3 分钟前",
                body = "描述",
                likes = 21,
                comments = 4,
                imageAssetKey = "figma-feed-tulips",
            ),
            DemoPost(
                id = "daniel-text",
                author = "Daniel",
                elapsedTime = "2 小时前",
                body = "Body text for a post. Since it’s a social app, sometimes it’s a hot take, and sometimes it’s a question.",
                likes = 6,
                comments = 18,
            ),
        ),
    )
        private set

    val profile = DemoProfile(
        displayName = "摄影师",
        bio = "用 AI 构图，记录每一帧美好",
        works = 128,
        followers = 256,
        following = 64,
        galleryItems = listOf(
            DemoGalleryItem("gallery-1", "figma-gallery-1", "花朵作品"),
            DemoGalleryItem("gallery-2", "figma-gallery-2", "蘑菇作品"),
            DemoGalleryItem("gallery-3", "figma-gallery-3", "甜点作品"),
            DemoGalleryItem("gallery-4", "figma-gallery-4", "西瓜作品"),
            DemoGalleryItem("gallery-5", "figma-gallery-5", "街景作品"),
            DemoGalleryItem("gallery-6", "figma-gallery-6", "建筑作品"),
        ),
    )

    /** Local demonstration only. No network, database, account or analytics write occurs. */
    fun toggleLike(postId: String) {
        posts = posts.map { post ->
            if (post.id != postId) {
                post
            } else {
                post.copy(
                    liked = !post.liked,
                    likes = post.likes + if (post.liked) -1 else 1,
                )
            }
        }
    }
}
