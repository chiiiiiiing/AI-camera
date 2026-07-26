package com.example.aicamera.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.state.SettingsState

/**
 * 设置页。
 *
 * 功能：
 * - 辅助线开关（三分线 / 主体框 / 目标区域 / 水平仪）
 * - 辅助线透明度 slider
 * - 对焦相关设置
 * - 关于（版本号 / 隐私政策占位）
 * - 退出登录
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit
) {
    val context = LocalContext.current
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        // ── 顶部栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text(
                text = "设置",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ── 辅助线设置 ──
            SettingsSectionHeader("辅助线显示")

            SettingsToggle("三分线网格", settings.showGrid) {
                onSettingsChange(settings.copy(showGrid = it))
            }
            SettingsToggle("主体定位框", settings.showSubjectBox) {
                onSettingsChange(settings.copy(showSubjectBox = it))
            }
            SettingsToggle("目标区域", settings.showTargetZone) {
                onSettingsChange(settings.copy(showTargetZone = it))
            }
            SettingsToggle("水平仪", settings.showHorizon) {
                onSettingsChange(settings.copy(showHorizon = it))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 透明度
            Text(
                "辅助线透明度: ${(settings.overlayAlpha * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = settings.overlayAlpha,
                onValueChange = { onSettingsChange(settings.copy(overlayAlpha = it)) },
                valueRange = 0.2f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF8B5FBF),
                    activeTrackColor = Color(0xFF8B5FBF)
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── 拍摄 ──
            SettingsSectionHeader("拍摄")

            SettingsToggle("快门声音", settings.soundEnabled) {
                onSettingsChange(settings.copy(soundEnabled = it))
            }
            SettingsToggle("保存到系统相册", settings.saveOriginal) {
                onSettingsChange(settings.copy(saveOriginal = it))
            }
            SettingsToggle("Pro 模式（EV/ISO/白平衡）", settings.proModeEnabled) {
                onSettingsChange(settings.copy(proModeEnabled = it))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── AI 模型 ──
            SettingsSectionHeader("AI 模型")

            SettingsInfoRow("推理引擎", "ONNX Runtime v1.19.2")
            SettingsInfoRow("当前模型", "V9 static int8 (301KB)")
            SettingsInfoRow("模板数量", "728 条")
            SettingsInfoRow("推理延迟", "< 150ms (目标)")

            Spacer(modifier = Modifier.height(24.dp))

            // ── 缓存与存储 ──
            SettingsSectionHeader("缓存与存储")

            SettingsNavRow("清除缓存") { /* TODO: 清除 Glide/Coil + 模型索引缓存 */ }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 关于 ──
            SettingsSectionHeader("关于")

            SettingsInfoRow("版本号", "1.0.0")
            SettingsNavRow("隐私政策") { /* TODO: 打开隐私政策网页 */ }
            SettingsNavRow("用户协议") { /* TODO: 打开用户协议网页 */ }

            Spacer(modifier = Modifier.height(32.dp))

            // ── 退出登录 ──
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935).copy(alpha = 0.2f),
                    contentColor = Color(0xFFEF5350)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("退出登录")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 退出确认弹窗
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？") },
            confirmButton = {
                TextButton(onClick = {
                    AuthRepository.getInstance(context).logout()
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text("确定", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ── 设置项组件 ──────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF8B5FBF),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

@Composable
private fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF8B5FBF)
            )
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        Text(value, color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
    }
}

@Composable
private fun SettingsNavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}
