package com.example.aicamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.BuildConfig
import com.example.aicamera.guidance.CompositionSessionController
import com.example.aicamera.guidance.geometry.HorizonObservation
import com.example.aicamera.overlay.model.RenderInput

/**
 * Debug HUD — 仅 Debug Build 显示。
 *
 * 用于区分 AI 输出问题 / App Controller 问题 / Camera Zoom 问题 / 坐标转换问题。
 * Release build 中不渲染（由 BuildConfig.DEBUG 门控）。
 *
 * 显示内容：
 * - 状态 / 帧 / 推理耗时
 * - 传感器 raw gx/gy/gz（由外部传入）
 * - raw roll / smoothed roll / upright
 * - subject / target / dx/dy
 * - AI suggested zoom / actual zoom
 * - guidance action
 */
@Composable
fun DebugHud(
    modifier: Modifier = Modifier,
    sessionState: CompositionSessionController.State,
    renderInput: RenderInput,
    inferenceType: String = "?",
    horizonObs: HorizonObservation? = null,
    zoomInfo: ZoomDebugInfo = ZoomDebugInfo(),
) {
    // ★ Release 完全隐藏
    if (!BuildConfig.DEBUG) return

    val bg = Color.Black.copy(alpha = 0.65f)
    val fg = Color(0xFF90EE90) // 终端绿
    val dim = Color(0xFF888888)
    val warn = Color(0xFFFFD700)

    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .width(260.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── 状态 ──
        DebugRow("State", sessionState.toString().removePrefix("Adjusting"), fg)

        // ── 传感器 ──
        if (horizonObs != null) {
            DebugRow("Roll", String.format("%.1f°", horizonObs.rollDegrees),
                if (horizonObs.isUpright) fg else warn)
            DebugRow("Upright", horizonObs.isUpright.toString(),
                if (horizonObs.isUpright) fg else warn)
            DebugRow("Conf", String.format("%.2f", horizonObs.confidence), dim)
        }

        // ── 主体 / 目标 ──
        val subjectBox = renderInput.guideLines.find { it.type.name == "SUBJECT_BOX" }
        val targetZone = renderInput.guideLines.find { it.type.name == "TARGET_ZONE" }
        val isReal = subjectBox?.params?.get("real") == true

        if (subjectBox != null) {
            val sx = (subjectBox.params["x"] as? Number)?.toFloat()
            val sy = (subjectBox.params["y"] as? Number)?.toFloat()
            val sw = (subjectBox.params["width"] as? Number)?.toFloat()
            val sh = (subjectBox.params["height"] as? Number)?.toFloat()
            val scx = if (sx != null && sw != null) sx + sw / 2f else null
            val scy = if (sy != null && sh != null) sy + sh / 2f else null
            DebugRow("Subject", if (scx != null) String.format("(%.2f, %.2f) %s",
                scx, scy, if (isReal) "REAL" else "PLACEHOLDER")
            else "—", if (isReal) fg else warn)
        }

        if (targetZone != null) {
            val tcx = (targetZone.params["center_x"] as? Number)?.toFloat()
            val tcy = (targetZone.params["center_y"] as? Number)?.toFloat()
            DebugRow("Target", if (tcx != null) String.format("(%.2f, %.2f)", tcx, tcy) else "—", fg)
        }

        // dx/dy
        val subjectBox2 = renderInput.guideLines.find { it.type.name == "SUBJECT_BOX" }
        val targetZone2 = renderInput.guideLines.find { it.type.name == "TARGET_ZONE" }
        if (subjectBox2 != null && targetZone2 != null &&
            subjectBox2.params["real"] == true
        ) {
            val sx = (subjectBox2.params["x"] as? Number)?.toFloat()
            val sy = (subjectBox2.params["y"] as? Number)?.toFloat()
            val sw = (subjectBox2.params["width"] as? Number)?.toFloat()
            val sh = (subjectBox2.params["height"] as? Number)?.toFloat()
            val tcx = (targetZone2.params["center_x"] as? Number)?.toFloat()
            val tcy = (targetZone2.params["center_y"] as? Number)?.toFloat()
            if (sx != null && sw != null && tcx != null) {
                val scx = sx + sw / 2f
                val scy = (sy ?: 0f) + (sh ?: 0f) / 2f
                DebugRow("dX/dY", String.format("(%.3f, %.3f)", scx - tcx,
                    scy - (tcy ?: 0.5f)), dim)
            }
        }

        // ── Zoom ──
        DebugRow("Zoom", String.format("UI=%.2f actual=%.2f",
            zoomInfo.uiZoom, zoomInfo.actualZoom), dim)
        DebugRow("Engine", inferenceType, dim)

        // ── 分隔线 ──
        Spacer(modifier = Modifier.height(2.dp))
        Text("─── Debug HUD (Release: hidden) ───",
            color = Color(0xFF666666), fontSize = 8.sp)
    }
}

data class ZoomDebugInfo(
    val uiZoom: Float = 1.0f,
    val actualZoom: Float = 1.0f
)

@Composable
private fun DebugRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFFAAAAAA), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
        Text(text = value, color = color, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}
