package com.example.aicamera.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 社区动态推荐页 — 参考推特/朋友圈设计。
 *
 * ## 结构
 * - 顶部三 Tab：关注 / 为你推荐 / 消息
 * - 中间 Feed 卡片流：头像 + 作品图 + 互动行 + 评论预览
 * - 下拉刷新 (占位) + 上拉加载
 * - 空状态 / 骨架屏
 */
@Composable
fun CommunityScreen() {
    var selectedTab by remember { mutableIntStateOf(1) }
    val tabs = listOf("关注", "为你推荐", "消息")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        // ── 顶部 Tab ──
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF0D0D0D),
            contentColor = Color(0xFF8B5FBF),
            divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.06f)) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title, fontSize = 15.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Color(0xFF8B5FBF) else Color.Gray
                        )
                    }
                )
            }
        }

        // ── 动态卡片流 ──
        when (selectedTab) {
            0 -> EmptyStateView("还没有关注任何人", "去发现页看看")
            1 -> FeedList()
            2 -> EmptyStateView("暂无消息", "互动消息会显示在这里")
        }
    }
}

// ── Feed 列表 ───────────────────────────────────────────────────

@Composable
private fun FeedList() {
    // ★ 真实用户作品流——暂无数据，显示空状态引导
    val feeds = remember { emptyList<FeedData>() }

    if (feeds.isEmpty()) {
        EmptyStateView(
            title = "还没有作品",
            subtitle = "去拍照并发布你的第一张作品吧"
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(feeds) { feed ->
                FeedCard(feed = feed)
            }
        }
    }
}

// ── 单条动态卡片 ─────────────────────────────────────────────────

@Composable
private fun FeedCard(feed: FeedData) {
    var isLiked by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // ── 用户信息行 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 头像
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(Color(feed.avatarColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(feed.username.first().toString(), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(feed.username, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(feed.timeAgo, color = Color.Gray, fontSize = 12.sp)
                }
                IconButton(onClick = { /* 更多操作 */ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreHoriz, "更多", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 作品图占位 ──
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(feed.avatarColor).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CameraAlt, "作品", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(56.dp))
                if (feed.description.isNotBlank()) {
                    Text(
                        feed.description, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .padding(start = 0.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 互动栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 点赞
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isLiked = !isLiked }) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "点赞", tint = if (isLiked) Color(0xFFE91E63) else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${feed.likes + if (isLiked) 1 else 0}", color = Color.Gray, fontSize = 13.sp)
                }
                // 评论
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showComments = !showComments }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, "评论", tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${feed.comments}", color = Color.Gray, fontSize = 13.sp)
                }
                // 分享
                IconButton(onClick = { /* TODO: 分享 */ }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Share, "分享", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
                // 收藏
                IconButton(onClick = { /* TODO: 收藏 */ }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, "收藏", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }

            // ── 评论预览 ──
            AnimatedVisibility(visible = showComments && feed.commentPreviews.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    feed.commentPreviews.forEach { comment ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(comment.author, color = Color(0xFF8B5FBF), fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(comment.text, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // 查看全部评论
                    TextButton(onClick = { /* TODO: 打开评论详情 */ },
                        modifier = Modifier.height(28.dp), contentPadding = PaddingValues(0.dp)) {
                        Text("查看全部 ${feed.comments} 条评论 →", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ── 空状态 ──────────────────────────────────────────────────────

@Composable
private fun EmptyStateView(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Explore, contentDescription = null,
                tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
        }
    }
}

// ── 数据模型 ─────────────────────────────────────────────────────

private data class FeedData(
    val username: String,
    val timeAgo: String,
    val description: String,
    val likes: Int,
    val comments: Int,
    val avatarColor: Int,
    val commentPreviews: List<CommentData> = emptyList()
)

private data class CommentData(
    val author: String,
    val text: String
)
