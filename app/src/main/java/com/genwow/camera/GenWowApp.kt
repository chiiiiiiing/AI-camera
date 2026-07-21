package com.genwow.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.genwow.camera.data.demo.DemoAuthRepository
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.navigation.AppDestination
import com.genwow.camera.navigation.GenWowNavHost
import com.genwow.camera.ui.theme.GenWowTheme

@Composable
fun GenWowApp(
    startDestination: AppDestination = AppDestination.Splash,
    splashDurationMillis: Long = 700,
) {
    val authRepository = remember { DemoAuthRepository() }
    val communityRepository = remember { DemoCommunityRepository() }

    GenWowTheme {
        GenWowNavHost(
            authRepository = authRepository,
            communityRepository = communityRepository,
            startDestination = startDestination,
            splashDurationMillis = splashDurationMillis,
        )
    }
}
