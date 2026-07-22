package com.example.aicamera.ui.screen

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * App 内照片浏览器 — 查看用本 App 拍摄的所有照片。
 *
 * 从 MediaStore 查询 `AiCamera_` 前缀的照片，网格展示。
 * 点击某张进入大图预览模式。
 */
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ★ 从 MediaStore 加载照片列表（IO 线程，避免阻塞主线程）
    LaunchedEffect(Unit) {
        photos = withContext(Dispatchers.IO) {
            queryAiCameraPhotos(context)
        }
    }

    if (selectedPhoto != null) {
        // ── 大图预览模式 ──
        FullScreenPhotoView(
            photo = selectedPhoto!!,
            onBack = { selectedPhoto = null },
            onDelete = {
                showDeleteConfirm = true
            }
        )

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("删除照片") },
                text = { Text("确定要删除这张照片吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        deletePhoto(context, selectedPhoto!!.uri)
                        photos = queryAiCameraPhotos(context)
                        selectedPhoto = null
                        showDeleteConfirm = false
                    }) {
                        Text("删除", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
    } else {
        // ── 网格浏览模式 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    text = "我的作品 (${photos.size})",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (photos.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "还没有拍摄照片",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 15.sp
                        )
                        Text(
                            "回到相机页拍一张吧",
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(photos) { photo ->
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(photo.uri)
                                .crossfade(true)
                                .size(256)
                                .build(),
                            contentDescription = "照片",
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { selectedPhoto = photo },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

/** 大图预览 */
@Composable
private fun FullScreenPhotoView(
    photo: PhotoItem,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(photo.uri)
                .crossfade(true)
                .build(),
            contentDescription = "照片预览",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // 顶部返回 + 删除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ── 数据模型与工具函数 ──────────────────────────────────────────

data class PhotoItem(
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long
)

/** 从 MediaStore 查询 AICamera_ 前缀的照片（限 50 张，避免全量加载） */
private fun queryAiCameraPhotos(context: android.content.Context): List<PhotoItem> {
    val photos = mutableListOf<PhotoItem>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT 50"

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null, null,
        sortOrder
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

        while (cursor.moveToNext()) {
            val displayName = cursor.getString(nameCol) ?: continue
            if (!displayName.startsWith("AiCamera_")) continue

            val id = cursor.getLong(idCol)
            val uri = Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id.toString()
            )
            val dateTaken = cursor.getLong(dateCol)
            photos.add(PhotoItem(uri, displayName, dateTaken))
        }
    }
    return photos
}

/** 删除照片 */
private fun deletePhoto(context: android.content.Context, uri: Uri) {
    try {
        context.contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        android.util.Log.w("GalleryScreen", "删除照片失败: ${e.message}")
    }
}
