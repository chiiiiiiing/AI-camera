package com.example.aicamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.example.aicamera.ui.theme.BrandPink
import com.example.aicamera.ui.theme.CameraChrome
import com.example.aicamera.ui.theme.GuideAligned

/**
 * 拍后构图评分卡。
 *
 * @param score 0–100 的构图评分
 * @param strategy 构图策略名（如 "rule_of_thirds"）
 * @param sceneType 场景名（如 "portrait"）
 * @param onSaveToGallery 点击“保存到相册”
 * @param onShareToCommunity 点击“分享到社区”
 */
@Composable
fun CompositionScoreCard(
    imageUri: Uri?,
    score: Int,
    strategy: String,
    sceneType: String,
    onSaveToGallery: () -> Unit,
    onRetake: () -> Unit,
    onShareToCommunity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strategyCN = when {
        strategy.contains("rule_of_thirds") || strategy.contains("thirds") -> "三分构图"
        strategy.contains("centered") || strategy.contains("symmetry") -> "对称构图"
        strategy.contains("leading") -> "引导线构图"
        strategy.contains("negative_space") -> "留白构图"
        strategy.contains("horizon") -> "水平构图"
        else -> "AI 构图"
    }

    val sceneCN = when {
        sceneType.contains("portrait") || sceneType.contains("person") -> "人像"
        sceneType.contains("landscape") -> "风景"
        sceneType.contains("food") || sceneType.contains("coffee") -> "美食"
        sceneType.contains("bright") -> "明亮"
        sceneType.contains("dark") -> "暗调"
        sceneType.contains("normal") -> "自然光"
        else -> "日常"
    }

    val comment = when {
        score >= 90 -> "构图出色，可以直接分享"
        score >= 75 -> "构图不错，稍作调整会更出彩"
        score >= 60 -> "基本构图达标，可以继续练习"
        else -> "构图还有提升空间，参考建议再拍一张"
    }

    val scoreColor = when {
        score >= 90 -> GuideAligned
        score >= 70 -> Color(0xFFFFC107)
        else -> BrandPink
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(CameraChrome.copy(alpha = 0.95f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部小横条
        Spacer(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.2f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        AsyncImage(
            model = imageUri,
            contentDescription = "刚拍摄的照片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.08f))
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 分数
        Text(
            text = "$score",
            color = scoreColor,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "构图评分",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 构图策略与场景标签
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tag(text = strategyCN)
            Tag(text = sceneCN)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 评语
        Text(
            text = comment,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 操作按钮：照片已经保存，因此这里是完成/重拍/分享，不再误导用户重复保存。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("重拍", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onShareToCommunity,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandPink,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("分享到社区", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSaveToGallery,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White.copy(alpha = 0.72f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("完成并返回相机", fontSize = 13.sp)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
