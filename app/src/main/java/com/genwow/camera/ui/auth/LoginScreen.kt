package com.genwow.camera.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.genwow.camera.ui.theme.BrandPurple
import com.genwow.camera.ui.theme.GenWowTheme

@Composable
fun LoginRoute(
    repository: DemoAuthRepository,
    onLoginSucceeded: () -> Unit,
    onRegister: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect == AuthEffect.NavigateToMain) onLoginSucceeded()
        }
    }
    LoginScreen(
        state = state,
        onAccountChanged = viewModel::updateAccount,
        onPasswordChanged = viewModel::updatePassword,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onSubmit = viewModel::submitLogin,
        onRegister = onRegister,
    )
}

@Composable
fun LoginScreen(
    state: AuthUiState,
    onAccountChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSubmit: () -> Unit,
    onRegister: () -> Unit,
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
        Spacer(Modifier.height(145.dp))
        Text("登录", color = BrandOnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(102.dp))
        AuthField(
            value = state.account,
            onValueChange = onAccountChanged,
            placeholder = "输入手机号/邮箱",
            contentDescription = "手机号或邮箱输入框",
            icon = AuthFieldIcon.PERSON,
            error = state.accountError,
            keyboardType = KeyboardType.Email,
        )
        AuthField(
            value = state.password,
            onValueChange = onPasswordChanged,
            placeholder = "输入密码",
            contentDescription = "密码输入框",
            icon = AuthFieldIcon.LOCK,
            error = state.passwordError,
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisibility = onTogglePasswordVisibility,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("忘记密码", color = BrandOnSurfaceMuted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "注册账号",
                color = BrandOnSurfaceMuted,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onRegister).padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(42.dp))
        BrandPrimaryButton(
            label = "登录",
            enabled = state.canSubmitLogin,
            onClick = onSubmit,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(108.dp))
        ThirdPartyLoginRow()
        Spacer(Modifier.height(22.dp))
        AgreementText("登录同意")
        Spacer(Modifier.height(28.dp))
    }
}

@Preview(widthDp = 412, heightDp = 917, showBackground = true)
@Composable
private fun LoginPreview() = GenWowTheme { LoginScreen(AuthUiState(), {}, {}, {}, {}, {}) }

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun LoginSmallPreview() = GenWowTheme { LoginScreen(AuthUiState(), {}, {}, {}, {}, {}) }
