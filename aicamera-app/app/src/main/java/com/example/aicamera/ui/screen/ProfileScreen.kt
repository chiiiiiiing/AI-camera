package com.example.aicamera.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aicamera.data.AuthRepository

/**
 * 个人主页 — 参考推特/X 设计。
 *
 * ## 布局
 * - 封面图（可更换）
 * - 头像（可上传）+ 编辑按钮
 * - 用户名 + @handle + 简介
 * - 统计数据（作品 / 粉丝 / 关注）
 * - Tab 栏（作品 / 点赞 / 收藏）
 * - 作品网格流
 * - 设置 + 退出登录
 */
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToEditProfile: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val username = authRepo.currentUsername ?: "摄影师"
    val bio = authRepo.currentBio ?: "用 AI 构图，记录每一帧美好"

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("作品" to Icons.Outlined.GridView, "点赞" to Icons.Outlined.FavoriteBorder, "收藏" to Icons.Outlined.BookmarkBorder)

    // ★ 头像选择
    var avatarUri by remember { mutableStateOf<Uri?>(authRepo.currentAvatarUri?.let { Uri.parse(it) }) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            avatarUri = uri
            authRepo.saveAvatarUri(uri.toString())
        }
    }

    // ★ 封面选择
    var coverColor by remember { mutableIntStateOf(0xFF6C2FB0.toInt()) }

    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            // ── 封面区域 ──
            Box(
                modifier = Modifier.fillMaxWidth().height(140.dp)
                    .background(Color(coverColor))
                    .clickable { /* TODO: 更换封面 */ },
                contentAlignment = Alignment.BottomEnd
            ) {
                // 右上角操作按钮
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Settings, "设置", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showLogoutDialog = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, "退出", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                }

                // 头像（跨封面和内容区）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 20.dp, y = 32.dp)
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(avatarUri).crossfade(true).size(160).build(),
                            contentDescription = "头像", modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Filled.Person, "上传头像", tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
                    }
                    // 相机角标
                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape)
                            .background(Color(0xFF8B5FBF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // ── 用户信息 ──
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(username, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    // 编辑资料按钮
                    TextButton(
                        onClick = { onNavigateToEditProfile?.invoke() },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8B5FBF))
                    ) {
                        Text("编辑资料", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text("@${username.lowercase()}", color = Color.Gray, fontSize = 13.sp)

                Spacer(modifier = Modifier.height(8.dp))
                Text(bio, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)

                // ── 统计行 ──
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatItem("0", "作品")
                    StatItem("0", "粉丝")
                    StatItem("0", "关注")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Tab 栏 ──
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0D0D0D),
                contentColor = Color(0xFF8B5FBF),
                divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.06f)) }
            ) {
                tabs.forEachIndexed { index, (label, icon) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
                        text = { Text(label, fontSize = 13.sp) }
                    )
                }
            }

            // ── 作品网格 ──
            PhotoGrid()
        }
    }

    // ── 退出确认 ──
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？") },
            confirmButton = {
                TextButton(onClick = {
                    authRepo.logout()
                    showLogoutDialog = false
                    onLogout?.invoke()
                }) { Text("确定", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }
}

// ── 统计项 ───────────────────────────────────────────────────────

@Composable
private fun StatItem(number: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = Color.Gray, fontSize = 13.sp)
    }
}

// ── 作品网格 ─────────────────────────────────────────────────────

@Composable
private fun PhotoGrid() {
    val colors = listOf(
        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF2196F3),
        Color(0xFF9C27B0), Color(0xFFE91E63), Color(0xFF00BCD4)
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().height(400.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        userScrollEnabled = false
    ) {
        items(12) { index ->
            Box(
                modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
                    .background(colors[index % colors.size].copy(alpha = 0.3f))
                    .clickable { /* TODO: 作品详情 */ },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CameraAlt, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
            }
        }
    }
}
