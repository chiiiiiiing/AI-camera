package com.example.aicamera.ui.components

import android.net.Uri
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.CameraChrome
import com.example.aicamera.ui.theme.GuideAligned

/** 拍摄完成后的确认页。只反馈结果与操作，不生成伪审美分数。 */
@Composable
fun CaptureResultSheet(
    imageUri: Uri?,
    strategy: String,
    sceneType: String,
    onDone: () -> Unit,
    onRetake: () -> Unit,
    onShareToCommunity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val strategyCN = when {
        strategy.contains("rule_of_thirds") || strategy.contains("thirds") -> "三分构图"
        strategy.contains("centered") || strategy.contains("symmetry") -> "对称构图"
        strategy.contains("leading") -> "引导线构图"
        strategy.contains("negative_space") -> "留白构图"
        strategy.contains("horizon") -> "水平构图"
        else -> "辅助构图"
    }
    val sceneCN = when {
        sceneType.contains("portrait") || sceneType.contains("person") -> "人像"
        sceneType.contains("landscape") -> "风景"
        sceneType.contains("food") || sceneType.contains("coffee") -> "美食"
        else -> "日常"
    }

    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(CameraChrome.copy(alpha = 0.96f))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(
            Modifier
                .size(width = 38.dp, height = 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.2f))
        )
        Spacer(Modifier.height(14.dp))
        AsyncImage(
            model = imageUri,
            contentDescription = "刚拍摄的照片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isLandscape) Modifier.height(112.dp)
                    else Modifier.aspectRatio(16f / 10f)
                )
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.08f))
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = GuideAligned, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("  照片已保存", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            "$strategyCN · $sceneCN",
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            ) { Text("重拍") }
            Button(
                onClick = onShareToCommunity,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BrandPink),
                shape = RoundedCornerShape(14.dp)
            ) { Text("发布到社区") }
        }
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White.copy(alpha = 0.72f)
            )
        ) { Text("完成并返回相机", fontSize = 13.sp) }
    }
}
