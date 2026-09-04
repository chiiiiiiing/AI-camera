package com.example.aicamera.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.R
import com.example.aicamera.filter.CustomFilterConfig
import com.example.aicamera.ui.theme.BrandPink

@Composable
fun FilterEditorScreen(
    initial: CustomFilterConfig?,
    onBack: () -> Unit,
    onSave: (CustomFilterConfig) -> Unit
) {
    var config by remember(initial) { mutableStateOf(initial ?: CustomFilterConfig()) }
    val previewFilter = remember(config) {
        ColorFilter.colorMatrix(ColorMatrix(config.colorMatrix().array))
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF111116))) {
        Box(Modifier.fillMaxWidth().weight(0.42f)) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.community_cafe_portrait),
                contentDescription = "滤镜效果预览",
                contentScale = ContentScale.Crop,
                colorFilter = previewFilter,
                modifier = Modifier.fillMaxSize()
            )
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("取消", color = Color.White) }
                Text("自定义滤镜", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { onSave(config) }) { Text("保存", color = BrandPink, fontWeight = FontWeight.Bold) }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(0.58f).verticalScroll(rememberScrollState())
                .background(Color(0xE618181E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(18.dp)
        ) {
            OutlinedTextField(
                value = config.name,
                onValueChange = { config = config.copy(name = it.take(20)) },
                label = { Text("滤镜名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = BrandPink, focusedLabelColor = BrandPink,
                    unfocusedLabelColor = Color.White.copy(alpha = .6f)
                )
            )
            SectionTitle("光线")
            FilterSlider("曝光", config.exposure, -2f..2f) { config = config.copy(exposure = it) }
            FilterSlider("对比度", config.contrast, -100f..100f) { config = config.copy(contrast = it) }
            FilterSlider("高光", config.highlights, -100f..100f) { config = config.copy(highlights = it) }
            FilterSlider("阴影", config.shadows, -100f..100f) { config = config.copy(shadows = it) }
            SectionTitle("颜色")
            FilterSlider("饱和度", config.saturation, -100f..100f) { config = config.copy(saturation = it) }
            FilterSlider("色温", config.temperature, -100f..100f) { config = config.copy(temperature = it) }
            FilterSlider("色调", config.tint, -100f..100f) { config = config.copy(tint = it) }
            FilterSlider("红色", config.red, -100f..100f, Color(0xFFFF6B6B)) { config = config.copy(red = it) }
            FilterSlider("绿色", config.green, -100f..100f, Color(0xFF55D98A)) { config = config.copy(green = it) }
            FilterSlider("蓝色", config.blue, -100f..100f, Color(0xFF69A8FF)) { config = config.copy(blue = it) }
            SectionTitle("细节")
            FilterSlider("锐化", config.sharpness, 0f..100f) { config = config.copy(sharpness = it) }
            FilterSlider("暗角", config.vignette, 0f..100f) { config = config.copy(vignette = it) }
            Text("原片始终保留；自定义滤镜会生成独立副本，并可在社区评论中分享参数。",
                color = Color.White.copy(alpha = .55f), fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable private fun FilterSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    color: Color = BrandPink, onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White.copy(alpha = .82f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (value > 0) "+${value.toInt()}" else value.toInt().toString(), color = color, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
            modifier = Modifier.height(32.dp))
    }
}
