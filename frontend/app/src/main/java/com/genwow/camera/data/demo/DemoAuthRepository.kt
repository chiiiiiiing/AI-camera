package com.genwow.camera.data.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory demo boundary. Credentials are validated for blanks and never retained. */
class DemoAuthRepository {
    private val mutableSession = MutableStateFlow<DemoAuthSession?>(null)
    val currentSession: StateFlow<DemoAuthSession?> = mutableSession.asStateFlow()

    fun login(account: String, password: String): DemoAuthSession? {
        if (account.isBlank() || password.isBlank()) return null
        return DemoAuthSession(
            userId = "local-demo-user",
            displayName = "本地演示用户",
        ).also { mutableSession.value = it }
    }

    fun register(userName: String, account: String, password: String): DemoAuthSession? {
        if (userName.isBlank() || account.isBlank() || password.isBlank()) return null
        return DemoAuthSession(
            userId = "local-demo-user",
            displayName = userName,
        ).also { mutableSession.value = it }
    }

    fun logout() {
        mutableSession.value = null
    }
}
