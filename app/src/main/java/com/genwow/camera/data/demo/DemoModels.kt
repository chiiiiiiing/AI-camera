package com.genwow.camera.data.demo

import androidx.compose.runtime.Immutable

@Immutable
data class DemoAuthSession(
    val userId: String,
    val displayName: String,
)

@Immutable
data class DemoPost(
    val id: String,
    val author: String,
    val elapsedTime: String,
    val body: String,
    val likes: Int,
    val comments: Int,
    val imageAssetKey: String? = null,
    val liked: Boolean = false,
)

@Immutable
data class DemoGalleryItem(
    val id: String,
    val assetKey: String,
    val contentDescription: String,
)

@Immutable
data class DemoProfile(
    val displayName: String,
    val bio: String,
    val works: Int,
    val followers: Int,
    val following: Int,
    val galleryItems: List<DemoGalleryItem>,
)
