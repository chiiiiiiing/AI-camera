package com.genwow.camera.ui.profile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Rule
import org.junit.Test

class ProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun profileShowsFigmaIdentityStatsGalleryAndSettings() {
        val profile = DemoCommunityRepository().profile
        composeRule.setContent {
            GenWowTheme { ProfileScreen(profile = profile, onOpenEntryFlow = {}) }
        }

        composeRule.onNodeWithText("摄影师").assertExists()
        composeRule.onNodeWithText("用 AI 构图，记录每一帧美好").assertExists()
        composeRule.onNodeWithText("128").assertExists()
        composeRule.onNodeWithText("256").assertExists()
        composeRule.onNodeWithText("64").assertExists()
        composeRule.onNodeWithContentDescription("128 作品").assertExists()
        composeRule.onNodeWithContentDescription("256 粉丝").assertExists()
        composeRule.onNodeWithContentDescription("64 关注").assertExists()
        composeRule.onNodeWithContentDescription("主页设置").assertExists()
        profile.galleryItems.forEach { item ->
            composeRule.onNodeWithContentDescription(item.contentDescription).assertExists()
        }
    }
}
