package com.example.aicamera.ui.screen

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.aicamera.data.db.CommunityPost
import com.example.aicamera.filter.CustomFilterStore
import com.example.aicamera.ui.theme.BrandLike
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.BrandPurple
import com.example.aicamera.viewmodel.CommunityViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Figma 帖子详情：全屏大图 + 作者栏 + 评论 + 底部操作条。 */
@Composable
fun PostDetailDialog(
    post: CommunityPost,
    viewModel: CommunityViewModel,
    currentUser: String,
    currentAvatarUri: String?,
    onDismiss: () -> Unit,
    onNavigateToProfile: (String) -> Unit = {},
    communityAssetMapper: (String?) -> Int,
) {
    val comments by viewModel.getComments(post.id).collectAsState(initial = emptyList())
    val isFavorited by remember(post.id, currentUser) {
        viewModel.getFavoritedPosts(currentUser).map { list -> list.any { it.id == post.id } }
    }.collectAsState(initial = false)
    val isFollowing by remember(post.author, currentUser) {
        viewModel.getFollowing(currentUser).map { list -> list.contains(post.author) }
    }.collectAsState(initial = false)
    val isLiked by remember(post.id, currentUser) {
        viewModel.observeIsLiked(post.id, currentUser)
    }.collectAsState(initial = false)
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var commentsLoading by remember(post.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val filterStore = remember(context) { CustomFilterStore(context) }

    LaunchedEffect(post.id) {
        delay(500)
        commentsLoading = false
    }

    val (title, description) = remember(post.body) { splitPostBody(post.body) }

    fun sendComment() {
        val canSend = input.isNotBlank() && !isSending
        if (!canSend) return
        isSending = true
        scope.launch {
            val ok = viewModel.addComment(post.id, currentUser, input.trim())
            isSending = false
            if (ok) input = "" else Toast.makeText(context, "评论发送失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Figma 原图背景
            Image(
                painter = painterResource(com.example.aicamera.R.drawable.bg_hub),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.fillMaxSize()) {
                // ── 顶栏：返回 / 作者 / 关注 / 分享 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = BrandOnSurface,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(onClick = onDismiss)
                            .padding(8.dp),
                    )
                    AuthorAvatar(
                        author = post.author,
                        currentUser = currentUser,
                        currentAvatarUri = currentAvatarUri,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { onNavigateToProfile(post.author) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = post.author,
                        color = BrandOnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToProfile(post.author) },
                    )
                    Text(
                        text = if (isFollowing) "已关注" else "关注",
                        color = if (isFollowing) BrandOnSurfaceMuted else BrandLike,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = if (isFollowing) BrandOnSurfaceMuted.copy(alpha = .35f) else BrandLike,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { viewModel.toggleFollow(currentUser, post.author) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Image(
                        painter = painterResource(com.example.aicamera.R.drawable.ic_post_share),
                        contentDescription = "分享",
                        modifier = Modifier.size(22.dp),
                    )
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        // 用户作品可能是 content:// / file://，必须走 PostImage 而不是 drawable 映射
                        PostImage(
                            assetKey = post.imageAssetKey,
                            contentDescription = title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.82f),
                        )
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = title,
                                color = BrandOnSurface,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = post.elapsedText,
                                color = BrandOnSurfaceMuted,
                                fontSize = 12.sp,
                            )
                            if (description.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = description,
                                    color = BrandOnSurface.copy(alpha = .86f),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = if (comments.isEmpty()) "还没有评论" else "共 ${comments.size} 条评论",
                                color = BrandOnSurfaceMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (comments.isEmpty()) {
                        item {
                            Text(
                                text = if (commentsLoading) "加载评论中..." else "暂无评论，来说两句吧",
                                color = BrandOnSurfaceMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        items(comments, key = { it.id }) { c ->
                            Box(Modifier.padding(horizontal = 16.dp)) { CommentItem(c, filterStore) }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }

                // ── 底部操作条：输入 / 点赞 / 收藏 / 评论 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说点什么...", fontSize = 13.sp) },
                        textStyle = LocalTextStyle.current.copy(color = Color(0xFF3D3D3D), fontSize = 13.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPink,
                            unfocusedBorderColor = BrandOnSurfaceMuted.copy(alpha = 0.3f),
                            focusedContainerColor = Color.White.copy(alpha = .72f),
                            unfocusedContainerColor = Color.White.copy(alpha = .72f),
                        ),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (isSending) "…" else "发送",
                        color = if (input.isNotBlank() && !isSending) BrandPink else BrandOnSurfaceMuted,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(enabled = input.isNotBlank() && !isSending) { sendComment() },
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.toggleLike(post.id, currentUser) },
                    ) {
                        Image(
                            painter = painterResource(com.example.aicamera.R.drawable.ic_feed_heart),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(if (isLiked) BrandLike else BrandOnSurface),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "${post.likes}",
                            color = if (isLiked) BrandLike else BrandOnSurface,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Image(
                        painter = painterResource(com.example.aicamera.R.drawable.ic_post_star),
                        contentDescription = "收藏",
                        alpha = if (isFavorited) 1f else 0.55f,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { viewModel.toggleFavorite(post.id, currentUser) },
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(com.example.aicamera.R.drawable.ic_feed_comment),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(BrandOnSurface),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("${comments.size}", color = BrandOnSurface, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
