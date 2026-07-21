package com.genwow.camera.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    cameraContent: @Composable ((() -> Unit), (() -> Unit), (() -> Unit)) -> Unit = { onFeed, onProfile, onEntryFlow ->
        CameraRoute(onOpenFeed = onFeed, onOpenProfile = onProfile, onOpenEntryFlow = onEntryFlow)
    },
) {
    val currentSession by authRepository.currentSession.collectAsStateWithLifecycle()
    NavHost(
        navController = navController,
        startDestination = startDestination.route,
        modifier = modifier,
    ) {
        composable(AppDestination.Splash.route) {
            SplashScreen()
            LaunchedEffect(splashDurationMillis) {
                if (splashDurationMillis > 0) delay(splashDurationMillis)
                navController.navigate(AppDestination.Camera.route) {
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
                {
                    navController.navigateToProfileOrAuth(
                        authenticated = currentSession != null,
                    )
                },
                {
                    navController.navigate(AppDestination.Splash.route) {
                        popUpTo(AppDestination.Camera.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(AppDestination.Feed.route) {
            MainDestinationScaffold(
                currentDestination = AppDestination.Feed,
                onDestinationSelected = { destination ->
                    navController.navigateMainDestination(
                        destination = destination,
                        authenticated = currentSession != null,
                    )
                },
            ) {
                FeedScreen(
                    posts = communityRepository.posts,
                    onToggleLike = communityRepository::toggleLike,
                )
            }
        }
        composable(AppDestination.Profile.route) {
            if (currentSession == null) {
                LoginRoute(
                    repository = authRepository,
                    onLoginSucceeded = {
                        navController.openMainDestination(AppDestination.Profile, clearAuth = true)
                    },
                    onRegister = {
                        navController.navigate(AppDestination.Register.route)
                    },
                )
            } else {
                MainDestinationScaffold(
                    currentDestination = AppDestination.Profile,
                    onDestinationSelected = { destination ->
                        navController.navigateMainDestination(
                            destination = destination,
                            authenticated = true,
                        )
                    },
                ) {
                    ProfileScreen(
                        profile = communityRepository.profile,
                        onOpenEntryFlow = { navController.navigate(AppDestination.Login.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainDestinationScaffold(
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        MainBottomBar(
            currentDestination = currentDestination,
            onDestinationSelected = onDestinationSelected,
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

private fun NavHostController.navigateMainDestination(
    destination: AppDestination,
    authenticated: Boolean,
) {
    when {
        destination == AppDestination.Profile -> navigateToProfileOrAuth(authenticated)
        else -> openMainDestination(destination)
    }
}

private fun NavHostController.navigateToProfileOrAuth(authenticated: Boolean) {
    if (authenticated) {
        openMainDestination(AppDestination.Profile)
    } else {
        openMainDestination(AppDestination.Profile)
    }
}
