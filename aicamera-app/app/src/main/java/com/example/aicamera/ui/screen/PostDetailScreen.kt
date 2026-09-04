package com.example.aicamera.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.aicamera.data.db.CommunityPost
import com.example.aicamera.filter.CustomFilterStore
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.viewmodel.CommunityViewModel
import kotlinx.coroutines.flow.map

@Composable
fun PostDetailDialog(
    post: CommunityPost,
    viewModel: CommunityViewModel,
    currentUser: String,
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
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }  // ★ 防重复发送
    val context = LocalContext.current
    val filterStore = remember(context) { CustomFilterStore(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFFF1F5)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("← 返回", color = BrandOnSurface, fontSize = 14.sp,
                        modifier = Modifier.clickable(onClick = onDismiss))
                    Spacer(Modifier.weight(1f))
                    Text(post.author, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = BrandOnSurface)
                    Spacer(Modifier.size(8.dp))
                    // ★ 关注按钮
                    Text(
                        text = if (isFollowing) "已关注" else "+ 关注",
                        color = if (isFollowing) BrandOnSurfaceMuted else BrandPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isFollowing) Color.Transparent else BrandPink.copy(alpha = 0.12f))
                            .clickable {
                                viewModel.toggleFollow(currentUser, post.author)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Image(
                            painter = painterResource(communityAssetMapper(post.imageAssetKey)),
                            contentDescription = post.body,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            contentScale = ContentScale.Crop
                        )
                    }
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onNavigateToProfile(post.author) }
                            ) {
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape)
                                        .background(BrandPink.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(post.author.take(1), color = BrandPink,
                                        fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.size(8.dp))
                                Text(post.author, fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp, color = BrandOnSurface)
                                Spacer(Modifier.weight(1f))
                                Text(post.elapsedText, fontSize = 12.sp,
                                    color = BrandOnSurfaceMuted)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(post.body, fontSize = 14.sp,
                                color = BrandOnSurface.copy(alpha = 0.85f), lineHeight = 20.sp)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("赞 ${post.likes}", fontSize = 13.sp, color = BrandPink)
                                Spacer(Modifier.size(16.dp))
                                // ★ 收藏切换
                                Text(
                                    text = if (isFavorited) "已收藏" else "收藏",
                                    fontSize = 13.sp,
                                    color = if (isFavorited) BrandPink else BrandOnSurfaceMuted,
                                    modifier = Modifier.clickable {
                                        viewModel.toggleFavorite(post.id, currentUser)
                                    }
                                )
                                Spacer(Modifier.size(16.dp))
                                Text("评论 ${post.commentsCount}", fontSize = 13.sp,
                                    color = BrandOnSurfaceMuted)
                            }
                        }
                    }
                    item {
                        Text("评论 (${post.commentsCount})",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            color = BrandOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    if (comments.isEmpty()) {
                        item {
                            // ★ 修复：commentsCount > 0 但评论未加载时不显示「暂无评论」
                            val hint = if (post.commentsCount > 0) "加载评论中..."
                            else "暂无评论，来说两句吧"
                            Text(hint, fontSize = 13.sp,
                                color = BrandOnSurfaceMuted,
                                modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    } else {
                        items(comments, key = { it.id }) { c ->
                            Box(Modifier.padding(horizontal = 16.dp)) { CommentItem(c, filterStore) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    filterStore.current()?.let { custom ->
                        Text(
                            "🎨",
                            fontSize = 20.sp,
                            modifier = Modifier.padding(end = 8.dp).clickable {
                                input = custom.communityText()
                            }
                        )
                    }
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("写评论...", fontSize = 13.sp) },
                        textStyle = LocalTextStyle.current.copy(
                            color = Color(0xFF3D3D3D), fontSize = 13.sp),
                        singleLine = true, shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPink,
                            unfocusedBorderColor = BrandOnSurfaceMuted.copy(alpha = 0.3f))
                    )
                    Spacer(Modifier.size(8.dp))
                    // ★ 防重复发送：isSending 门控 + 空输入禁止
                    val canSend = input.isNotBlank() && !isSending
                    Text(
                        text = if (isSending) "发送中..." else "发送",
                        color = if (canSend) BrandPink else BrandOnSurfaceMuted,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clickable(enabled = canSend) {
                            isSending = true
                            viewModel.addComment(post.id, currentUser, input.trim())
                            input = ""
                            isSending = false
                        })
                }
            }
        }
    }
}
