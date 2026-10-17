package com.example.aicamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.guidance.PhotographyTechniqueAdvice

/** A quiet optional suggestion, with no button, direction animation or completion signal. */
@Composable
fun PhotographyTechniqueCard(advice: PhotographyTechniqueAdvice, modifier: Modifier = Modifier) {
    val label = "拍摄技巧 · 可尝试"
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(14.dp))
            .clearAndSetSemantics {
                // Read the full suggestion even when compact preview text is ellipsized.
                contentDescription = "$label。${advice.title}。${advice.suggestion}"
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = .72f), fontSize = 11.sp,
            lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(advice.title, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(advice.suggestion, color = Color.White.copy(alpha = .9f), fontSize = 12.sp,
            lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
