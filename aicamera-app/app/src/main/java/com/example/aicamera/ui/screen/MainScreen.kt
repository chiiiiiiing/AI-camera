package com.example.aicamera.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.ui.theme.BrandPink
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.CommunityViewModel
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
    onNavigateToEditProfile: (() -> Unit)? = null,
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToFollowers: (String) -> Unit = {},
    onNavigateToFollowing: (String) -> Unit = {},
    onLogout: (() -> Unit)? = null,
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit
) {
    // ★ 社区 VM 仅在社区 Tab 使用，通过 hiltViewModel() 即时获取
    val communityViewModel: CommunityViewModel = hiltViewModel()
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
                    onNavigateToCommunity = { selectedTab = 1 },
                    settings = settings,
                    onSettingsChange = onSettingsChange
                )
                1 -> CommunityScreen(
                    viewModel = communityViewModel,
                    onNavigateToProfile = onNavigateToProfile
                )
                2 -> ProfileScreen(
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToEditProfile = onNavigateToEditProfile,
                    onNavigateToFollowers = onNavigateToFollowers,
                    onNavigateToFollowing = onNavigateToFollowing,
                    onNavigateToProfile = onNavigateToProfile,
                    onLogout = onLogout,
                    communityViewModel = communityViewModel
                )
            }
        }
    }
}

/** 底部 Tab glyph 标识（队友 GenWow BottomGlyph） */
private enum class BottomGlyph { CAMERA, SHARE, PERSON }

/**
 * 底部全局三 Tab 导航栏 — 设计稿 Tab Bar.png: icon-only, 无文字, 浅粉底, 粉色选中
 */
@Composable
private fun BottomTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        color = Color(0xFFFFF1F5),  // ★ 统一浅粉底栏（不对相机 Tab 深色）
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            BottomGlyphTab(
                glyph = BottomGlyph.CAMERA,
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )
            BottomGlyphTab(
                glyph = BottomGlyph.SHARE,
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )
            BottomGlyphTab(
                glyph = BottomGlyph.PERSON,
                isSelected = selectedTab == 2,
                onClick = { onTabSelected(2) }
            )
        }
    }
}

@Composable
private fun BottomGlyphTab(
    glyph: BottomGlyph,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val iconColor = if (isSelected) BrandPink else Color(0xFF8A8093)
    val iconSize = if (isSelected) 28.dp else 25.dp  // ★ 选中稍大

    Box(
        modifier = Modifier
            .clickable(
                onClick = onClick,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            )
            .semantics {
                contentDescription = when (glyph) {
                    BottomGlyph.CAMERA -> "相机"
                    BottomGlyph.SHARE -> "社区"
                    BottomGlyph.PERSON -> "我的"
                }
            }
            .padding(8.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(Modifier.size(iconSize)) {
            val color = iconColor
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
