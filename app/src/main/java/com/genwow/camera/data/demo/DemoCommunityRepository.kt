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
                body = "黄昏散步顺手拍到的一束光，AI 构图把花束和路灯的比例卡得刚刚好。",
                likes = 21,
                comments = 4,
                imageAssetKey = "figma-feed-tulips",
                imageAspectRatio = 0.8f,
            ),
            DemoPost(
                id = "daniel-mushrooms",
                author = "Daniel",
                elapsedTime = "2 小时前",
                body = "阴天森林的蘑菇颜色很平，但把画面往左收一点之后，层次突然就出来了。",
                likes = 48,
                comments = 18,
                imageAssetKey = "figma-gallery-2",
                imageAspectRatio = 0.68f,
            ),
            DemoPost(
                id = "helena-dessert",
                author = "Helena",
                elapsedTime = "昨天",
                body = "甜点台面太乱的时候，用构图框先压边缘，主体一下子干净很多。",
                likes = 36,
                comments = 7,
                imageAssetKey = "figma-gallery-3",
                imageAspectRatio = 0.92f,
            ),
            DemoPost(
                id = "daniel-street",
                author = "Daniel",
                elapsedTime = "昨天",
                body = "街景不一定要拍满，把建筑和行人的节奏留一点空，照片会更会呼吸。",
                likes = 65,
                comments = 12,
                imageAssetKey = "figma-gallery-5",
                imageAspectRatio = 0.72f,
            ),
            DemoPost(
                id = "helena-architecture",
                author = "Helena",
                elapsedTime = "2 天前",
                body = "建筑透视最怕歪，调整俯仰角的时候这个引导真的比自己瞎试快很多。",
                likes = 54,
                comments = 9,
                imageAssetKey = "figma-gallery-6",
                imageAspectRatio = 1.08f,
            ),
            DemoPost(
                id = "daniel-watermelon",
                author = "Daniel",
                elapsedTime = "3 天前",
                body = "颜色很炸的时候，反而更要靠位置关系，不然主体一下就散了。",
                likes = 29,
                comments = 5,
                imageAssetKey = "figma-gallery-4",
                imageAspectRatio = 0.84f,
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
