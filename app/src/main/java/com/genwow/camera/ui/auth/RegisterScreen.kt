package com.genwow.camera.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genwow.camera.data.demo.DemoAuthRepository
import com.genwow.camera.ui.theme.BrandBackgroundGradient
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.GenWowTheme

@Composable
fun RegisterRoute(
    repository: DemoAuthRepository,
    onRegistrationSucceeded: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect == AuthEffect.NavigateToMain) onRegistrationSucceeded()
        }
    }
    RegisterScreen(
        state = state,
        onUserNameChanged = viewModel::updateUserName,
        onPasswordChanged = viewModel::updatePassword,
        onAccountChanged = viewModel::updateAccount,
        onVerificationCodeChanged = viewModel::updateVerificationCode,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onSendVerificationCode = viewModel::sendVerificationCode,
        onSubmit = viewModel::submitRegistration,
    )
}

@Composable
fun RegisterScreen(
    state: AuthUiState,
    onUserNameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onAccountChanged: (String) -> Unit,
    onVerificationCodeChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSendVerificationCode: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LightSystemBars()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandBackgroundGradient)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        Text("注册", color = BrandOnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("创建新账号", color = BrandOnSurface, fontSize = 16.sp)
        Spacer(Modifier.height(82.dp))
        AuthField(
            value = state.userName,
            onValueChange = onUserNameChanged,
            placeholder = "新建用户名",
            contentDescription = "用户名输入框",
            icon = AuthFieldIcon.PERSON,
            error = state.userNameError,
        )
        AuthField(
            value = state.password,
            onValueChange = onPasswordChanged,
            placeholder = "新建密码",
            contentDescription = "密码输入框",
            icon = AuthFieldIcon.LOCK,
            error = state.passwordError,
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisibility = onTogglePasswordVisibility,
        )
        AuthField(
            value = state.account,
            onValueChange = onAccountChanged,
            placeholder = "输入手机号/邮箱",
            contentDescription = "手机号或邮箱输入框",
            icon = AuthFieldIcon.PHONE,
            error = state.accountError,
            keyboardType = KeyboardType.Email,
        )
        AuthField(
            value = state.verificationCode,
            onValueChange = onVerificationCodeChanged,
            placeholder = "输入验证码",
            contentDescription = "验证码输入框",
            icon = AuthFieldIcon.SHIELD,
            error = state.verificationCodeError,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            trailingContent = {
                VerificationButton(
                    secondsRemaining = state.verificationSecondsRemaining,
                    enabled = state.canSendVerificationCode,
                    onClick = onSendVerificationCode,
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "本地演示，不会发送短信",
            color = BrandOnSurfaceMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(Modifier.height(9.dp))
        BrandPrimaryButton(
            label = "注册",
            enabled = state.canSubmitRegistration,
            onClick = onSubmit,
        )
        Spacer(Modifier.height(48.dp))
        AgreementText("注册同意")
        Spacer(Modifier.height(28.dp))
    }
}

@Preview(widthDp = 412, heightDp = 917, showBackground = true)
@Composable
private fun RegisterPreview() = GenWowTheme { RegisterScreen(AuthUiState(), {}, {}, {}, {}, {}, {}, {}) }

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun RegisterSmallPreview() = GenWowTheme { RegisterScreen(AuthUiState(), {}, {}, {}, {}, {}, {}, {}) }
