package com.example.aicamera.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.data.CommunityRepository
import com.example.aicamera.ui.auth.AuthField
import com.example.aicamera.ui.auth.AuthFieldIcon
import com.example.aicamera.ui.auth.BrandPrimaryButton
import com.example.aicamera.ui.auth.LightSystemBars
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPurple

/**
 * 编辑资料页 — 视觉移植自队友 frontend/ ui/profile/EditProfileScreen.kt。
 *
 * 功能：头像更换、昵称、简介；保存时写入昵称 + 简介 + 头像 Uri 后返回。
 * 昵称 2–20 字符校验，不合法时提示并阻止保存。
 */
private val EditProfileBackground = Brush.verticalGradient(
    listOf(
        Color(0xFFFFE7F3),
        Color(0xFFF6E6F8),
        Color(0xFFE3DAFF),
        Color(0xFFFFFFFF),
    ),
)

@Composable
fun EditProfileScreen(
    onBack: () -> Unit
) {
    LightSystemBars()
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val communityRepo = remember { CommunityRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    // 游客也有真实 users 行（AuthRepository 会补建），这里用统一显示名初始化。
    var username by remember { mutableStateOf(authRepo.displayUsername) }
    var bio by remember { mutableStateOf(authRepo.currentBio ?: "") }
    var avatarUri by remember { mutableStateOf<Uri?>(authRepo.currentAvatarUri?.let { Uri.parse(it) }) }
    var avatarPersisting by remember { mutableStateOf(false) }
    var profileSeeded by remember { mutableStateOf(false) }
    val activeUser by authRepo.activeUser.collectAsState()

    // 冷启动恢复会话是异步的；首次拿到真实用户行时以数据库为准，
    // 避免页面停留在 displayUsername 的默认昵称/旧头像。
    LaunchedEffect(activeUser?.accountKey) {
        val user = activeUser ?: return@LaunchedEffect
        if (!profileSeeded) {
            if (user.username.isNotBlank()) username = user.username
            if (user.bio.isNotBlank()) bio = user.bio
            if (user.avatarUri.isNotBlank()) avatarUri = Uri.parse(user.avatarUri)
            profileSeeded = true
        } else {
            if (username.isBlank()) username = user.username
            if (bio.isBlank()) bio = user.bio
            if (avatarUri == null && user.avatarUri.isNotBlank()) avatarUri = Uri.parse(user.avatarUri)
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // content:// 临时授权在重启后失效，先复制到 App 私有目录再保存。
            avatarPersisting = true
            scope.launch {
                try {
                    val persisted = authRepo.persistAvatar(uri)
                    if (persisted != null) {
                        avatarUri = Uri.parse(persisted)
                    } else {
                        Toast.makeText(context, "头像读取失败，请重新选择", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    avatarPersisting = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditProfileBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "返回",
                color = BrandOnSurfaceMuted,
                fontSize = 14.sp,
                modifier = Modifier
                    .semantics {
                        contentDescription = "返回个人主页"
                        role = Role.Button
                    }
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "编辑资料",
                color = BrandOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(40.dp))
        }

        Spacer(Modifier.height(22.dp))
        ProfileAvatar(
            avatarUri = avatarUri,
            onClick = { avatarPicker.launch("image/*") },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "点击更换头像",
            color = BrandPurple,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { avatarPicker.launch("image/*") },
        )
        Spacer(Modifier.height(28.dp))

        EditSection(title = "基础信息") {
            AuthField(
                value = username,
                onValueChange = { username = it },
                placeholder = "输入昵称",
                contentDescription = "昵称输入框",
                icon = AuthFieldIcon.PERSON,
            )
            AuthField(
                value = bio,
                onValueChange = { bio = it },
                placeholder = "输入个人简介",
                contentDescription = "简介输入框",
                icon = AuthFieldIcon.SHIELD,
                imeAction = ImeAction.Next,
            )
        }

        Spacer(Modifier.height(26.dp))
        BrandPrimaryButton(
            label = if (avatarPersisting) "正在保存头像…" else "保存资料",
            enabled = !avatarPersisting,
            onClick = {
                val trimmed = username.trim()
                if (trimmed.length !in 2..20) {
                    Toast.makeText(context, "昵称需要 2-20 个字符", Toast.LENGTH_SHORT).show()
                } else {
                    // 先用统一显示名迁移社区侧引用（帖子/评论/点赞/收藏/关注），
                    // 再等待 Room 写入个人资料；两步都完成后才返回，避免页面读到旧昵称。
                    val oldName = authRepo.displayUsername
                    scope.launch {
                        try {
                            if (!oldName.isNullOrBlank() && oldName != trimmed) {
                                communityRepo.renameAuthor(oldName, trimmed)
                            }
                            authRepo.updateProfile(trimmed, bio, avatarUri?.toString())
                            onBack()
                        } catch (e: Exception) {
                            Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun EditSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = {
            Text(
                text = title,
                color = BrandOnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        },
    )
}
