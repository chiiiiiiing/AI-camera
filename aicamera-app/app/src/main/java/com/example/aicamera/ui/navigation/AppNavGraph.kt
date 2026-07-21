package com.example.aicamera.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.screen.*
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.ui.theme.AiCameraTheme
import com.example.aicamera.ui.theme.LoginTheme
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.PipelineViewModel
import com.example.aicamera.viewmodel.RenderViewModel

/**
 * 导航路由表
 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val GALLERY = "gallery"
}

/**
 * App 导航图
 *
 * 路由流程：
 * ```
 * Splash (2s) → 检查登录态
 *   ├── 已登录 → Main (底部三 Tab)
 *   └── 未登录 → Login ←→ Register
 *                     ↓
 *                  Main (底部三 Tab)
 *
 * Main → Settings / Gallery (全屏 overlay)
 * ```
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    cameraViewModel: CameraViewModel,
    pipelineViewModel: PipelineViewModel,
    renderViewModel: RenderViewModel
) {
    // ── 设置状态（全局，跨页面保持） ──
    var settings by remember { mutableStateOf(SettingsState()) }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        // ── 启动页 ──
        composable(Routes.SPLASH) {
            LoginTheme {
                SplashScreen(
                    onSplashFinished = {
                        val authRepo = AuthRepository.getInstance(
                            cameraViewModel.getApplication()
                        )
                        if (authRepo.isLoggedIn) {
                            navController.navigate(Routes.MAIN) {
                                popUpTo(Routes.SPLASH) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(Routes.SPLASH) { inclusive = true }
                            }
                        }
                    }
                )
            }
        }

        // ── 登录页 ──
        composable(Routes.LOGIN) {
            LoginTheme {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = {
                        navController.navigate(Routes.REGISTER)
                    }
                )
            }
        }

        // ── 注册页 ──
        composable(Routes.REGISTER) {
            LoginTheme {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onNavigateToLogin = {
                        navController.popBackStack()
                    }
                )
            }
        }

        // ── 主页面（底部三 Tab）─ 深色主题 ──
        composable(Routes.MAIN) {
            AiCameraTheme {
                MainScreen(
                    cameraViewModel = cameraViewModel,
                    pipelineViewModel = pipelineViewModel,
                    renderViewModel = renderViewModel,
                    onNavigateToSettings = {
                        navController.navigate(Routes.SETTINGS)
                    },
                    onNavigateToGallery = {
                        navController.navigate(Routes.GALLERY)
                    },
                    settings = settings,
                    onSettingsChange = { settings = it }
                )
            }
        }

        // ── 设置页 ──
        composable(Routes.SETTINGS) {
            AiCameraTheme {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLogout = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    settings = settings,
                    onSettingsChange = { settings = it }
                )
            }
        }

        // ── 相册页 ──
        composable(Routes.GALLERY) {
            AiCameraTheme {
                GalleryScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
