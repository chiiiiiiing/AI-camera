package com.genwow.camera.ui.feed

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.R
import com.genwow.camera.data.demo.DemoPost
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
            animationSpec = tween(GenWowMotion.ModeChangeMillis, easing = GenWowMotion.StandardEasing),
            label = "community tab indicator",
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommunityTabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    if (selected) BrandOnSurface else BrandOnSurfaceMuted,
                    tween(GenWowMotion.ModeChangeMillis),
                    label = "community tab color",
                )
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
                        color = color,
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
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(authorAvatar(post.author)),
                contentDescription = "${post.author} 的头像",
                modifier = Modifier.size(32.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(post.author, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(post.elapsedTime, color = Color(0xFF9A909E), fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "•••",
                color = Color.Black,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.semantics { contentDescription = "${post.author} 的更多操作" },
            )
        }

        Column(Modifier.padding(start = 44.dp, end = 36.dp, top = 12.dp)) {
            post.imageAssetKey?.let { assetKey ->
                var visible by remember(post.id) { mutableStateOf(false) }
                LaunchedEffect(post.id) { visible = true }
                val imageAlpha by animateFloatAsState(
                    if (visible) 1f else 0f,
                    tween(180),
                    label = "feed image fade",
                )
                Image(
                    painter = painterResource(communityAssetResource(assetKey)),
                    contentDescription = "${post.author} 的郁金香照片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .graphicsLayer { alpha = imageAlpha },
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = post.body,
                color = Color.Black,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            PostActions(post = post, onToggleLike = onToggleLike)
        }
    }
}

@Composable
private fun PostActions(post: DemoPost, onToggleLike: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        val interaction = remember(post.id) { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) .88f else 1f,
            tween(if (pressed) GenWowMotion.PressMillis else GenWowMotion.PressReleaseMillis),
            label = "like press",
        )
        Row(
            modifier = Modifier
                .scale(scale)
                .semantics { contentDescription = "点赞 ${post.author}" }
                .clickable(
                    role = Role.Button,
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onToggleLike(post.id) },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeartIcon(filled = post.liked)
            Text("${post.likes} 赞", color = Color.Black, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
        }
        Row(
            modifier = Modifier
                .semantics { contentDescription = "评论 ${post.author}" }
                .clickable(role = Role.Button, onClick = {})
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommentIcon()
            Text("${post.comments} 评论", color = Color.Black, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

@Composable
private fun HeartIcon(filled: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(size.width * .5f, size.height * .86f)
            cubicTo(size.width * .42f, size.height * .76f, size.width * .1f, size.height * .55f, size.width * .1f, size.height * .3f)
            cubicTo(size.width * .1f, size.height * .08f, size.width * .38f, size.height * .02f, size.width * .5f, size.height * .22f)
            cubicTo(size.width * .62f, size.height * .02f, size.width * .9f, size.height * .08f, size.width * .9f, size.height * .3f)
            cubicTo(size.width * .9f, size.height * .55f, size.width * .58f, size.height * .76f, size.width * .5f, size.height * .86f)
            close()
        }
        if (filled) drawPath(path, BrandPink)
        drawPath(path, Color.Black, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun CommentIcon() {
    Canvas(Modifier.size(20.dp)) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(
            Color.Black,
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(16.dp.toPx(), 13.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = stroke,
        )
        drawLine(
            Color.Black,
            Offset(6.dp.toPx(), 15.dp.toPx()),
            Offset(5.dp.toPx(), 19.dp.toPx()),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            Color.Black,
            Offset(5.dp.toPx(), 19.dp.toPx()),
            Offset(10.dp.toPx(), 15.dp.toPx()),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
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
