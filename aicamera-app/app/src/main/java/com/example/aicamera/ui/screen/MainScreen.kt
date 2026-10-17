package com.example.aicamera.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.example.aicamera.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.BrandTabBar
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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
 * 底部全局三 Tab 导航栏 — 黑色相机底栏 + 粉色图标。
 */
@Composable
private fun BottomTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        color = BrandTabBar,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
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
    // Figma 底栏图标：选中品牌粉，未选中黑色
    val iconColor = if (isSelected) BrandPink else Color.Black
    val iconSize = if (isSelected) 28.dp else 25.dp
    val iconRes = when (glyph) {
        BottomGlyph.CAMERA -> R.drawable.ic_tab_camera
        BottomGlyph.SHARE -> R.drawable.ic_tab_share
        BottomGlyph.PERSON -> R.drawable.ic_tab_person
    }

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
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}
