package com.genwow.camera.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.genwow.camera.data.demo.DemoAuthRepository
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.ui.camera.CameraRoute
import com.genwow.camera.ui.auth.LoginRoute
import com.genwow.camera.ui.auth.RegisterRoute
import com.genwow.camera.ui.feed.FeedScreen
import com.genwow.camera.ui.profile.ProfileScreen
import com.genwow.camera.ui.splash.SplashScreen
import kotlinx.coroutines.delay

@Composable
fun GenWowNavHost(
    authRepository: DemoAuthRepository,
    communityRepository: DemoCommunityRepository,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: AppDestination = AppDestination.Splash,
    splashDurationMillis: Long = 700,
    cameraContent: @Composable ((() -> Unit), (() -> Unit)) -> Unit = { onFeed, onProfile ->
        CameraRoute(onOpenFeed = onFeed, onOpenProfile = onProfile)
    },
) {
    NavHost(
        navController = navController,
        startDestination = startDestination.route,
        modifier = modifier,
    ) {
        composable(AppDestination.Splash.route) {
            SplashScreen()
            LaunchedEffect(splashDurationMillis) {
                if (splashDurationMillis > 0) delay(splashDurationMillis)
                navController.navigate(AppDestination.Login.route) {
                    popUpTo(AppDestination.Splash.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        composable(AppDestination.Login.route) {
            LoginRoute(
                repository = authRepository,
                onLoginSucceeded = {
                    navController.openMainDestination(AppDestination.Camera, clearAuth = true)
                },
                onRegister = {
                    navController.navigate(AppDestination.Register.route)
                },
            )
        }
        composable(AppDestination.Register.route) {
            RegisterRoute(
                repository = authRepository,
                onRegistrationSucceeded = {
                    navController.openMainDestination(AppDestination.Camera, clearAuth = true)
                },
            )
        }
        composable(AppDestination.Camera.route) {
            cameraContent(
                { navController.openMainDestination(AppDestination.Feed) },
                { navController.openMainDestination(AppDestination.Profile) },
            )
        }
        composable(AppDestination.Feed.route) {
            MainDestinationScaffold(
                currentDestination = AppDestination.Feed,
                navController = navController,
            ) {
                FeedScreen(
                    posts = communityRepository.posts,
                    onToggleLike = communityRepository::toggleLike,
                )
            }
        }
        composable(AppDestination.Profile.route) {
            MainDestinationScaffold(
                currentDestination = AppDestination.Profile,
                navController = navController,
            ) {
                ProfileScreen(profile = communityRepository.profile)
            }
        }
    }
}

@Composable
private fun MainDestinationScaffold(
    currentDestination: AppDestination,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        MainBottomBar(
            currentDestination = currentDestination,
            onDestinationSelected = { destination ->
                if (destination != currentDestination) {
                    navController.openMainDestination(destination)
                }
            },
        )
    }
}

private fun NavHostController.openMainDestination(
    destination: AppDestination,
    clearAuth: Boolean = false,
) {
    navigate(destination.route) {
        if (clearAuth) {
            popUpTo(AppDestination.Login.route) { inclusive = true }
        } else {
            popUpTo(AppDestination.Camera.route) { saveState = true }
        }
        launchSingleTop = true
        restoreState = !clearAuth
    }
}
