package com.genwow.camera.ui.auth

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loginRequiresAccountAndPasswordThenSubmits() {
        var state by mutableStateOf(AuthUiState())
        var submissions = 0
        composeRule.setContent {
            GenWowTheme {
                LoginScreen(
                    state = state,
                    onAccountChanged = { state = state.copy(account = it) },
                    onPasswordChanged = { state = state.copy(password = it) },
                    onTogglePasswordVisibility = {},
                    onSubmit = { submissions++ },
                    onRegister = {},
                    onForgotPassword = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("登录").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("手机号或邮箱输入框")
            .performTextInput("demo@genwow.local")
        composeRule.onNodeWithContentDescription("密码输入框").performTextInput("local-only")
        composeRule.onNodeWithContentDescription("登录").assertIsEnabled().performClick()

        assertEquals(1, submissions)
    }

    @Test
    fun thirdPartyProvidersUseIconsInsteadOfVisibleTextLabels() {
        composeRule.setContent {
            GenWowTheme { LoginScreen(AuthUiState(), {}, {}, {}, {}, {}, {}) }
        }

        composeRule.onNodeWithContentDescription("微信登录（未接入）").assertExists()
        composeRule.onNodeWithContentDescription("QQ 登录（未接入）").assertExists()
        composeRule.onNodeWithText("微信").assertDoesNotExist()
        composeRule.onNodeWithText("QQ").assertDoesNotExist()
    }
}
