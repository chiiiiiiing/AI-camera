package com.genwow.camera.ui.feed

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Rule
import org.junit.Test

class FeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun feedShowsFigmaTabsPostsAndActions() {
        val repository = DemoCommunityRepository()
        composeRule.setContent {
            GenWowTheme {
                FeedScreen(
                    posts = repository.posts,
                    onToggleLike = repository::toggleLike,
                )
            }
        }

        composeRule.onNodeWithText("关注").assertExists()
        composeRule.onNodeWithText("为你推荐").assertExists()
        composeRule.onNodeWithText("消息").assertExists()
        composeRule.onNodeWithText("Helena").assertExists()
        composeRule.onNodeWithText("Daniel").assertExists()
        composeRule.onNodeWithContentDescription("Helena 的郁金香照片").assertExists()
        composeRule.onNodeWithContentDescription("点赞 Helena").assertExists()
        composeRule.onNodeWithContentDescription("评论 Helena").assertExists()
    }

    @Test
    fun likeOnlyUpdatesTheLocalDemoRepository() {
        val repository = DemoCommunityRepository()
        composeRule.setContent {
            GenWowTheme {
                FeedScreen(
                    posts = repository.posts,
                    onToggleLike = repository::toggleLike,
                )
            }
        }

        composeRule.onNodeWithContentDescription("点赞 Helena").performClick()
        composeRule.onNodeWithText("22 赞").assertExists()
    }
}
