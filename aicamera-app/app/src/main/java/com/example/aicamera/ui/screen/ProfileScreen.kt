package com.example.aicamera.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.data.db.CommunityComment
import com.example.aicamera.data.db.CommunityPost
import com.example.aicamera.ui.auth.SystemBarsAppearance
import com.example.aicamera.ui.components.HeartOutlineIcon
import com.example.aicamera.ui.components.SettingsOutlineIcon
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.BrandPurple
import com.example.aicamera.ui.theme.GenWowMotion
import com.example.aicamera.viewmodel.CommunityViewModel
import kotlinx.coroutines.flow.flowOf

/**
 * 个人主页 — 视觉移植自队友 frontend/ ui/profile/ProfileScreen.kt + ProfileComponents.kt。
 *
 * ## 布局（对齐设计稿 个人主页-1~4.png）
 * - 粉紫渐变背景 + 左侧圆形头像 + 昵称/简介 + 右上角设置齿轮
 * - 统计行（作品 / 粉丝 / 关注）
 * - 四 Tab：作品 / 点赞 / 收藏 / 评论（下划线指示器）
 * - 作品/点赞/收藏：2 列卡片（图片 + 标题 + 作者 + 点赞数）
 * - 评论：评论列表
 */
private val ProfileBackground = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFE8F4),
        Color(0xFFF5E3F8),
        Color(0xFFD8D0FF),
        Color(0xFFFFFFFF),
    ),
)

/** Profile Tab 枚举 */
private enum class ProfileTab(val label: String) {
    WORKS("作品"),
    LIKES("点赞"),
    FAVORITES("收藏"),
    COMMENTS("评论")
}

@Composable
fun ProfileScreen(
    profileUsername: String? = null,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToEditProfile: (() -> Unit)? = null,
    onNavigateToFollowers: (String) -> Unit = {},
    onNavigateToFollowing: (String) -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onLogout: (() -> Unit)? = null,
    communityViewModel: CommunityViewModel? = null
) {
    SystemBarsAppearance(light = true)
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val currentUser = authRepo.currentUsername ?: "摄影师"
    val isCurrentUser = profileUsername == null || profileUsername == currentUser
    val username = profileUsername ?: currentUser
    val bio = if (isCurrentUser) {
        authRepo.currentBio ?: "用 AI 构图，记录每一帧美好"
    } else {
        when (username) {
            "Helena" -> "AI 构图助手 Helena | 热爱光影与咖啡"
            "Daniel" -> "AI 构图助手 Daniel | 城市与风景观察者"
            else -> "AI 构图爱好者"
        }
    }

    var selectedProfileTab by remember { mutableStateOf(ProfileTab.WORKS) }

    val postsFlow = remember(username, communityViewModel) {
        communityViewModel?.getPostsByAuthor(username) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val userPosts by postsFlow.collectAsState(initial = emptyList())

    val likedFlow = remember(username, communityViewModel) {
        communityViewModel?.getLikedPosts(username) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val likedPosts by likedFlow.collectAsState(initial = emptyList())

    val favoritesFlow = remember(username, communityViewModel) {
        communityViewModel?.getFavoritedPosts(username) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val favoritedPosts by favoritesFlow.collectAsState(initial = emptyList())

    val commentsFlow = remember(username, communityViewModel) {
        communityViewModel?.getCommentsByAuthor(username) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val userComments by commentsFlow.collectAsState(initial = emptyList())

    // 头像选择（仅当前用户）
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(isCurrentUser) {
        avatarUri = if (isCurrentUser) authRepo.currentAvatarUri?.let { Uri.parse(it) } else null
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            avatarUri = uri
            authRepo.saveAvatarUri(uri.toString())
        }
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val headerProgress by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(GenWowMotion.PageEnterMillis, easing = GenWowMotion.StandardEasing),
        label = "profile header enter",
    )
    val galleryProgress by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(300, delayMillis = 70, easing = GenWowMotion.StandardEasing),
        label = "profile gallery enter",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ProfileBackground)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .graphicsLayer {
                    alpha = headerProgress
                    translationY = (1f - headerProgress) * 16.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(26.dp))

            // 头部：头像 + 昵称/简介
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatarCircle(
                    avatarUri = avatarUri,
                    fallbackAvatar = authorAvatar(username),
                    onClick = { if (isCurrentUser) avatarPicker.launch("image/*") },
                    modifier = Modifier.size(78.dp),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = username,
                        color = BrandOnSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = bio,
                        color = BrandOnSurfaceMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // 统计行
            val followerCount by remember(username, communityViewModel) {
                communityViewModel?.followerCount(username) ?: flowOf(0)
            }.collectAsState(initial = 0)
            val followingCount by remember(username, communityViewModel) {
                communityViewModel?.followingCount(username) ?: flowOf(0)
            }.collectAsState(initial = 0)

            ProfileStatsRow(
                postCount = userPosts.size,
                followerCount = followerCount,
                followingCount = followingCount,
                onFollowersClick = { onNavigateToFollowers(username) },
                onFollowingClick = { onNavigateToFollowing(username) },
            )

            Spacer(Modifier.height(16.dp))

            // 当前用户：编辑资料；他人：关注/已关注
            if (isCurrentUser) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.72f))
                        .clickable { onNavigateToEditProfile?.invoke() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "编辑资料",
                        color = BrandOnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                FollowButton(
                    currentUser = currentUser,
                    targetUser = username,
                    viewModel = communityViewModel
                )
            }

            Spacer(Modifier.height(18.dp))

            // Tab 选择器
            ProfileTabBar(
                selected = selectedProfileTab,
                onSelected = { selectedProfileTab = it }
            )

            Spacer(Modifier.height(18.dp))

            // Tab 内容
            Box(modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = galleryProgress
                    translationY = (1f - galleryProgress) * 14.dp.toPx()
                }
            ) {
                when (selectedProfileTab) {
                    ProfileTab.WORKS -> {
                        if (userPosts.isEmpty()) {
                            ProfileEmptyGallery()
                        } else {
                            ProfilePostGrid(
                                posts = userPosts,
                                currentUser = currentUser,
                                viewModel = communityViewModel,
                                onNavigateToProfile = onNavigateToProfile,
                            )
                        }
                    }
                    ProfileTab.LIKES -> {
                        if (likedPosts.isEmpty()) {
                            ProfileEmptyState(
                                title = "还没有点赞",
                                subtitle = "去社区发现喜欢的作品吧"
                            )
                        } else {
                            ProfilePostGrid(
                                posts = likedPosts,
                                currentUser = currentUser,
                                viewModel = communityViewModel,
                                onNavigateToProfile = onNavigateToProfile,
                            )
                        }
                    }
                    ProfileTab.FAVORITES -> {
                        if (favoritedPosts.isEmpty()) {
                            ProfileEmptyState(
                                title = "还没有收藏",
                                subtitle = "收藏的作品会显示在这里"
                            )
                        } else {
                            ProfilePostGrid(
                                posts = favoritedPosts,
                                currentUser = currentUser,
                                viewModel = communityViewModel,
                                onNavigateToProfile = onNavigateToProfile,
                            )
                        }
                    }
                    ProfileTab.COMMENTS -> {
                        if (userComments.isEmpty()) {
                            ProfileEmptyState(
                                title = "还没有评论",
                                subtitle = "你的评论历史会显示在这里"
                            )
                        } else {
                            ProfileCommentList(comments = userComments)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (isCurrentUser) {
            ProfileSettingsButton(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 18.dp),
                onClick = onNavigateToSettings,
            )
        }
    }
}

// ── 圆形头像 ────────────────────────────────────────────────────

@Composable
private fun ProfileAvatarCircle(
    avatarUri: Uri?,
    fallbackAvatar: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(1.5.dp, BrandOnSurface.copy(alpha = 0.24f), CircleShape)
            .background(Color.White.copy(alpha = 0.72f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUri)
                    .crossfade(true)
                    .size(216)
                    .build(),
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                painter = painterResource(fallbackAvatar),
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// ── 统计行 ──────────────────────────────────────────────────────

@Composable
private fun ProfileStatsRow(
    postCount: Int,
    followerCount: Int,
    followingCount: Int,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        ProfileStat(postCount, "作品")
        ProfileStat(
            followerCount, "粉丝",
            Modifier
                .padding(horizontal = 40.dp)
                .clickable(onClick = onFollowersClick)
        )
        ProfileStat(
            followingCount, "关注",
            Modifier.clickable(onClick = onFollowingClick)
        )
    }
}

@Composable
private fun FollowButton(
    currentUser: String,
    targetUser: String,
    viewModel: CommunityViewModel?,
) {
    val isFollowing by remember(targetUser, currentUser, viewModel) {
        viewModel?.observeIsFollowing(currentUser, targetUser) ?: flowOf(false)
    }.collectAsState(initial = false)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFollowing) Color.White.copy(alpha = 0.72f) else BrandPink)
            .clickable {
                viewModel?.toggleFollow(currentUser, targetUser)
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isFollowing) "已关注" else "+ 关注",
            color = if (isFollowing) BrandOnSurface else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ProfileStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "$value $label" },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = value.toString(),
            color = BrandOnSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = BrandOnSurfaceMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 5.dp, bottom = 2.dp),
        )
    }
}

// ── Tab 选择器（下划线） ────────────────────────────────────────

@Composable
private fun ProfileTabBar(
    selected: ProfileTab,
    onSelected: (ProfileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp)) {
        val tabWidth = maxWidth / ProfileTab.entries.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selected.ordinal + (tabWidth - 24.dp) / 2,
            animationSpec = tween(GenWowMotion.ModeChangeMillis, easing = GenWowMotion.StandardEasing),
            label = "profile tab indicator",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ProfileTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelected(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) BrandOnSurface else BrandOnSurfaceMuted,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 4.dp)
                .offset { androidx.compose.ui.unit.IntOffset(indicatorOffset.roundToPx(), 0) }
                .size(width = 24.dp, height = 2.dp)
                .background(BrandOnSurface, RoundedCornerShape(1.dp)),
        )
    }
}

// ── 作品/点赞/收藏 2 列卡片网格 ─────────────────────────────────

@Composable
private fun ProfilePostGrid(
    posts: List<CommunityPost>,
    currentUser: String,
    viewModel: CommunityViewModel?,
    onNavigateToProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        posts.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowItems.forEach { post ->
                    ProfilePostCard(
                        post = post,
                        currentUser = currentUser,
                        viewModel = viewModel,
                        onNavigateToProfile = onNavigateToProfile,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfilePostCard(
    post: CommunityPost,
    currentUser: String,
    viewModel: CommunityViewModel?,
    onNavigateToProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember(post.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .97f else 1f,
        tween(if (pressed) GenWowMotion.PressMillis else GenWowMotion.PressReleaseMillis),
        label = "profile card press",
    )
    val liked by remember(post.id, currentUser, viewModel) {
        viewModel?.observeIsLiked(post.id, currentUser) ?: flowOf(false)
    }.collectAsState(initial = false)

    val (title, _) = remember(post.body) { splitPostBody(post.body) }

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.72f))
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = {},
            )
            .padding(8.dp),
    ) {
        PostImage(
            assetKey = post.imageAssetKey,
            contentDescription = title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.95f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = BrandOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(authorAvatar(post.author)),
                contentDescription = "${post.author} 的头像",
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { onNavigateToProfile(post.author) },
                contentScale = ContentScale.Crop,
            )
            Text(
                text = post.author,
                color = BrandOnSurfaceMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    viewModel?.toggleLike(post.id, currentUser)
                }
            ) {
                HeartOutlineIcon(
                    modifier = Modifier.size(14.dp),
                    tint = if (liked) BrandPink else BrandOnSurfaceMuted,
                    filled = liked,
                    fillColor = BrandPink,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "${post.likes}",
                    color = if (liked) BrandPink else BrandOnSurfaceMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── 评论列表 ────────────────────────────────────────────────────

@Composable
private fun ProfileCommentList(comments: List<CommunityComment>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        comments.forEach { comment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Image(
                    painter = painterResource(authorAvatar(comment.author)),
                    contentDescription = "${comment.author} 的头像",
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = comment.author,
                        color = BrandOnSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = comment.body,
                        color = BrandOnSurface.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

// ── 设置齿轮按钮 ────────────────────────────────────────────────

@Composable
private fun ProfileSettingsButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = "主页设置"
                role = Role.Button
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SettingsOutlineIcon(modifier = Modifier.size(27.dp), tint = BrandOnSurface)
    }
}

// ── 空状态 ──────────────────────────────────────────────────────

@Composable
private fun ProfileEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = BrandOnSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = BrandOnSurfaceMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ProfileEmptyGallery(modifier: Modifier = Modifier) {
    ProfileEmptyState(
        title = "还没有作品",
        subtitle = "用 AI 构图拍下你的第一张照片吧",
        modifier = modifier,
    )
}

// ── 旧版头像卡片（保留，供 EditProfileScreen 复用） ─────────────

@Composable
internal fun ProfileAvatar(
    avatarUri: Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 108.dp, height = 92.dp)
            .clip(RoundedCornerShape(32.dp))
            .border(
                BorderStroke(1.4.dp, BrandOnSurface.copy(alpha = .24f)),
                RoundedCornerShape(32.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUri)
                    .crossfade(true)
                    .size(216)
                    .build(),
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.90f),
                            Color(0xFFFFEAF5),
                            Color(0xFFE8DEFF),
                        ),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(32.dp.toPx(), 32.dp.toPx()),
                )
            }
            Canvas(Modifier.size(42.dp)) {
                val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(
                    BrandOnSurface,
                    radius = 7.dp.toPx(),
                    center = Offset(size.width / 2f, 12.dp.toPx()),
                    style = stroke,
                )
                drawArc(
                    BrandOnSurface,
                    startAngle = 198f,
                    sweepAngle = 144f,
                    useCenter = false,
                    topLeft = Offset(5.dp.toPx(), 20.dp.toPx()),
                    size = Size(32.dp.toPx(), 19.dp.toPx()),
                    style = stroke,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(BrandPurple),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp)) {
                val stroke = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Color.White, 3.dp.toPx(), center, style = stroke)
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(1.5.dp.toPx(), 4.dp.toPx()),
                    size = Size(11.dp.toPx(), 7.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    style = stroke,
                )
                drawLine(Color.White, Offset(5.dp.toPx(), 4.dp.toPx()), Offset(5.5.dp.toPx(), 2.8.dp.toPx()), stroke.width)
            }
        }
    }
}
