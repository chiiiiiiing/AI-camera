package com.genwow.camera.ui.splash

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Rule
import org.junit.Test

class SplashScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun splashShowsOfficialBrand() {
        composeRule.setContent { GenWowTheme { SplashScreen(animate = false) } }

        composeRule.onNodeWithContentDescription("帧好 GenWow 标志").assertExists()
        composeRule.onNodeWithText("帧好").assertExists()
        composeRule.onNodeWithText("GenWow").assertExists()
    }
}
