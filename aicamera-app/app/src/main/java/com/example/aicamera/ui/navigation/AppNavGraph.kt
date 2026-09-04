package com.example.aicamera.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.data.SettingsRepository
import com.example.aicamera.ui.screen.*
import com.example.aicamera.ui.state.SettingsState
import com.example.aicamera.ui.theme.GenWowTheme
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.CommunityViewModel
import com.example.aicamera.viewmodel.PipelineViewModel
import com.example.aicamera.viewmodel.RenderViewModel
import androidx.navigation.navArgument
import kotlinx.coroutines.launch

/**
 * 导航路由表
 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT_PASSWORD = "forgot_password"
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val EDIT_PROFILE = "edit_profile"
    const val PROFILE = "profile"
    const val FOLLOWERS = "followers"
    const val FOLLOWING = "following"
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
 * Main → Settings (全屏 overlay)；相册走系统 Intent
 * ```
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    cameraViewModel: CameraViewModel,
    pipelineViewModel: PipelineViewModel,
    renderViewModel: RenderViewModel,
    settingsRepository: SettingsRepository
) {
    // ── 设置状态（DataStore 持久化，进程重启后保留；修复内存态丢失问题） ──
    val settings by settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = SettingsState())
    val scope = rememberCoroutineScope()
    val onSettingsChange: (SettingsState) -> Unit = { s ->
        scope.launch { settingsRepository.update(s) }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        // ── 启动页 ──
        composable(Routes.SPLASH) {
            GenWowTheme {
                SplashScreen(
                    onSplashFinished = {
                        val authRepo = AuthRepository.getInstance(
                            navController.context.applicationContext
                        )
                        // 已登录 → 进相机；首次启动 → 游客模式直接进相机
                        if (authRepo.isLoggedIn || authRepo.isFirstLaunch) {
                            if (!authRepo.isLoggedIn) {
                                // 首次启动游客模式：创建本地会话，避免二次启动强制登录
                                authRepo.createGuestSession()
                            }
                            authRepo.markFirstLaunchDone()
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
            GenWowTheme {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = {
                        navController.navigate(Routes.REGISTER)
                    },
                    onNavigateToForgotPassword = {
                        navController.navigate(Routes.FORGOT_PASSWORD)
                    }
                )
            }
        }

        // ── 忘记密码页 ──
        composable(Routes.FORGOT_PASSWORD) {
            GenWowTheme {
                ForgotPasswordScreen(
                    onBack = { navController.popBackStack() },
                    onResetSuccess = { navController.popBackStack() }
                )
            }
        }

        // ── 注册页 ──
        composable(Routes.REGISTER) {
            GenWowTheme {
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

        // ── 主页面（底部三 Tab） ──
        composable(Routes.MAIN) {
            val context = LocalContext.current
            GenWowTheme {
                MainScreen(
                    cameraViewModel = cameraViewModel,
                    pipelineViewModel = pipelineViewModel,
                    renderViewModel = renderViewModel,
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToGallery = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    onNavigateToEditProfile = { navController.navigate(Routes.EDIT_PROFILE) },
                    onNavigateToProfile = { username ->
                        if (username.isNotBlank()) {
                            navController.navigate("${Routes.PROFILE}?username=${Uri.encode(username)}")
                        }
                    },
                    onNavigateToFollowers = { username ->
                        if (username.isNotBlank()) {
                            navController.navigate("${Routes.FOLLOWERS}/${Uri.encode(username)}")
                        }
                    },
                    onNavigateToFollowing = { username ->
                        if (username.isNotBlank()) {
                            navController.navigate("${Routes.FOLLOWING}/${Uri.encode(username)}")
                        }
                    },
                    onLogout = {
                        navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    },
                    settings = settings,
                    onSettingsChange = onSettingsChange
                )
            }
        }

        // ── 设置页 ──
        composable(Routes.SETTINGS) {
            GenWowTheme {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLogout = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    settings = settings,
                    onSettingsChange = onSettingsChange
                )
            }
        }

        // ── 编辑资料页 ──
        composable(Routes.EDIT_PROFILE) {
            GenWowTheme {
                EditProfileScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // ── 用户主页（含机器人主页） ──
        composable(
            route = "${Routes.PROFILE}?username={username}",
            arguments = listOf(
                navArgument("username") { nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username")
            val context = LocalContext.current
            GenWowTheme {
                val communityViewModel: CommunityViewModel = hiltViewModel()
                val authRepo = remember { AuthRepository.getInstance(context) }
                ProfileScreen(
                    profileUsername = username,
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToEditProfile = { navController.navigate(Routes.EDIT_PROFILE) },
                    onNavigateToFollowers = {
                        if (it.isNotBlank()) navController.navigate("${Routes.FOLLOWERS}/${Uri.encode(it)}")
                    },
                    onNavigateToFollowing = {
                        if (it.isNotBlank()) navController.navigate("${Routes.FOLLOWING}/${Uri.encode(it)}")
                    },
                    onNavigateToProfile = {
                        if (it.isNotBlank()) navController.navigate("${Routes.PROFILE}?username=${Uri.encode(it)}")
                    },
                    onLogout = {
                        navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    },
                    communityViewModel = communityViewModel
                )
            }
        }

        // ── 粉丝列表 ──
        composable(
            route = "${Routes.FOLLOWERS}/{username}",
            arguments = listOf(navArgument("username") { nullable = false })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: ""
            val context = LocalContext.current
            GenWowTheme {
                val communityViewModel: CommunityViewModel = hiltViewModel()
                val authRepo = remember { AuthRepository.getInstance(context) }
                UserListScreen(
                    listType = "followers",
                    username = username,
                    currentUser = authRepo.currentUsername ?: "摄影师",
                    onBack = { navController.popBackStack() },
                    onNavigateToProfile = { navController.navigate("${Routes.PROFILE}?username=$it") },
                    viewModel = communityViewModel
                )
            }
        }

        // ── 关注列表 ──
        composable(
            route = "${Routes.FOLLOWING}/{username}",
            arguments = listOf(navArgument("username") { nullable = false })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: ""
            val context = LocalContext.current
            GenWowTheme {
                val communityViewModel: CommunityViewModel = hiltViewModel()
                val authRepo = remember { AuthRepository.getInstance(context) }
                UserListScreen(
                    listType = "following",
                    username = username,
                    currentUser = authRepo.currentUsername ?: "摄影师",
                    onBack = { navController.popBackStack() },
                    onNavigateToProfile = { navController.navigate("${Routes.PROFILE}?username=$it") },
                    viewModel = communityViewModel
                )
            }
        }
    }
}
