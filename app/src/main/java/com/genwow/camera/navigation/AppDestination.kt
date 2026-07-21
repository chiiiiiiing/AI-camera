package com.genwow.camera.navigation

enum class AppDestination(
    val route: String,
    val label: String,
) {
    Splash("splash", "启动"),
    Login("login", "登录"),
    Register("register", "注册"),
    Camera("camera", "相机"),
    Feed("feed", "推荐"),
    Profile("profile", "主页"),
    ;

    companion object {
        val mainDestinations = listOf(Camera, Feed, Profile)
    }
}
