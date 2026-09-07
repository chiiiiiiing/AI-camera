package com.example.aicamera.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.auth.SystemBarsAppearance
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.viewmodel.CommunityViewModel

/**
 * 粉丝 / 关注列表页。
 *
 * @param listType "followers" 或 "following"
 * @param username 要查看的用户
 * @param currentUser 当前登录用户（判断能否关注）
 * @param onBack 返回回调
 * @param onNavigateToProfile 点击用户进入其主页
 */
@Composable
fun UserListScreen(
    listType: String,
    username: String,
    currentUser: String,
    onBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    viewModel: CommunityViewModel
) {
    SystemBarsAppearance(light = true)
    val title = if (listType == "followers") "粉丝" else "关注"
    val usersFlow = remember(username, listType) {
        if (listType == "followers") viewModel.getFollowers(username)
        else viewModel.getFollowing(username)
    }
    val users by usersFlow.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFE7F3), Color(0xFFF6E6F8), Color(0xFFD8CCF5))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "← 返回",
                color = BrandOnSurfaceMuted,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$title (${users.size})",
                color = BrandOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(40.dp))
        }
        Spacer(Modifier.height(18.dp))

        if (users.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有$title",
                    color = BrandOnSurfaceMuted,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(users, key = { it }) { name ->
                    UserListItem(
                        username = name,
                        currentUser = currentUser,
                        onClick = { onNavigateToProfile(name) },
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun UserListItem(
    username: String,
    currentUser: String,
    onClick: () -> Unit,
    viewModel: CommunityViewModel
) {
    val isFollowing by remember(username, currentUser) {
        viewModel.observeIsFollowing(currentUser, username)
    }.collectAsState(initial = false)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(authorAvatar(username)),
            contentDescription = "$username 的头像",
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = username,
            color = BrandOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (username != currentUser) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isFollowing) Color.Transparent else BrandPink.copy(alpha = 0.12f))
                    .clickable {
                        viewModel.toggleFollow(currentUser, username)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isFollowing) "已关注" else "+ 关注",
                    color = if (isFollowing) BrandOnSurfaceMuted else BrandPink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
