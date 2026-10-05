package com.example.aicamera.ui.screen

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.BuildConfig
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.BrandPurple
import com.example.aicamera.ui.auth.SystemBarsAppearance

/** 设置页背景渐变（队友 GenWow 设计） */
private val SettingsBackground = Brush.verticalGradient(
    listOf(
        Color(0xFFFFE7F3),
        Color(0xFFF6E5F7),
        Color(0xFFD9CEFF),
    ),
)

/**
 * 设置页 — 移植自队友 ui/settings/SettingsScreen.kt，适配主项目 SettingsState。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBarsAppearance(light = true)

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
            SettingsSliderRow(
                label = "辅助线透明度",
                subtitle = "明亮背景下保持可见又不过度遮挡",
                value = settings.overlayAlpha,
                onValueChange = { onSettingsChange(settings.copy(overlayAlpha = it)) },
            )
            SettingsSwitchRow(
                label = "显示三分线网格",
                subtitle = "按下快门后显示三分线参考",
                checked = settings.showGrid,
                onCheckedChange = { onSettingsChange(settings.copy(showGrid = it)) },
            )
            SettingsSwitchRow(
                label = "显示主体框",
                subtitle = "检测到主体时绘制边界框",
                checked = settings.showSubjectBox,
                onCheckedChange = { onSettingsChange(settings.copy(showSubjectBox = it)) },
            )
            SettingsSwitchRow(
                label = "显示目标区",
                subtitle = "展示推荐构图位置参考框",
                checked = settings.showTargetZone,
                onCheckedChange = { onSettingsChange(settings.copy(showTargetZone = it)) },
            )
            SettingsSwitchRow(
                label = "显示水平仪",
                subtitle = "IMU 实时水平参考线",
                checked = settings.showHorizon,
                onCheckedChange = { onSettingsChange(settings.copy(showHorizon = it)) },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "拍摄与相册") {
            SettingsSwitchRow(
                label = "安全自动变焦",
                subtitle = "仅识别到真实人物时轻微调整，最高 1.8×",
                checked = settings.aiAutoZoomEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(aiAutoZoomEnabled = it)) },
            )
            SettingsSwitchRow(
                label = "保存原图到相册",
                subtitle = "默认开启，拍摄后直接写入系统相册",
                checked = settings.saveOriginal,
                onCheckedChange = { onSettingsChange(settings.copy(saveOriginal = it)) },
            )
            SettingsSwitchRow(
                label = "拍照音效",
                subtitle = "默认关闭，减少公共场景干扰",
                checked = settings.soundEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(soundEnabled = it)) },
            )
            SettingsSwitchRow(
                label = "Pro 手动模式",
                subtitle = "开启后可手动调节 EV/ISO/白平衡/对焦",
                checked = settings.proModeEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(proModeEnabled = it)) },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "关于") {
            SettingsInfoRow("产品名称", "帧好 GenWow")
            SettingsInfoRow("版本", BuildConfig.VERSION_NAME)
            SettingsInfoRow("定位", "AI 构图相机")
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        Slider(value = value, onValueChange = onValueChange, valueRange = 0.25f..1f)
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = BrandOnSurfaceMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = BrandOnSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
