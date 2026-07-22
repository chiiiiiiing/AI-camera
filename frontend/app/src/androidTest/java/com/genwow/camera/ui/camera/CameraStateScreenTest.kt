package com.genwow.camera.ui.camera

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraStateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun permissionScreenExplainsLocalProcessingAndRequestsPermission() {
        var requested = false
        composeRule.setContent {
            GenWowTheme {
                PermissionStateScreen(
                    permanentlyDenied = false,
                    onRequestPermission = { requested = true },
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("开启相机，开始构图").assertExists()
        composeRule.onNodeWithText("帧好只在设备上分析取景画面。照片仅在你按下快门后保存。").assertExists()
        composeRule.onNodeWithText("允许使用相机").performClick()
        composeRule.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun cameraErrorScreenKeepsRetryAction() {
        var retried = false
        composeRule.setContent {
            GenWowTheme {
                ErrorStateScreen(message = "测试相机错误", onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithText("相机暂时不可用").assertExists()
        composeRule.onNodeWithText("测试相机错误").assertExists()
        composeRule.onNodeWithText("重新尝试").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }
}
