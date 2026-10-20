package com.example.aicamera.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aicamera.R
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.data.db.CommunityComment
import com.example.aicamera.data.db.CommunityPost
import com.example.aicamera.filter.CustomFilterConfig
import com.example.aicamera.filter.CustomFilterStore
import com.example.aicamera.ui.auth.SystemBarsAppearance
import com.example.aicamera.ui.theme.BrandLike
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.GenWowMotion
import com.example.aicamera.viewmodel.CommunityViewModel
import kotlinx.coroutines.launch

/**
 * 社区动态推荐页 — 视觉移植自队友 frontend/ ui/feed/FeedScreen.kt。
 *
 * ## 结构
 * - 顶部三 Tab：关注 / 为你推荐 / 消息（滑动指示器）
 * - 「为你推荐」：单列动态卡片流（头像 / 名称 / 时间 / 大图 / 描述 / 点赞 / 评论）
 * - 「关注 / 消息」：空状态引导
 */
private val CommunityTabs = listOf("关注", "为你推荐", "消息")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    viewModel: CommunityViewModel,
    onNavigateToProfile: (String) -> Unit = {}
) {
    SystemBarsAppearance(light = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    val posts by viewModel.posts.collectAsState()
    val postsLoading by viewModel.postsLoading.collectAsState()
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val activeUser by authRepo.activeUser.collectAsState()
    val currentUser = activeUser?.username?.takeIf { it.isNotBlank() } ?: authRepo.displayUsername
    val followingUsers by remember(currentUser) {
        viewModel.getFollowing(currentUser)
    }.collectAsState(initial = emptyList())
    val followingPosts = remember(posts, followingUsers) {
        posts.filter { it.author in followingUsers }
    }

    var showComments by remember { mutableStateOf(false) }
    var selectedPostId by remember { mutableStateOf<Long?>(null) }
    var commentText by remember { mutableStateOf("") }
    // 点卡片打开作品详情弹层
    var selectedPost by remember { mutableStateOf<CommunityPost?>(null) }

    Box(Modifier.fillMaxSize()) {
        // Figma 原图背景（白-粉-紫）
        Image(
            painter = painterResource(R.drawable.bg_hub),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            CommunityTopTabs(selectedIndex = selectedTab, onSelected = { selectedTab = it })
        when (selectedTab) {
            0 -> if (postsLoading) {
                FeedLoadingState(modifier = Modifier.fillMaxWidth().weight(1f))
            } else if (followingUsers.isEmpty()) {
                FeedEmptyState(
                    title = "还没有关注任何人",
                    subtitle = "去发现页关注喜欢的摄影师",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else if (followingPosts.isEmpty()) {
                FeedEmptyState(
                    title = "关注的人还没有发布作品",
                    subtitle = "他们的新作品会自动出现在这里",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                // Figma「关注」：双列封面网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(followingPosts, key = { "following_grid_${it.id}" }) { post ->
                        FeedGridItem(
                            post = post,
                            onToggleLike = { viewModel.toggleLike(post.id, currentUser) },
                            onNavigateToProfile = onNavigateToProfile,
                            viewModel = viewModel,
                            currentUser = currentUser,
                            currentAvatarUri = activeUser?.avatarUri,
                            onPostClick = { selectedPost = post },
                        )
                    }
                }
            }
            1 -> if (postsLoading) {
                FeedLoadingState(modifier = Modifier.fillMaxWidth().weight(1f))
            } else if (posts.isEmpty()) {
                FeedEmptyState(modifier = Modifier.fillMaxWidth().weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(posts, key = { it.id }) { post ->
                        FeedPostItem(
                            post = post,
                            onToggleLike = { viewModel.toggleLike(post.id, currentUser) },
                            onCommentClick = { selectedPostId = post.id; showComments = true },
                            onNavigateToProfile = onNavigateToProfile,
                            viewModel = viewModel,
                            currentUser = currentUser,
                            currentAvatarUri = activeUser?.avatarUri,
                            onPostClick = { selectedPost = post },
                        )
                    }
                }
            }
            else -> FeedEmptyState(
                title = "暂无消息",
                subtitle = "互动消息会显示在这里",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (showComments && selectedPostId != null) {
            ModalBottomSheet(
                onDismissRequest = { showComments = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFFFFF1F5)
            ) {
                CommentSheet(
                    postId = selectedPostId!!,
                    viewModel = viewModel,
                    currentUser = currentUser
                )
            }
        }

        // 作品详情弹层（点信息流卡片打开）
        val detailPost = selectedPost
        if (detailPost != null) {
            PostDetailDialog(
                post = detailPost,
                viewModel = viewModel,
                currentUser = currentUser,
                currentAvatarUri = activeUser?.avatarUri,
                onDismiss = { selectedPost = null },
                onNavigateToProfile = onNavigateToProfile,
                communityAssetMapper = { key -> communityAssetResource(key ?: "") },
            )
        }
        }
    }
}

// ── 顶部 Tab（滑动指示器） ──────────────────────────────────────

@Composable
private fun CommunityTopTabs(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp)) {
        val tabWidth = maxWidth / CommunityTabs.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex + (tabWidth - 28.dp) / 2,
            animationSpec = tween(
                durationMillis = GenWowMotion.ModeChangeMillis,
                easing = GenWowMotion.StandardEasing,
            ),
            label = "community tab indicator",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommunityTabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelected(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) BrandOnSurface else BrandOnSurfaceMuted,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .size(width = 24.dp, height = 2.dp)
                .background(Color.Black, RoundedCornerShape(1.dp)),
        )
    }
}

// ── 动态条目（单列） ────────────────────────────────────────────

@Composable
private fun FeedPostItem(
    post: CommunityPost,
    onToggleLike: () -> Unit,
    onCommentClick: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    viewModel: CommunityViewModel,
    currentUser: String,
    currentAvatarUri: String?,
    onPostClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 整张卡片可点：进入作品详情（点赞/收藏/评论/作者跳转都在详情里）
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onPostClick)) {
        // 头部：头像 / 名称 / 时间 / 更多
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthorAvatar(
                author = post.author,
                currentUser = currentUser,
                currentAvatarUri = currentAvatarUri,
                contentDescription = "${post.author} 的头像",
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onNavigateToProfile(post.author) },
            )
            Column(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .clickable { onNavigateToProfile(post.author) },
            ) {
                Text(
                    text = post.author,
                    color = BrandOnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = post.elapsedText,
                    color = BrandOnSurfaceMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Figma：正方形大图，左右留白约 56dp（299/412）
        PostImage(
            assetKey = post.imageAssetKey,
            contentDescription = "${post.author} 的内容图片",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth(0.726f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)),
        )

        Spacer(Modifier.height(12.dp))

        // 描述
        val (title, description) = remember(post.body) { splitPostBody(post.body) }
        Text(
            text = title,
            color = BrandOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = BrandOnSurface.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))

        // 点赞 / 评论 / 收藏
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LikeAction(
                post = post,
                onToggleLike = onToggleLike,
                viewModel = viewModel,
                currentUser = currentUser,
            )
            CommentAction(
                commentCount = post.commentsCount,
                onClick = onCommentClick,
            )
            // Figma 动态卡片只有点赞/评论；收藏入口保留在帖子详情
        }
    }
}

/** Figma「关注」双列网格卡片：封面 + 标题 + 作者 + 点赞数。 */
@Composable
private fun FeedGridItem(
    post: CommunityPost,
    onToggleLike: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    viewModel: CommunityViewModel,
    currentUser: String,
    currentAvatarUri: String?,
    onPostClick: () -> Unit,
) {
    val liked by remember(post.id, currentUser) {
        viewModel.observeIsLiked(post.id, currentUser)
    }.collectAsState(initial = false)
    val (title, _) = remember(post.body) { splitPostBody(post.body) }

    // Figma 卡片：透明填充/透明边框，背景图直接透出
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPostClick),
    ) {
        PostImage(
            assetKey = post.imageAssetKey,
            contentDescription = "${post.author} 的内容图片",
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
        )
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(
                text = title,
                color = BrandOnSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorAvatar(
                    author = post.author,
                    currentUser = currentUser,
                    currentAvatarUri = currentAvatarUri,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onNavigateToProfile(post.author) },
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    text = post.author,
                    color = BrandOnSurfaceMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Image(
                    painter = painterResource(R.drawable.ic_feed_heart),
                    contentDescription = null,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable(role = Role.Button, onClick = onToggleLike),
                    colorFilter = ColorFilter.tint(if (liked) BrandLike else BrandOnSurfaceMuted),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "${post.likes}",
                    color = if (liked) BrandLike else BrandOnSurfaceMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun PostImage(
    assetKey: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (assetKey != null && (assetKey.startsWith("content:") || assetKey.startsWith("file:"))) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(assetKey)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.gallery_flowers),
            error = painterResource(R.drawable.gallery_flowers),
            fallback = painterResource(R.drawable.gallery_flowers),
        )
    } else {
        Image(
            painter = painterResource(communityAssetResource(assetKey ?: "gallery_flowers")),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

internal fun splitPostBody(body: String): Pair<String, String> {
    val separator = " | "
    return if (separator in body) {
        val idx = body.indexOf(separator)
        body.substring(0, idx) to body.substring(idx + separator.length)
    } else {
        body to ""
    }
}

// ── 空状态 ──────────────────────────────────────────────────────

@Composable
private fun FeedLoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BrandPink)
    }
}

@Composable
private fun FeedEmptyState(
    modifier: Modifier = Modifier,
    title: String = "还没有内容更新",
    subtitle: String = "等你发布第一条图文后，这里会展示统一尺寸的社区卡片。",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .background(Color.White.copy(alpha = 0.62f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.78f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_feed_comment),
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                colorFilter = ColorFilter.tint(BrandOnSurface.copy(alpha = 0.76f)),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            color = BrandOnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = BrandOnSurfaceMuted,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
    }
}

// ── 互动 Action ─────────────────────────────────────────────────

@Composable
private fun LikeAction(
    post: CommunityPost,
    onToggleLike: () -> Unit,
    viewModel: CommunityViewModel,
    currentUser: String,
) {
    val interaction = remember(post.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .92f else 1f,
        animationSpec = tween(if (pressed) GenWowMotion.PressMillis else GenWowMotion.PressReleaseMillis),
        label = "like press",
    )
    val liked by remember(post.id, currentUser) {
        viewModel.observeIsLiked(post.id, currentUser)
    }.collectAsState(initial = false)
    Row(
        modifier = Modifier
            .scale(scale)
            .semantics { contentDescription = "点赞 ${post.author}" }
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = {
                    onToggleLike()
                },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_feed_heart),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (liked) BrandLike else BrandOnSurface),
        )
        Spacer(Modifier.size(5.dp))
        Text(
            "${post.likes} 赞",
            color = if (liked) BrandPink else BrandOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CommentAction(
    commentCount: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_feed_comment),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(BrandOnSurface),
        )
        Spacer(Modifier.size(5.dp))
        Text(
            text = "$commentCount 评论",
            color = BrandOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── 素材映射 ────────────────────────────────────────────────────

@DrawableRes
internal fun communityAssetResource(assetKey: String): Int = when (assetKey) {
    "community-cafe-portrait" -> R.drawable.community_cafe_portrait
    "community-blue-city" -> R.drawable.community_blue_city
    "community-forest-macro" -> R.drawable.community_forest_macro
    "community-rainy-street" -> R.drawable.community_rainy_street
    "figma-feed-tulips" -> R.drawable.feed_tulips
    "figma-gallery-1" -> R.drawable.gallery_flowers
    "figma-gallery-2" -> R.drawable.gallery_mushrooms
    "figma-gallery-3" -> R.drawable.gallery_desserts
    "figma-gallery-4" -> R.drawable.gallery_watermelon
    "figma-gallery-5" -> R.drawable.gallery_street
    "figma-gallery-6" -> R.drawable.gallery_architecture
    else -> R.drawable.gallery_flowers
}

@DrawableRes
internal fun authorAvatar(author: String): Int = when (author) {
    "Helena" -> R.drawable.avatar_helena
    "Daniel" -> R.drawable.avatar_daniel
    else -> R.drawable.avatar_helena
}

/**
 * 作者头像：当前登录用户优先显示个人资料里的真实头像，其他作者仍用内置头像。
 * 内置头像始终垫底，URI 失效时不会出现白块。
 */
@Composable
internal fun AuthorAvatar(
    author: String,
    currentUser: String,
    currentAvatarUri: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val ownAvatar = currentAvatarUri?.takeIf { it.isNotBlank() && author == currentUser }
    Box(modifier = modifier.clip(CircleShape)) {
        Image(
            painter = painterResource(authorAvatar(author)),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (ownAvatar != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(Uri.parse(ownAvatar))
                    .crossfade(true)
                    .size(144)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ── 评论底部抽屉 ──────────────────────────────────────────────────

@Composable
private fun CommentSheet(
    postId: Long,
    viewModel: CommunityViewModel,
    currentUser: String,
) {
    val comments by viewModel.getComments(postId).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val filterStore = remember { CustomFilterStore(context) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("评论", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = BrandOnSurface)
        Spacer(Modifier.height(12.dp))
        if (comments.isEmpty()) {
            Text("暂无评论，来说两句吧", color = BrandOnSurfaceMuted, fontSize = 13.sp)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(comments, key = { it.id }) { comment ->
                    CommentItem(comment, filterStore)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        filterStore.current()?.let { custom ->
            Text(
                text = "🎨 附加我的滤镜「${custom.name}」",
                color = BrandPink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { input = custom.communityText() }.padding(vertical = 6.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("写评论...", fontSize = 13.sp) },
                textStyle = LocalTextStyle.current.copy(color = Color(0xFF3D3D3D), fontSize = 13.sp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandPink,
                    unfocusedBorderColor = BrandOnSurfaceMuted.copy(alpha = 0.3f)
                )
            )
            Spacer(Modifier.size(8.dp))
            val canSend = input.isNotBlank() && !isSending
            Text(
                text = if (isSending) "发送中..." else "发送",
                color = if (canSend) BrandPink else BrandOnSurfaceMuted,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(enabled = canSend) {
                        isSending = true
                        scope.launch {
                            val ok = viewModel.addComment(postId, currentUser, input.trim())
                            isSending = false
                            if (ok) {
                                input = ""
                            } else {
                                Toast.makeText(context, "评论发送失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            )
        }
    }
}

@Composable
internal fun CommentItem(comment: CommunityComment, filterStore: CustomFilterStore) {
    val context = LocalContext.current
    val sharedFilter = remember(comment.body) { CustomFilterConfig.fromText(comment.body) }
    if (sharedFilter != null) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 7.dp)
                .background(Color.White.copy(alpha = .72f), RoundedCornerShape(14.dp)).padding(12.dp)
        ) {
            Text("${comment.author} 分享了滤镜", color = BrandOnSurfaceMuted, fontSize = 11.sp)
            Text(sharedFilter.name, color = BrandOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("曝光 ${sharedFilter.exposure} · 对比 ${sharedFilter.contrast.toInt()} · 饱和 ${sharedFilter.saturation.toInt()}",
                color = BrandOnSurfaceMuted, fontSize = 11.sp)
            Text("一键导入到我的相机", color = BrandPink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    filterStore.save(sharedFilter)
                    Toast.makeText(context, "已导入滤镜：${sharedFilter.name}", Toast.LENGTH_SHORT).show()
                }.padding(top = 8.dp))
        }
    } else {
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(comment.author, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = BrandOnSurface)
            Spacer(Modifier.size(8.dp))
            Text(comment.body, fontSize = 13.sp, color = BrandOnSurface.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun FeaturedFilterResourceCard() {
    val context = LocalContext.current
    val store = remember { CustomFilterStore(context) }
    val filter = CustomFilterConfig.COMMUNITY_SAMPLE
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = .76f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(R.drawable.community_rainy_street), "雨后电影感滤镜示例",
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("本周滤镜资源", color = BrandPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(filter.name, color = BrandOnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("冷调蓝影 · 压高光 · 轻锐化", color = BrandOnSurfaceMuted, fontSize = 11.sp)
        }
        Text("导入", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(BrandPink, RoundedCornerShape(999.dp)).clickable {
                store.save(filter)
                Toast.makeText(context, "滤镜已导入，打开相机即可使用", Toast.LENGTH_SHORT).show()
            }.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}
