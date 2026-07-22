package com.genwow.camera.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.genwow.camera.GenWowApp
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.genwow.camera.data.demo.DemoAuthRepository
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.ui.theme.GenWowTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class GenWowNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun splashFinishesAtLogin() {
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            GenWowApp(
                startDestination = AppDestination.Splash,
                splashDurationMillis = 0,
            )
        }

        composeRule.onNodeWithContentDescription("打开相机").assertExists()
    }

    @Test
    fun demoLoginOpensCameraAndMainDestinationsSwitch() {
        val authRepository = DemoAuthRepository()
        composeRule.setContent {
            GenWowTheme {
                GenWowNavHost(
                    authRepository = authRepository,
                    communityRepository = DemoCommunityRepository(),
                    startDestination = AppDestination.Login,
                    cameraContent = { onFeed, onProfile, _ ->
                        Row {
                            Text("相机", Modifier.semantics { contentDescription = "打开相机" })
                            Text("推荐", Modifier.semantics { contentDescription = "打开推荐" }.clickable(onClick = onFeed))
                            Text("主页", Modifier.semantics { contentDescription = "打开个人主页" }.clickable(onClick = onProfile))
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("手机号或邮箱输入框")
            .performTextInput("demo@genwow.local")
        composeRule.onNodeWithContentDescription("密码输入框").performTextInput("local-only")
        composeRule.onNodeWithContentDescription("登录").performClick()
        composeRule.onNodeWithContentDescription("打开相机").assertExists()

        composeRule.onNodeWithContentDescription("打开推荐").performClick()
        composeRule.onNodeWithText("Helena").assertExists()

        composeRule.onNodeWithContentDescription("打开个人主页").performClick()
        composeRule.onNodeWithText("摄影师").assertExists()
    }

    @Test
    fun leavingCameraDisposesCameraDestinationComposition() {
        var cameraDisposed = false
        lateinit var openFeed: () -> Unit
        composeRule.setContent {
            GenWowTheme {
                GenWowNavHost(
                    authRepository = DemoAuthRepository(),
                    communityRepository = DemoCommunityRepository(),
                    startDestination = AppDestination.Camera,
                    cameraContent = { onFeed, _, _ ->
                        openFeed = onFeed
                        DisposableEffect(Unit) {
                            onDispose { cameraDisposed = true }
                        }
                        Text("测试相机内容")
                    },
                )
            }
        }

        composeRule.runOnIdle { openFeed() }
        composeRule.runOnIdle { assertTrue(cameraDisposed) }
    }

    @Test
    fun successfulLoginRemovesAuthenticationBackStack() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            GenWowTheme {
                GenWowNavHost(
                    authRepository = DemoAuthRepository(),
                    communityRepository = DemoCommunityRepository(),
                    navController = navController,
                    startDestination = AppDestination.Login,
                    cameraContent = { _, _, _ -> Text("测试相机内容") },
                )
            }
        }

        composeRule.onNodeWithContentDescription("手机号或邮箱输入框")
            .performTextInput("demo@genwow.local")
        composeRule.onNodeWithContentDescription("密码输入框").performTextInput("local-only")
        composeRule.onNodeWithContentDescription("登录").performClick()
        composeRule.runOnIdle { assertFalse(navController.popBackStack()) }
        composeRule.onNodeWithText("测试相机内容").assertExists()
    }

    @Test
    fun unauthenticatedProfileRouteShowsLoginAndSuccessfulLoginOpensProfile() {
        val authRepository = DemoAuthRepository()
        composeRule.setContent {
            GenWowTheme {
                GenWowNavHost(
                    authRepository = authRepository,
                    communityRepository = DemoCommunityRepository(),
                    startDestination = AppDestination.Profile,
                )
            }
        }

        composeRule.onNodeWithContentDescription("登录").assertExists()
        composeRule.onNodeWithContentDescription("手机号或邮箱输入框")
            .performTextInput("demo@genwow.local")
        composeRule.onNodeWithContentDescription("密码输入框").performTextInput("local-only")
        composeRule.onNodeWithContentDescription("登录").performClick()
        composeRule.onNodeWithText("我的主页").assertExists()
    }

    @Test
    fun authenticatedProfileRouteShowsProfileContent() {
        val authRepository = DemoAuthRepository().apply {
            login("demo@genwow.local", "local-only")
        }
        composeRule.setContent {
            GenWowTheme {
                GenWowNavHost(
                    authRepository = authRepository,
                    communityRepository = DemoCommunityRepository(),
                    startDestination = AppDestination.Profile,
                )
            }
        }

        composeRule.onNodeWithText("我的主页").assertExists()
        composeRule.onNodeWithText("摄影师").assertExists()
    }
}
