package com.genwow.camera.ui.camera

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.ui.theme.BrandPink
import com.genwow.camera.ui.theme.CameraChrome
import com.genwow.camera.ui.theme.CameraControlMuted
import com.genwow.camera.ui.theme.GenWowMotion
import com.genwow.camera.ui.theme.GuideAligned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CameraTopControls(debugDemo: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CameraChrome)
            .statusBarsPadding()
            .height(40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TopModeControl("咖啡构图模式", selected = true, glyph = TopGlyph.COFFEE)
            TopModeControl("风景构图模式", selected = false, glyph = TopGlyph.LANDSCAPE)
            TopModeControl("人像构图模式", selected = false, glyph = TopGlyph.PERSON)
        }
        Spacer(Modifier.weight(1f))
        if (debugDemo) {
            Box(Modifier.size(5.dp).background(BrandPink, CircleShape))
            Spacer(Modifier.size(8.dp))
        }
        TopModeControl("相机设置", selected = false, glyph = TopGlyph.SETTINGS)
    }
}

private enum class TopGlyph { COFFEE, LANDSCAPE, PERSON, SETTINGS }

@Composable
private fun TopModeControl(description: String, selected: Boolean, glyph: TopGlyph) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(Color.Transparent, CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val color = if (selected) BrandPink else Color.White.copy(alpha = 0.9f)
            drawCircle(color.copy(alpha = 0.9f), style = Stroke(1.dp.toPx()))
            val stroke = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
            when (glyph) {
                TopGlyph.COFFEE -> {
                    drawRoundRect(color, Offset(7.dp.toPx(), 9.dp.toPx()), Size(8.dp.toPx(), 6.dp.toPx()), CornerRadius(1.dp.toPx()), style = stroke)
                    drawArc(color, -80f, 160f, false, Offset(13.dp.toPx(), 10.dp.toPx()), Size(5.dp.toPx(), 4.dp.toPx()), style = stroke)
                    drawLine(color, Offset(7.dp.toPx(), 17.dp.toPx()), Offset(16.dp.toPx(), 17.dp.toPx()), strokeWidth = stroke.width)
                }
                TopGlyph.LANDSCAPE -> {
                    val path = Path().apply {
                        moveTo(5.dp.toPx(), 16.dp.toPx()); lineTo(10.dp.toPx(), 10.dp.toPx()); lineTo(13.dp.toPx(), 13.dp.toPx()); lineTo(17.dp.toPx(), 8.dp.toPx()); lineTo(20.dp.toPx(), 16.dp.toPx())
                    }
                    drawPath(path, color, style = stroke)
                    drawCircle(color, 1.5.dp.toPx(), Offset(7.dp.toPx(), 7.dp.toPx()), style = stroke)
                }
                TopGlyph.PERSON -> {
                    drawCircle(color, 2.2.dp.toPx(), Offset(12.dp.toPx(), 8.dp.toPx()), style = stroke)
                    drawArc(color, 200f, 140f, false, Offset(7.dp.toPx(), 11.dp.toPx()), Size(10.dp.toPx(), 8.dp.toPx()), style = stroke)
                }
                TopGlyph.SETTINGS -> {
                    drawCircle(color, 3.dp.toPx(), center, style = stroke)
                    repeat(8) { index ->
                        val angle = Math.toRadians((index * 45).toDouble())
                        drawLine(color, Offset(center.x + kotlin.math.cos(angle).toFloat() * 5.dp.toPx(), center.y + kotlin.math.sin(angle).toFloat() * 5.dp.toPx()), Offset(center.x + kotlin.math.cos(angle).toFloat() * 7.dp.toPx(), center.y + kotlin.math.sin(angle).toFloat() * 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraBottomControls(
    state: CameraUiState,
    cameraReady: Boolean,
    recentPhotoUri: Uri?,
    onCapture: () -> Unit,
    onSwitchLens: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .background(CameraChrome)
            .navigationBarsPadding()
            .padding(horizontal = 30.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(82.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GalleryThumbnail(recentPhotoUri)
            CaptureButton(
                enabled = cameraReady && state.capturePhase != CapturePhase.CAPTURING,
                aligned = state.guidance.phase == GuidancePhase.ALIGNED,
                onClick = onCapture,
            )
            CameraIconButton("切换前后摄像头", onSwitchLens) { CameraSwitchGlyph() }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            BottomDestinationIcon("打开相机", selected = true, BottomGlyph.CAMERA, onClick = {})
            BottomDestinationIcon("打开推荐", selected = false, BottomGlyph.SHARE, onClick = onOpenFeed)
            BottomDestinationIcon("打开个人主页", selected = false, BottomGlyph.PERSON, onClick = onOpenProfile)
        }
    }
}

@Composable
fun CaptureButton(enabled: Boolean, aligned: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, tween(GenWowMotion.PressMillis), label = "capture press")
    val outer by animateColorAsState(if (aligned) GuideAligned else Color.White, label = "capture ring")
    Box(
        modifier = Modifier.size(78.dp).scale(scale).semantics { contentDescription = "拍摄照片" }.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(72.dp)) {
            drawCircle(outer.copy(alpha = if (enabled) 1f else 0.42f), style = Stroke(3.dp.toPx()))
            drawCircle(BrandPink.copy(alpha = if (enabled) 1f else 0.48f), radius = 27.dp.toPx())
        }
    }
}

@Composable
private fun GalleryThumbnail(uri: Uri?) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = uri?.let { photoUri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(photoUri)?.use { stream ->
                        BitmapFactory.decodeStream(
                            stream,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = 4 },
                        )
                    }
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(48.dp)
            .background(Color(0xFF273C2C), RoundedCornerShape(5.dp))
            .semantics { contentDescription = if (uri == null) "最近照片" else "最近拍摄照片" },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(5.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Canvas(Modifier.size(42.dp)) {
                drawRect(Color(0xFF315A3B))
                repeat(5) { index ->
                    drawLine(Color(0xFF6B9A55), Offset((5 + index * 8).dp.toPx(), size.height), Offset((8 + index * 7).dp.toPx(), (10 + index % 2 * 5).dp.toPx()), 1.2.dp.toPx())
                    drawCircle(if (index % 2 == 0) Color(0xFFE45C45) else Color(0xFFF09A4E), 3.dp.toPx(), Offset((8 + index * 7).dp.toPx(), (9 + index % 2 * 5).dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun CameraIconButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = description }.clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CameraSwitchGlyph() {
    Canvas(Modifier.size(28.dp)) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        drawArc(CameraControlMuted, 205f, 220f, false, Offset(3.dp.toPx(), 3.dp.toPx()), Size(22.dp.toPx(), 22.dp.toPx()), style = stroke)
        drawLine(CameraControlMuted, Offset(3.dp.toPx(), 13.dp.toPx()), Offset(3.dp.toPx(), 7.dp.toPx()), stroke.width, StrokeCap.Round)
        drawLine(CameraControlMuted, Offset(3.dp.toPx(), 7.dp.toPx()), Offset(8.dp.toPx(), 7.5.dp.toPx()), stroke.width, StrokeCap.Round)
        drawLine(CameraControlMuted, Offset(25.dp.toPx(), 15.dp.toPx()), Offset(25.dp.toPx(), 21.dp.toPx()), stroke.width, StrokeCap.Round)
        drawLine(CameraControlMuted, Offset(25.dp.toPx(), 21.dp.toPx()), Offset(20.dp.toPx(), 20.5.dp.toPx()), stroke.width, StrokeCap.Round)
    }
}

private enum class BottomGlyph { CAMERA, SHARE, PERSON }

@Composable
private fun BottomDestinationIcon(description: String, selected: Boolean, glyph: BottomGlyph, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = description }.clickable(role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(25.dp)) {
            val color = if (selected) BrandPink else Color.White
            val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round)
            when (glyph) {
                BottomGlyph.CAMERA -> {
                    drawRoundRect(color, Offset(3.dp.toPx(), 7.dp.toPx()), Size(19.dp.toPx(), 14.dp.toPx()), CornerRadius(2.dp.toPx()), style = stroke)
                    drawCircle(color, 4.dp.toPx(), Offset(12.5.dp.toPx(), 14.dp.toPx()), style = stroke)
                    drawLine(color, Offset(8.dp.toPx(), 7.dp.toPx()), Offset(10.dp.toPx(), 4.dp.toPx()), stroke.width)
                    drawLine(color, Offset(10.dp.toPx(), 4.dp.toPx()), Offset(15.dp.toPx(), 4.dp.toPx()), stroke.width)
                }
                BottomGlyph.SHARE -> {
                    drawCircle(color, 2.5.dp.toPx(), Offset(5.dp.toPx(), 13.dp.toPx()), style = stroke)
                    drawCircle(color, 2.5.dp.toPx(), Offset(19.dp.toPx(), 6.dp.toPx()), style = stroke)
                    drawCircle(color, 2.5.dp.toPx(), Offset(19.dp.toPx(), 20.dp.toPx()), style = stroke)
                    drawLine(color, Offset(7.2.dp.toPx(), 12.dp.toPx()), Offset(16.7.dp.toPx(), 7.dp.toPx()), stroke.width)
                    drawLine(color, Offset(7.2.dp.toPx(), 14.dp.toPx()), Offset(16.7.dp.toPx(), 19.dp.toPx()), stroke.width)
                }
                BottomGlyph.PERSON -> {
                    drawCircle(color, 4.dp.toPx(), Offset(12.5.dp.toPx(), 7.dp.toPx()), style = stroke)
                    drawArc(color, 195f, 150f, false, Offset(4.dp.toPx(), 12.dp.toPx()), Size(17.dp.toPx(), 12.dp.toPx()), style = stroke)
                }
            }
        }
    }
}

@Composable
fun ModeSelector() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("智能", "人像", "风景").forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Text(
                text = label,
                color = if (selected) Color.White else Color.White.copy(alpha = .56f),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier
                    .background(if (selected) Color.White.copy(alpha = .13f) else Color.Transparent, RoundedCornerShape(13.dp))
                    .semantics { this.selected = selected }
                    .selectable(selected = selected, role = Role.Tab, onClick = { selectedIndex = index })
                    .padding(horizontal = 13.dp, vertical = 6.dp),
            )
        }
    }
}
