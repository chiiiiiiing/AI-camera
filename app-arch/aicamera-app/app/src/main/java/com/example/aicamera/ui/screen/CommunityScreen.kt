package com.example.aicamera.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 社区动态推荐页
 *
 * 顶部三文字 Tab：关注 / 为你推荐 / 消息
 * 中间动态卡片流（用户头像 + 作品图 + 互动数据）
 */
@Composable
fun CommunityScreen() {
    var selectedTab by remember { mutableStateOf(1) } // 默认"为你推荐"
    val tabs = listOf("关注", "为你推荐", "消息")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        // ── 顶部 Tab 导航 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEachIndexed { index, title ->
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = if (index == selectedTab) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == selectedTab) Color(0xFF8B5FBF) else Color.Gray,
                    modifier = Modifier
                        .clickable { selectedTab = index }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        // ── 动态卡片流 ──
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 动态1：Helena
            item {
                FeedCard(
                    username = "Helena",
                    timeAgo = "3 分钟前",
                    description = "",
                    likes = 21,
                    comments = 4,
                    imageColor = Color(0xFF2E7D32) // 绿色占位(郁金香)
                )
            }

            // 动态2：Daniel
            item {
                FeedCard(
                    username = "Daniel",
                    timeAgo = "12 分钟前",
                    description = "落日时分的光影正好",
                    likes = 47,
                    comments = 9,
                    imageColor = Color(0xFFE65100) // 橙色占位
                )
            }

            // 动态3：Luna
            item {
                FeedCard(
                    username = "Luna",
                    timeAgo = "28 分钟前",
                    description = "用帧好拍的第一张构图",
                    likes = 89,
                    comments = 15,
                    imageColor = Color(0xFF6A1B9A) // 紫色占位
                )
            }
        }
    }
}

/**
 * 单条动态卡片
 */
@Composable
private fun FeedCard(
    username: String,
    timeAgo: String,
    description: String,
    likes: Int,
    comments: Int,
    imageColor: Color
) {
    Column {
        // ── 发布者信息栏 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(imageColor.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = username.first().toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(username, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(timeAgo, color = Color.Gray, fontSize = 11.sp)
            }

            // 三点菜单
            Text("⋯", color = Color.Gray, fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── 作品大图占位 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(imageColor.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = "作品",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 互动数据栏 ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                Text(" $likes 赞", color = Color.Gray, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                Text(" $comments 评论", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}
