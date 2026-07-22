package com.genwow.camera.ui.profile

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
import androidx.compose.ui.graphics.Brush
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
import com.genwow.camera.ui.icon.SettingsOutlineIcon
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.GenWowMotion

@Composable
fun ProfileAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 108.dp, height = 92.dp)
            .border(BorderStroke(1.4.dp, BrandOnSurface.copy(alpha = .24f)), RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) {
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
fun EditProfileButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .97f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) GenWowMotion.PressMillis else GenWowMotion.PressReleaseMillis,
        ),
        label = "edit profile press",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .border(
                BorderStroke(1.dp, BrandOnSurface.copy(alpha = 0.14f)),
                RoundedCornerShape(999.dp),
            )
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.76f))
            .scale(scale)
            .semantics { contentDescription = "编辑资料" }
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "编辑资料",
            color = BrandOnSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
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
fun ProfileSettingsButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "主页设置" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SettingsOutlineIcon(modifier = Modifier.size(27.dp), tint = BrandOnSurface)
    }
}
