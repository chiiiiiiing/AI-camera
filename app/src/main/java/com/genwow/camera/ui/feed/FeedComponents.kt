package com.genwow.camera.ui.feed

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.R
import com.genwow.camera.data.demo.DemoPost
import com.genwow.camera.ui.icon.CommentOutlineIcon
import com.genwow.camera.ui.icon.HeartOutlineIcon
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.BrandPink
import com.genwow.camera.ui.theme.GenWowMotion

private val CommunityTabs = listOf("关注", "为你推荐", "消息")

@Composable
fun CommunityTopTabs(
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
                .offset { androidx.compose.ui.unit.IntOffset(indicatorOffset.roundToPx(), 0) }
                .size(width = 28.dp, height = 2.dp)
                .background(BrandOnSurface, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
fun FeedPostCard(
    post: DemoPost,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember(post.id) { mutableStateOf(false) }
    LaunchedEffect(post.id) { visible = true }
    val imageAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(180),
        label = "feed image fade",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.95f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.78f),
                shape = RoundedCornerShape(24.dp),
            ),
    ) {
        Image(
            painter = painterResource(communityAssetResource(post.imageAssetKey)),
            contentDescription = "${post.author} 的内容图片",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.62f)
                .graphicsLayer { alpha = imageAlpha },
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.94f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = post.body,
                color = BrandOnSurface,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.heightIn(min = 9.dp).weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(authorAvatar(post.author)),
                    contentDescription = "${post.author} 的头像",
                    modifier = Modifier
                        .size(25.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = post.author,
                        color = BrandOnSurface,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = post.elapsedTime,
                        color = BrandOnSurfaceMuted,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommentPill(commentCount = post.comments)
                    LikePill(post = post, onToggleLike = onToggleLike)
                }
            }
        }
    }
}

@Composable
fun FeedEmptyState(
    modifier: Modifier = Modifier,
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
            CommentOutlineIcon(
                modifier = Modifier.size(38.dp),
                tint = BrandOnSurface.copy(alpha = 0.76f),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "还没有内容更新",
            color = BrandOnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "等你发布第一条图文后，这里会展示统一尺寸的社区卡片。",
            color = BrandOnSurfaceMuted,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            fontStyle = FontStyle.Normal,
        )
    }
}

@Composable
private fun LikePill(
    post: DemoPost,
    onToggleLike: (String) -> Unit,
) {
    val interaction = remember(post.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .92f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) {
                GenWowMotion.PressMillis
            } else {
                GenWowMotion.PressReleaseMillis
            },
        ),
        label = "like press",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFFFF3F7))
            .scale(scale)
            .semantics { contentDescription = "点赞 ${post.author}" }
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = { onToggleLike(post.id) },
            )
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeartOutlineIcon(
            modifier = Modifier.size(16.dp),
            tint = BrandOnSurface,
            filled = post.liked,
            fillColor = BrandPink,
        )
        Spacer(Modifier.size(5.dp))
        Text("${post.likes}", color = BrandOnSurface, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CommentPill(commentCount: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.78f))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommentOutlineIcon(
            modifier = Modifier.size(15.dp),
            tint = BrandOnSurface,
        )
        Spacer(Modifier.size(5.dp))
        Text(
            text = "$commentCount",
            color = BrandOnSurface,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@DrawableRes
internal fun communityAssetResource(assetKey: String): Int = when (assetKey) {
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
private fun authorAvatar(author: String): Int = when (author) {
    "Helena" -> R.drawable.avatar_helena
    "Daniel" -> R.drawable.avatar_daniel
    else -> R.drawable.avatar_helena
}
