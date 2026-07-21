package com.genwow.camera.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.data.demo.DemoGalleryItem
import com.genwow.camera.data.demo.DemoProfile
import com.genwow.camera.ui.feed.communityAssetResource
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.GenWowMotion

@Composable
fun ProfileAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 108.dp, height = 92.dp)
            .border(BorderStroke(1.4.dp, BrandOnSurface.copy(alpha = .45f)), RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(
                BrandOnSurface,
                radius = 6.dp.toPx(),
                center = Offset(size.width / 2f, 10.dp.toPx()),
                style = stroke,
            )
            drawArc(
                BrandOnSurface,
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(5.dp.toPx(), 17.dp.toPx()),
                size = Size(24.dp.toPx(), 17.dp.toPx()),
                style = stroke,
            )
        }
    }
}

@Composable
fun ProfileStats(profile: DemoProfile, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        ProfileStat(profile.works, "作品")
        ProfileStat(profile.followers, "粉丝", Modifier.padding(horizontal = 32.dp))
        ProfileStat(profile.following, "关注")
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
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 7.dp, bottom = 2.dp),
        )
    }
}

@Composable
fun ProfileGallery(items: List<DemoGalleryItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                rowItems.forEach { item ->
                    GalleryCell(item = item, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(item: DemoGalleryItem, modifier: Modifier = Modifier) {
    val interaction = remember(item.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .96f else 1f,
        tween(if (pressed) GenWowMotion.PressMillis else GenWowMotion.PressReleaseMillis),
        label = "gallery press",
    )
    Image(
        painter = painterResource(communityAssetResource(item.assetKey)),
        contentDescription = item.contentDescription,
        modifier = modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(7.dp))
            .semantics { contentDescription = item.contentDescription }
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = {},
            ),
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun ProfileSettingsButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "主页设置" }
            .clickable(role = Role.Button, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(27.dp)) {
            val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(BrandOnSurface, 5.dp.toPx(), center, style = stroke)
            drawCircle(BrandOnSurface, 9.dp.toPx(), center, style = stroke)
            repeat(8) { index ->
                val angle = Math.toRadians(index * 45.0)
                val inner = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * 8.dp.toPx(),
                    center.y + kotlin.math.sin(angle).toFloat() * 8.dp.toPx(),
                )
                val outer = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * 11.dp.toPx(),
                    center.y + kotlin.math.sin(angle).toFloat() * 11.dp.toPx(),
                )
                drawLine(BrandOnSurface, inner, outer, stroke.width, StrokeCap.Round)
            }
        }
    }
}
