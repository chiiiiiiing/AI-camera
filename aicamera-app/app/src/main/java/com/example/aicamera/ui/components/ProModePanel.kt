package com.example.aicamera.ui.components

import android.hardware.camera2.CaptureRequest
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 专业模式控制面板 — 叠加在相机取景器上的手动控制 UI。
 *
 * ## 控制项
 * - EV 曝光补偿 (slider，实时生效)
 * - ISO 感光度 (预设按钮，重绑生效)
 * - 快门速度 (预设按钮，重绑生效)
 * - 白平衡模式 (预设按钮，重绑生效)
 * - 对焦距离 (滑杆，屈光度，重绑生效)
 * - 变焦快捷档位
 *
 * 注：面板只接管曝光/对焦链路，AI 构图分析（ImageAnalysis）与
 * 引导叠加层完全独立，开关面板不影响 AI 辅助构图。
 */
@Composable
fun ProModePanel(
    modifier: Modifier = Modifier,
    isVisible: Boolean = false,
    evValue: Int = 0,
    evRange: Pair<Int, Int> = Pair(-12, 12),
    onEvChange: (Int) -> Unit = {},
    isoValue: Int = 200,
    onIsoChange: (Int) -> Unit = {},
    awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    onAwbChange: (Int) -> Unit = {},
    exposureTimeNs: Long? = null,
    onExposureTimeChange: (Long?) -> Unit = {},
    focusDistance: Float? = null,
    onFocusDistanceChange: (Float?) -> Unit = {},
    zoomRatio: Float = 1f,
    zoomRange: Pair<Float, Float> = Pair(1f, 4f),
    onZoomChange: (Float) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pro 模式", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── EV 曝光补偿 ──
            Text("曝光补偿 (EV): ${"%.1f".format(evValue / 3f)}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Slider(
                value = evValue.toFloat(),
                onValueChange = { onEvChange(it.toInt()) },
                valueRange = evRange.first.toFloat()..evRange.second.toFloat(),
                steps = evRange.second - evRange.first - 1,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFF9800), activeTrackColor = Color(0xFFFF9800))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── ISO ──
            Text("ISO", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 200, 400, 800, 1600, 3200).forEach { iso ->
                    val selected = iso == isoValue
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFFFF9800) else Color.White.copy(alpha = 0.1f))
                            .clickable { onIsoChange(iso) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("$iso", color = if (selected) Color.Black else Color.White, fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 快门速度 ──
            Text("快门速度", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // (label, 曝光时间 ns；null = 自动)
                val shutterOptions = listOf(
                    "自动" to null,
                    "1/15" to 66_666_667L,
                    "1/30" to 33_333_333L,
                    "1/60" to 16_666_667L,
                    "1/125" to 8_000_000L,
                    "1/250" to 4_000_000L,
                    "1/500" to 2_000_000L,
                    "1/1000" to 1_000_000L
                )
                shutterOptions.forEach { (label, ns) ->
                    val selected = exposureTimeNs == ns || (ns == null && exposureTimeNs == null)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF35C6D4) else Color.White.copy(alpha = 0.1f))
                            .clickable { onExposureTimeChange(ns) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 对焦距离（屈光度：0 = 无穷远，值越大越近） ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "对焦距离: " + (focusDistance?.let { "${"%.1f".format(it)}D" } ?: "自动"),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (focusDistance == null) Color(0xFF35C6D4) else Color.White.copy(alpha = 0.1f))
                        .clickable { onFocusDistanceChange(null) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("自动对焦", color = if (focusDistance == null) Color.Black else Color.White, fontSize = 11.sp)
                }
            }
            Slider(
                value = focusDistance ?: 0f,
                onValueChange = { onFocusDistanceChange(if (it <= 0.05f) null else it) },
                valueRange = 0f..10f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = Color(0xFF35C6D4), activeTrackColor = Color(0xFF35C6D4))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 白平衡 ──
            Text("白平衡", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data class WbOption(val mode: Int, val label: String)
                val wbModes = listOf(
                    WbOption(CaptureRequest.CONTROL_AWB_MODE_AUTO, "自动"),
                    WbOption(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "日光"),
                    WbOption(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "阴天"),
                    WbOption(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "白炽灯"),
                    WbOption(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, "荧光灯")
                )
                wbModes.forEach { (mode, label) ->
                    val selected = mode == awbMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF2196F3) else Color.White.copy(alpha = 0.1f))
                            .clickable { onAwbChange(mode) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 变焦快捷档位 ──
            Text("变焦: ${"%.1f".format(zoomRatio)}x", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1f, 2f, 3f, zoomRange.second).filter { it <= zoomRange.second }.forEach { zoom ->
                    val selected = kotlin.math.abs(zoomRatio - zoom) < 0.1f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF8B5FBF) else Color.White.copy(alpha = 0.1f))
                            .clickable { onZoomChange(zoom) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${"%.1f".format(zoom)}x", color = if (selected) Color.Black else Color.White, fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
