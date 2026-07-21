package com.genwow.camera.ui.camera

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.camera.CameraZoomState
import com.genwow.camera.ui.theme.BrandPink
import com.genwow.camera.ui.theme.CameraChrome
import com.genwow.camera.ui.theme.CameraControlMuted
import com.genwow.camera.ui.theme.GenWowMotion
import com.genwow.camera.ui.theme.GuideAligned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CameraGuideMode(
    val description: String,
    internal val glyph: TopGlyph,
) {
    COFFEE("静物构图模式", TopGlyph.COFFEE),
    LANDSCAPE("风景构图模式", TopGlyph.LANDSCAPE),
    PORTRAIT("人像构图模式", TopGlyph.PERSON),
}

@Composable
fun CameraTopControls(
    selectedMode: CameraGuideMode,
    debugDemo: Boolean,
    onModeSelected: (CameraGuideMode) -> Unit,
    onOpenEntryFlow: () -> Unit,
) {
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
            CameraGuideMode.entries.forEach { mode ->
                TopModeControl(
                    description = mode.description,
                    selected = selectedMode == mode,
                    glyph = mode.glyph,
                    onClick = { onModeSelected(mode) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (debugDemo) {
            Box(Modifier.size(5.dp).background(BrandPink, CircleShape))
            Spacer(Modifier.size(8.dp))
        }
        TopModeControl(
            description = "相机设置",
            selected = false,
            glyph = TopGlyph.SETTINGS,
            onClick = onOpenEntryFlow,
        )
    }
}

internal enum class TopGlyph { COFFEE, LANDSCAPE, PERSON, SETTINGS }

@Composable
private fun TopModeControl(
    description: String,
    selected: Boolean,
    glyph: TopGlyph,
    onClick: () -> Unit,
) {
    val iconColor by animateColorAsState(
        targetValue = if (selected) BrandPink else Color.White,
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "top mode color",
    )
    val shellColor by animateColorAsState(
        targetValue = if (selected) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "top mode shell",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(shellColor, CircleShape)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val color = iconColor
            val stroke = Stroke(1.35.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                TopGlyph.COFFEE -> {
                    drawRoundRect(color, Offset(7.dp.toPx(), 10.dp.toPx()), Size(8.dp.toPx(), 5.5.dp.toPx()), CornerRadius(1.2.dp.toPx()), style = stroke)
                    drawArc(color, -82f, 164f, false, Offset(13.dp.toPx(), 10.6.dp.toPx()), Size(4.6.dp.toPx(), 3.5.dp.toPx()), style = stroke)
                    drawLine(color, Offset(6.8.dp.toPx(), 17.dp.toPx()), Offset(15.8.dp.toPx(), 17.dp.toPx()), strokeWidth = stroke.width)
                    drawLine(color, Offset(8.5.dp.toPx(), 7.8.dp.toPx()), Offset(8.5.dp.toPx(), 5.5.dp.toPx()), stroke.width, StrokeCap.Round)
                    drawLine(color, Offset(12.dp.toPx(), 7.8.dp.toPx()), Offset(12.dp.toPx(), 4.9.dp.toPx()), stroke.width, StrokeCap.Round)
                }
                TopGlyph.LANDSCAPE -> {
                    val path = Path().apply {
                        moveTo(4.5.dp.toPx(), 16.dp.toPx())
                        lineTo(9.5.dp.toPx(), 10.5.dp.toPx())
                        lineTo(13.dp.toPx(), 13.2.dp.toPx())
                        lineTo(17.4.dp.toPx(), 8.6.dp.toPx())
                        lineTo(20.dp.toPx(), 16.dp.toPx())
                    }
                    drawPath(path, color, style = stroke)
                    drawCircle(color, 1.35.dp.toPx(), Offset(7.dp.toPx(), 7.4.dp.toPx()), style = stroke)
                    drawLine(color, Offset(4.5.dp.toPx(), 16.dp.toPx()), Offset(20.dp.toPx(), 16.dp.toPx()), stroke.width, StrokeCap.Round)
                }
                TopGlyph.PERSON -> {
                    drawCircle(color, 2.15.dp.toPx(), Offset(12.dp.toPx(), 8.dp.toPx()), style = stroke)
                    drawArc(color, 198f, 144f, false, Offset(6.8.dp.toPx(), 11.dp.toPx()), Size(10.4.dp.toPx(), 8.6.dp.toPx()), style = stroke)
                }
                TopGlyph.SETTINGS -> {
                    drawCircle(color, 2.8.dp.toPx(), center, style = stroke)
                    repeat(8) { index ->
                        val angle = Math.toRadians((index * 45).toDouble())
                        drawLine(color, Offset(center.x + kotlin.math.cos(angle).toFloat() * 4.8.dp.toPx(), center.y + kotlin.math.sin(angle).toFloat() * 4.8.dp.toPx()), Offset(center.x + kotlin.math.cos(angle).toFloat() * 6.8.dp.toPx(), center.y + kotlin.math.sin(angle).toFloat() * 6.8.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
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
    zoomState: CameraZoomState?,
    recentPhotoUri: Uri?,
    onCapture: () -> Unit,
    onSwitchLens: () -> Unit,
    onZoomRatioSelected: (Float) -> Unit,
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
        ZoomStrip(
            zoomState = zoomState,
            onZoomRatioSelected = onZoomRatioSelected,
            modifier = Modifier.padding(top = 2.dp),
        )
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
            drawCircle(outer.copy(alpha = if (enabled) 1f else 0.42f), style = Stroke(2.8.dp.toPx()))
            drawCircle(Color.White.copy(alpha = if (enabled) 0.16f else 0.08f), radius = 31.dp.toPx())
            drawCircle(BrandPink.copy(alpha = if (enabled) 1f else 0.48f), radius = 27.dp.toPx())
            drawCircle(Color.White.copy(alpha = if (enabled) 0.22f else 0.10f), radius = 18.dp.toPx())
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
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .semantics { contentDescription = if (uri == null) "最近照片" else "最近拍摄照片" },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Canvas(Modifier.size(38.dp)) {
                drawRoundRect(Color(0xFF315A3B), cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()))
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
    Box(
        Modifier
            .size(48.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CameraSwitchGlyph() {
    Canvas(Modifier.size(28.dp)) {
        val stroke = Stroke(1.55.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(CameraControlMuted, 210f, 205f, false, Offset(4.dp.toPx(), 4.dp.toPx()), Size(20.dp.toPx(), 20.dp.toPx()), style = stroke)
        drawLine(CameraControlMuted, Offset(4.2.dp.toPx(), 13.dp.toPx()), Offset(4.2.dp.toPx(), 8.dp.toPx()), stroke.width, StrokeCap.Round)
        drawLine(CameraControlMuted, Offset(4.2.dp.toPx(), 8.dp.toPx()), Offset(8.6.dp.toPx(), 8.4.dp.toPx()), stroke.width, StrokeCap.Round)
        drawLine(CameraControlMuted, Offset(23.8.dp.toPx(), 15.dp.toPx()), Offset(23.8.dp.toPx(), 20.dp.toPx()), stroke.width, StrokeCap.Round)
        drawLine(CameraControlMuted, Offset(23.8.dp.toPx(), 20.dp.toPx()), Offset(19.4.dp.toPx(), 19.6.dp.toPx()), stroke.width, StrokeCap.Round)
    }
}

private enum class BottomGlyph { CAMERA, SHARE, PERSON }

@Composable
private fun BottomDestinationIcon(description: String, selected: Boolean, glyph: BottomGlyph, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(
                if (selected) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(14.dp),
            )
            .semantics { contentDescription = description }
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(25.dp)) {
            val color = if (selected) BrandPink else Color.White.copy(alpha = 0.94f)
            val stroke = Stroke(1.55.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                BottomGlyph.CAMERA -> {
                    drawRoundRect(color, Offset(3.dp.toPx(), 7.dp.toPx()), Size(19.dp.toPx(), 13.5.dp.toPx()), CornerRadius(2.2.dp.toPx()), style = stroke)
                    drawCircle(color, 3.8.dp.toPx(), Offset(12.5.dp.toPx(), 13.8.dp.toPx()), style = stroke)
                    drawLine(color, Offset(8.dp.toPx(), 7.dp.toPx()), Offset(10.dp.toPx(), 4.4.dp.toPx()), stroke.width)
                    drawLine(color, Offset(10.dp.toPx(), 4.4.dp.toPx()), Offset(15.dp.toPx(), 4.4.dp.toPx()), stroke.width)
                }
                BottomGlyph.SHARE -> {
                    drawCircle(color, 2.3.dp.toPx(), Offset(5.5.dp.toPx(), 13.dp.toPx()), style = stroke)
                    drawCircle(color, 2.3.dp.toPx(), Offset(19.dp.toPx(), 6.6.dp.toPx()), style = stroke)
                    drawCircle(color, 2.3.dp.toPx(), Offset(19.dp.toPx(), 19.4.dp.toPx()), style = stroke)
                    drawLine(color, Offset(7.5.dp.toPx(), 12.2.dp.toPx()), Offset(16.4.dp.toPx(), 7.6.dp.toPx()), stroke.width)
                    drawLine(color, Offset(7.5.dp.toPx(), 13.8.dp.toPx()), Offset(16.4.dp.toPx(), 18.4.dp.toPx()), stroke.width)
                }
                BottomGlyph.PERSON -> {
                    drawCircle(color, 3.7.dp.toPx(), Offset(12.5.dp.toPx(), 7.2.dp.toPx()), style = stroke)
                    drawArc(color, 196f, 148f, false, Offset(4.2.dp.toPx(), 12.dp.toPx()), Size(16.6.dp.toPx(), 11.6.dp.toPx()), style = stroke)
                }
            }
        }
    }
}

@Composable
private fun ZoomStrip(
    zoomState: CameraZoomState?,
    onZoomRatioSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minZoom = zoomState?.minZoomRatio ?: 1f
    val maxZoom = zoomState?.maxZoomRatio ?: 1f
    val currentZoom = zoomState?.currentZoomRatio ?: 1f
    val presets = listOf(0.8f, 1f, 2f)
        .map { it.coerceIn(minZoom, maxZoom) }
        .distinct()

    Row(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { ratio ->
            val selected = kotlin.math.abs(ratio - currentZoom) < 0.08f
            ZoomPresetChip(
                label = formatZoomLabel(ratio),
                selected = selected,
                onClick = { onZoomRatioSelected(ratio) },
            )
        }
    }
}

@Composable
private fun ZoomPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember(label) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .96f else 1f,
        animationSpec = tween(GenWowMotion.PressMillis, easing = FastOutSlowInEasing),
        label = "zoom chip press",
    )
    val bg by animateColorAsState(
        targetValue = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "zoom chip bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) BrandPink else Color.White.copy(alpha = 0.86f),
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "zoom chip fg",
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .background(bg, RoundedCornerShape(999.dp))
            .semantics {
                contentDescription = "切换到${label}变焦"
                this.selected = selected
            }
            .clickable(
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatZoomLabel(ratio: Float): String = when {
    ratio >= 1f && ratio % 1f == 0f -> "${ratio.toInt()}x"
    else -> "${String.format("%.1f", ratio)}x"
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
