package com.example.aicamera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.CameraChrome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 对焦框旁的曝光/EV 调节条。
 *
 * - 出现在点击对焦位置右侧
 * - 垂直拖动改变 EV index，3 秒无操作自动隐藏
 * - 仅在对焦框可见期间显示
 *
 * @param focusX 对焦框中心 X（px，Compose 坐标）
 * @param focusY 对焦框中心 Y（px）
 * @param evIndex 当前 EV index
 * @param evRange 相机支持的 EV 范围（lower to upper）
 * @param onEVChange EV index 变化回调
 */
@Composable
fun FocusExposureOverlay(
    focusX: Float,
    focusY: Float,
    evIndex: Int,
    evRange: Pair<Int, Int>,
    onEVChange: (Int) -> Unit,
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hideDelayMs = 3000L

    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isVisible by remember { mutableStateOf(visible) }

    // 拖拽过程中用本地值避免抖动
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    val pxPerStep = with(density) { 18.dp.toPx() }

    val currentOnChange by rememberUpdatedState(onEVChange)

    fun resetHideTimer() {
        hideJob?.cancel()
        isVisible = true
        hideJob = scope.launch {
            delay(hideDelayMs)
            if (!isDragging) isVisible = false
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            resetHideTimer()
        } else {
            isVisible = false
        }
    }

    AnimatedVisibility(
        visible = isVisible && visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val offsetX = with(density) { focusX.toDp() + 52.dp }
        val offsetY = with(density) { focusY.toDp() - 70.dp }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(offsetX.roundToPx(), offsetY.roundToPx())
                }
                .pointerInput(evIndex, evRange) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragAccumulated = 0f
                            hideJob?.cancel()
                        },
                        onDragEnd = {
                            isDragging = false
                            resetHideTimer()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulated -= dragAmount
                            val steps = (dragAccumulated / pxPerStep).roundToInt()
                            if (steps != 0) {
                                val newIndex = (evIndex + steps)
                                    .coerceIn(evRange.first, evRange.second)
                                currentOnChange(newIndex)
                                dragAccumulated = 0f
                            }
                        }
                    )
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 当前 EV 值胶囊
                Box(
                    modifier = Modifier
                        .background(CameraChrome.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${if (evIndex >= 0) "+" else ""}$evIndex",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 垂直滑轨
                val trackHeight = 120.dp
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(trackHeight)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                ) {
                    val range = (evRange.second - evRange.first).coerceAtLeast(1)
                    val fraction = ((evIndex - evRange.first).toFloat() / range)
                        .coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(trackHeight * fraction)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(BrandPink, BrandPink.copy(alpha = 0.7f))
                                ),
                                RoundedCornerShape(999.dp)
                            )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // + / - 标识
                Text(
                    text = "亮度",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}
