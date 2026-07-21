package com.genwow.camera.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.data.demo.DemoProfile
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.GenWowMotion
import com.genwow.camera.ui.theme.GenWowTheme
import com.genwow.camera.ui.theme.SystemBarsAppearance

private val ProfileBackground = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFE8F4),
        Color(0xFFF5E3F8),
        Color(0xFFD8D0FF),
        Color(0xFFFFFFFF),
    ),
)

@Composable
fun ProfileScreen(
    profile: DemoProfile,
    onOpenEntryFlow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBarsAppearance(light = true)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val headerProgress by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(GenWowMotion.PageEnterMillis, easing = GenWowMotion.StandardEasing),
        label = "profile header enter",
    )
    val galleryProgress by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(300, delayMillis = 70, easing = GenWowMotion.StandardEasing),
        label = "profile gallery enter",
    )

    Column(modifier.fillMaxSize().background(ProfileBackground).statusBarsPadding()) {
        Box(Modifier.fillMaxSize()) {
            ProfileSettingsButton(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 18.dp),
                onClick = onOpenEntryFlow,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(26.dp))
                Text(
                    text = "我的主页",
                    color = BrandOnSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(28.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = headerProgress
                        translationY = (1f - headerProgress) * 16.dp.toPx()
                    },
                ) {
                    ProfileAvatar()
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = profile.displayName,
                        color = BrandOnSurface,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = profile.bio,
                        color = BrandOnSurfaceMuted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    ProfileStats(profile)
                }
                Spacer(Modifier.height(34.dp))
                ProfileGallery(
                    items = profile.galleryItems,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            alpha = galleryProgress
                            translationY = (1f - galleryProgress) * 14.dp.toPx()
                        },
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Preview(name = "Profile 412x917", widthDp = 412, heightDp = 917)
@Composable
private fun ProfilePreview() {
    GenWowTheme {
        ProfileScreen(profile = DemoCommunityRepository().profile, onOpenEntryFlow = {})
    }
}
