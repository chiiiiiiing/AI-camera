package com.genwow.camera.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.BrandPink
import com.genwow.camera.ui.theme.BrandPurple
import com.genwow.camera.ui.theme.GenWowTheme
import com.genwow.camera.ui.theme.SystemBarsAppearance

private val SettingsBackground = Brush.verticalGradient(
    listOf(
        Color(0xFFFFE7F3),
        Color(0xFFF6E5F7),
        Color(0xFFD9CEFF),
    ),
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBarsAppearance(light = true)
    var guideEnabled by remember { mutableStateOf(true) }
    var thirdsGridEnabled by remember { mutableStateOf(true) }
    var saveOriginalEnabled by remember { mutableStateOf(true) }
    var shutterSoundEnabled by remember { mutableStateOf(false) }
    var guideAlpha by remember { mutableFloatStateOf(0.74f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SettingsBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "返回",
                color = BrandOnSurfaceMuted,
                fontSize = 14.sp,
                modifier = Modifier
                    .semantics {
                        contentDescription = "返回设置"
                        role = Role.Button
                    }
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "设置",
                color = BrandOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(40.dp))
        }
        Spacer(Modifier.height(18.dp))

        SettingsSection(title = "辅助线与引导") {
            SettingsSwitchRow(
                label = "显示辅助线",
                subtitle = "默认开启，帮助用户快速找到推荐构图",
                checked = guideEnabled,
                onCheckedChange = { guideEnabled = it },
            )
            SettingsSliderRow(
                label = "辅助线透明度",
                subtitle = "默认 74%，明亮背景下保持可见又不过度遮挡",
                value = guideAlpha,
                onValueChange = { guideAlpha = it },
            )
            SettingsSwitchRow(
                label = "显示三分线网格",
                subtitle = "默认开启，用于静态参考",
                checked = thirdsGridEnabled,
                onCheckedChange = { thirdsGridEnabled = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "拍摄与相册") {
            SettingsSwitchRow(
                label = "保存原图",
                subtitle = "默认开启，拍摄后直接写入系统相册",
                checked = saveOriginalEnabled,
                onCheckedChange = { saveOriginalEnabled = it },
            )
            SettingsSwitchRow(
                label = "拍照音效",
                subtitle = "默认关闭，减少公共场景干扰",
                checked = shutterSoundEnabled,
                onCheckedChange = { shutterSoundEnabled = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "账号与隐私") {
            SettingsInfoRow("当前状态", "本地演示账号")
            SettingsInfoRow("账号安全", "密码仅保存在当前会话内存")
            SettingsInfoRow("隐私说明", "未接入真实云端同步")
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "关于") {
            SettingsInfoRow("产品名称", "帧好 GenWow")
            SettingsInfoRow("版本", "1.0.0")
            SettingsInfoRow("定位", "AI 构图相机原型")
        }

        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandPurple, RoundedCornerShape(18.dp))
                .clickable(onClick = onLogout)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("退出登录", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = {
            Text(title, color = BrandOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            content()
        },
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = BrandOnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = BrandOnSurfaceMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = BrandOnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = BrandOnSurfaceMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Text("${(value * 100).toInt()}%", color = BrandPink.copy(alpha = 0.92f), fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.25f..1f,
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = BrandOnSurfaceMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = BrandOnSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun SettingsPreview() {
    GenWowTheme {
        SettingsScreen(onBack = {}, onLogout = {})
    }
}
