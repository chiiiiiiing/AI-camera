package com.example.aicamera.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.PipelineViewModel
import com.example.aicamera.viewmodel.RenderViewModel

/**
 * 主页面容器 — 底部三 Tab 导航
 *
 * Tab 从左至右：
 * ① 相机图标 → 相机拍摄页
 * ② 社区图标 → 社区动态推荐页
 * ③ 用户头像图标 → 个人主页
 */
@Composable
fun MainScreen(
    cameraViewModel: CameraViewModel,
    pipelineViewModel: PipelineViewModel,
    renderViewModel: RenderViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    // ★ Tab 切换时控制相机/管线资源：离开相机 Tab 暂停，进入时恢复
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) {
            cameraViewModel.onResume()
            pipelineViewModel.resume()
        } else {
            pipelineViewModel.pause()
            cameraViewModel.onPause()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        bottomBar = {
            BottomTabBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> CameraScreen(
                    cameraViewModel = cameraViewModel,
                    pipelineViewModel = pipelineViewModel,
                    renderViewModel = renderViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToGallery = onNavigateToGallery,
                    settings = settings
                )
                1 -> CommunityScreen()
                2 -> ProfileScreen(
                    onNavigateToSettings = onNavigateToSettings
                )
            }
        }
    }
}

/**
 * 底部全局三 Tab 导航栏
 */
@Composable
private fun BottomTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        color = Color(0xFF0A0A0A),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            // Tab 1 — 相机
            BottomTabItem(
                icon = Icons.Default.CameraAlt,
                label = "相机",
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )

            // Tab 2 — 社区
            BottomTabItem(
                icon = Icons.Default.Explore,
                label = "社区",
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )

            // Tab 3 — 个人
            BottomTabItem(
                icon = Icons.Default.Person,
                label = "我的",
                isSelected = selectedTab == 2,
                onClick = { onTabSelected(2) }
            )
        }
    }
}

@Composable
private fun BottomTabItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val iconColor = if (isSelected) Color(0xFF8B5FBF) else Color.Gray
    val bgColor = if (isSelected) Color(0xFF6C2FB0).copy(alpha = 0.2f) else Color.Transparent

    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                onClick = onClick,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            )
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = iconColor,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
