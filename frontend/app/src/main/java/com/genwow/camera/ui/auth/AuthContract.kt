package com.genwow.camera.ui.auth

import androidx.compose.runtime.Immutable

@Immutable
data class AuthUiState(
    val userName: String = "",
    val account: String = "",
    val password: String = "",
    val verificationCode: String = "",
    val passwordVisible: Boolean = false,
    val accountError: String? = null,
    val passwordError: String? = null,
    val userNameError: String? = null,
    val verificationCodeError: String? = null,
    val verificationCodeSent: Boolean = false,
    val verificationSecondsRemaining: Int = 0,
) {
    val canSubmitLogin: Boolean
        get() = account.isNotBlank() && password.isNotBlank()

    val canSubmitRegistration: Boolean
        get() = userName.isNotBlank() && account.isNotBlank() &&
            password.isNotBlank() && verificationCode.isNotBlank()

    val canSendVerificationCode: Boolean
        get() = account.isNotBlank() && verificationSecondsRemaining == 0
}

sealed interface AuthEffect {
    data object NavigateToMain : AuthEffect
}
