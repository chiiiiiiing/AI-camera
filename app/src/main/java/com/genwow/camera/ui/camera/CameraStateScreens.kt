package com.genwow.camera.ui.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.R
import com.genwow.camera.ui.theme.BrandBackgroundGradient
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.BrandPurple
import com.genwow.camera.ui.theme.GuideAccent
import com.genwow.camera.ui.theme.GuideWarning

@Composable
fun PermissionStateScreen(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StateScreen(
        accent = GuideAccent,
        title = "开启相机，开始构图",
        message = "帧好只在设备上分析取景画面。照片仅在你按下快门后保存。",
        action = if (permanentlyDenied) "前往设置" else "允许使用相机",
        onAction = if (permanentlyDenied) onOpenSettings else onRequestPermission,
    )
}

@Composable
fun ErrorStateScreen(message: String, onRetry: () -> Unit) {
    StateScreen(
        accent = GuideWarning,
        title = "相机暂时不可用",
        message = message.ifBlank { "请检查是否有其他应用占用相机，然后重试。" },
        action = "重新尝试",
        onAction = onRetry,
    )
}

@Composable
private fun StateScreen(
    accent: Color,
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BrandBackgroundGradient)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(accent.copy(alpha = .12f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_genwow_logo),
                    contentDescription = null,
                    modifier = Modifier.size(66.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(title, color = BrandOnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                color = BrandOnSurfaceMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = action,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(BrandPurple, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 24.dp, vertical = 13.dp),
            )
        }
    }
}
