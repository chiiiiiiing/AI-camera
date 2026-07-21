package com.genwow.camera.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.genwow.camera.data.demo.DemoAuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repository: DemoAuthRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<AuthEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private var verificationJob: Job? = null

    fun updateUserName(value: String) = updateState {
        copy(userName = value, userNameError = null)
    }

    fun updateAccount(value: String) = updateState {
        copy(account = value, accountError = null)
    }

    fun updatePassword(value: String) = updateState {
        copy(password = value, passwordError = null)
    }

    fun updateVerificationCode(value: String) = updateState {
        copy(verificationCode = value, verificationCodeError = null)
    }

    fun togglePasswordVisibility() = updateState {
        copy(passwordVisible = !passwordVisible)
    }

    fun submitLogin() {
        val state = mutableUiState.value
        val accountError = if (state.account.isBlank()) "请输入手机号或邮箱" else null
        val passwordError = if (state.password.isBlank()) "请输入密码" else null
        if (accountError != null || passwordError != null) {
            mutableUiState.value = state.copy(
                accountError = accountError,
                passwordError = passwordError,
            )
            return
        }
        if (repository.login(state.account, state.password) != null) {
            mutableUiState.value = state.copy(password = "")
            effectChannel.trySend(AuthEffect.NavigateToMain)
        }
    }

    fun submitRegistration() {
        val state = mutableUiState.value
        val next = state.copy(
            userNameError = if (state.userName.isBlank()) "请输入用户名" else null,
            passwordError = if (state.password.isBlank()) "请输入密码" else null,
            accountError = if (state.account.isBlank()) "请输入手机号或邮箱" else null,
            verificationCodeError = if (state.verificationCode.isBlank()) "请输入验证码" else null,
        )
        mutableUiState.value = next
        if (!next.canSubmitRegistration) return
        if (repository.register(next.userName, next.account, next.password) != null) {
            mutableUiState.value = next.copy(password = "", verificationCode = "")
            effectChannel.trySend(AuthEffect.NavigateToMain)
        }
    }

    /** Local-only countdown. No SMS or network request is performed. */
    fun sendVerificationCode() {
        val state = mutableUiState.value
        if (!state.canSendVerificationCode || verificationJob?.isActive == true) return
        mutableUiState.value = state.copy(
            verificationCodeSent = true,
            verificationSecondsRemaining = 60,
            accountError = null,
        )
        verificationJob = viewModelScope.launch {
            while (mutableUiState.value.verificationSecondsRemaining > 0) {
                delay(1_000)
                updateState {
                    copy(verificationSecondsRemaining = (verificationSecondsRemaining - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private inline fun updateState(transform: AuthUiState.() -> AuthUiState) {
        mutableUiState.value = mutableUiState.value.transform()
    }

    companion object {
        fun factory(repository: DemoAuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AuthViewModel(repository) as T
            }
    }
}
