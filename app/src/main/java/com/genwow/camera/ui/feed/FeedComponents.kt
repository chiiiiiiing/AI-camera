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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.R
import com.genwow.camera.data.demo.DemoPost
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
                .offset(x = indicatorOffset)
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
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.72f),
                shape = RoundedCornerShape(22.dp),
            ),
    ) {
        Image(
            painter = painterResource(communityAssetResource(post.imageAssetKey)),
            contentDescription = "${post.author} 的内容图片",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.55f)
                .graphicsLayer { alpha = imageAlpha },
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Text(
                text = post.body,
                color = BrandOnSurface,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.heightIn(min = 10.dp).weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(authorAvatar(post.author)),
                    contentDescription = "${post.author} 的头像",
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = post.author,
                        color = BrandOnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = post.elapsedTime,
                        color = BrandOnSurfaceMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                LikePill(post = post, onToggleLike = onToggleLike)
            }
        }
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeartOutlineIcon(
            modifier = Modifier.size(17.dp),
            tint = BrandOnSurface,
            filled = post.liked,
            fillColor = BrandPink,
        )
        Spacer(Modifier.size(6.dp))
        Text("${post.likes}", color = BrandOnSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
