package com.genwow.camera.ui.auth

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegisterScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun verificationActionIsExplicitlyLocalDemo() {
        var requests = 0
        composeRule.setContent {
            GenWowTheme {
                RegisterScreen(
                    state = AuthUiState(account = "13800000000"),
                    onUserNameChanged = {},
                    onPasswordChanged = {},
                    onAccountChanged = {},
                    onVerificationCodeChanged = {},
                    onTogglePasswordVisibility = {},
                    onSendVerificationCode = { requests++ },
                    onSubmit = {},
                )
            }
        }

        composeRule.onNodeWithText("本地演示，不会发送短信").assertExists()
        composeRule.onNodeWithText("获取验证码").performClick()
        assertEquals(1, requests)
    }
}
