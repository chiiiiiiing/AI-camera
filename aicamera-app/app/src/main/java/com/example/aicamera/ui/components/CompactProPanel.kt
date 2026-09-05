package com.example.aicamera.ui.components

import android.hardware.camera2.CaptureRequest
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.theme.BrandPink

private enum class ProControl { EV, ISO, SHUTTER, WB, FOCUS, ZOOM }

@Composable
fun CompactProPanel(
    modifier: Modifier = Modifier, isVisible: Boolean,
    evValue: Int, evRange: Pair<Int, Int>, onEvChange: (Int) -> Unit,
    isoValue: Int, onIsoChange: (Int) -> Unit,
    awbMode: Int, onAwbChange: (Int) -> Unit,
    exposureTimeNs: Long?, onExposureTimeChange: (Long?) -> Unit,
    focusDistance: Float?, onFocusDistanceChange: (Float?) -> Unit,
    zoomRatio: Float, zoomRange: Pair<Float, Float>, onZoomChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(ProControl.EV) }
    AnimatedVisibility(
        visible = isVisible,
        modifier = modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut()
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .64f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ProChip("EV", if (evValue == 0) "0" else "%+.1f".format(evValue / 3f), selected == ProControl.EV) { selected = ProControl.EV }
                ProChip("ISO", if (isoValue == 0) "AUTO" else "$isoValue", selected == ProControl.ISO) { selected = ProControl.ISO }
                ProChip("S", shutterLabel(exposureTimeNs), selected == ProControl.SHUTTER) { selected = ProControl.SHUTTER }
                ProChip("WB", wbLabel(awbMode), selected == ProControl.WB) { selected = ProControl.WB }
                ProChip("FOCUS", focusDistance?.let { "%.1fD".format(it) } ?: "AUTO", selected == ProControl.FOCUS) { selected = ProControl.FOCUS }
                ProChip("ZOOM", "%.1fx".format(zoomRatio), selected == ProControl.ZOOM) { selected = ProControl.ZOOM }
                ProChip("×", "关闭", false, onDismiss)
            }
            Spacer(Modifier.height(9.dp))
            when (selected) {
                ProControl.EV -> Slider(value = evValue.toFloat(), onValueChange = { onEvChange(it.toInt()) }, valueRange = evRange.first.toFloat()..evRange.second.toFloat(), colors = proSliderColors())
                ProControl.FOCUS -> Slider(value = focusDistance ?: 0f, onValueChange = { onFocusDistanceChange(if (it < .05f) null else it) }, valueRange = 0f..10f, colors = proSliderColors())
                ProControl.ISO -> OptionRow(listOf("AUTO" to 0, "100" to 100, "200" to 200, "400" to 400, "800" to 800, "1600" to 1600), isoValue, onIsoChange)
                ProControl.SHUTTER -> OptionRow(listOf("AUTO" to null, "1/15" to 66_666_667L, "1/30" to 33_333_333L, "1/60" to 16_666_667L, "1/125" to 8_000_000L, "1/250" to 4_000_000L, "1/500" to 2_000_000L), exposureTimeNs, onExposureTimeChange)
                ProControl.WB -> OptionRow(listOf("AUTO" to CaptureRequest.CONTROL_AWB_MODE_AUTO, "日光" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "阴天" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "白炽灯" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "荧光灯" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT), awbMode, onAwbChange)
                ProControl.ZOOM -> OptionRow(listOf(1f, 1.5f, 2f, 3f, zoomRange.second).distinct().filter { it in zoomRange.first..zoomRange.second }.map { "%.1fx".format(it) to it }, zoomRatio, onZoomChange)
            }
        }
    }
}

@Composable private fun ProChip(title: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(11.dp)).background(if (selected) BrandPink else Color.White.copy(alpha = .12f)).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = if (selected) Color.White else Color.White.copy(alpha = .58f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable private fun <T> OptionRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) -> Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (value == selected) BrandPink else Color.White.copy(alpha = .12f)).clickable { onSelect(value) }.padding(horizontal = 13.dp, vertical = 9.dp)) }
    }
}

@Composable private fun proSliderColors() = SliderDefaults.colors(thumbColor = BrandPink, activeTrackColor = BrandPink, inactiveTrackColor = Color.White.copy(alpha = .22f))
private fun shutterLabel(ns: Long?): String = if (ns == null) "AUTO" else "1/${(1_000_000_000L / ns).coerceAtLeast(1)}"
private fun wbLabel(mode: Int): String = when (mode) { CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "日光"; CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "阴天"; CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "白炽灯"; CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "荧光灯"; else -> "AUTO" }
