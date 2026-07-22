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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.ui.theme.BrandBackgroundGradient
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.GenWowTheme
import kotlinx.coroutines.delay

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LightSystemBars()
    var account by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining -= 1
        }
    }

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
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "返回登录",
                color = BrandOnSurfaceMuted,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        AuthBrandHeader(
            title = "忘记密码",
            subtitle = "本地演示流程，不会发起真实短信或邮件",
        )
        Spacer(Modifier.height(48.dp))
        AuthField(
            value = account,
            onValueChange = { account = it },
            placeholder = "输入手机号/邮箱",
            contentDescription = "找回账号输入框",
            icon = AuthFieldIcon.PHONE,
            keyboardType = KeyboardType.Email,
        )
        AuthField(
            value = verificationCode,
            onValueChange = { verificationCode = it },
            placeholder = "输入验证码",
            contentDescription = "找回验证码输入框",
            icon = AuthFieldIcon.SHIELD,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
            trailingContent = {
                VerificationButton(
                    secondsRemaining = secondsRemaining,
                    enabled = secondsRemaining == 0 && account.isNotBlank(),
                    onClick = { secondsRemaining = 60 },
                )
            },
        )
        AuthField(
            value = newPassword,
            onValueChange = { newPassword = it },
            placeholder = "设置新密码",
            contentDescription = "新密码输入框",
            icon = AuthFieldIcon.LOCK,
            password = true,
            passwordVisible = passwordVisible,
            onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
            imeAction = ImeAction.Done,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "如需正式找回密码，可在后端接入后替换此本地流程。",
            color = BrandOnSurfaceMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(Modifier.height(20.dp))
        BrandPrimaryButton(
            label = "确认重置",
            enabled = account.isNotBlank() && verificationCode.isNotBlank() && newPassword.length >= 6,
            onClick = onBack,
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Preview(widthDp = 412, heightDp = 917, showBackground = true)
@Composable
private fun ForgotPasswordPreview() = GenWowTheme { ForgotPasswordScreen(onBack = {}) }
