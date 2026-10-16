package com.example.aicamera.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.auth.AgreementText
import com.example.aicamera.ui.auth.AuthBrandHeader
import com.example.aicamera.ui.auth.AuthField
import com.example.aicamera.ui.auth.AuthFieldIcon
import com.example.aicamera.ui.auth.BrandPrimaryButton
import com.example.aicamera.ui.auth.LightSystemBars
import com.example.aicamera.ui.auth.VerificationButton
import com.example.aicamera.ui.theme.BrandBackgroundGradient
import com.example.aicamera.ui.theme.BrandPurple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 忘记密码 — GenWow 设计规范。
 *
 * 流程：手机号/邮箱 → 获取验证码（scene=reset）→ 验证码 + 新密码 → 重置成功返回登录。
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onResetSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf("") }
    var verifyCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var devCodeHint by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var sendingCode by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000L)
            countdown--
        }
    }

    val targetValid = PHONE_RE.matches(target) || EMAIL_RE.matches(target)
    val formValid = agreed && targetValid && verifyCode.length == 6 && newPassword.length >= 6

    fun requestCode() {
        if (countdown > 0 || sendingCode) return
        if (!targetValid) {
            errorMessage = "请先输入正确的手机号或邮箱"
            return
        }
        errorMessage = null
        sendingCode = true
        scope.launch {
            when (val r = authRepo.requestCode(target.trim(), "reset")) {
                is AuthRepository.CodeResult.Sent -> {
                    countdown = 60
                    devCodeHint = r.devCode
                }
                is AuthRepository.CodeResult.Error -> errorMessage = r.message
            }
            sendingCode = false
        }
    }

    fun doReset() {
        if (!formValid || isLoading) return
        isLoading = true
        errorMessage = null
        scope.launch {
            when (val r = authRepo.resetPassword(target.trim(), verifyCode, newPassword)) {
                is AuthRepository.ResetResult.Success -> {
                    isLoading = false
                    onResetSuccess()
                }
                is AuthRepository.ResetResult.Error -> {
                    isLoading = false
                    errorMessage = r.message
                }
            }
        }
    }

    LightSystemBars()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandBackgroundGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 52.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuthBrandHeader(title = "忘记密码", subtitle = "通过验证码重置密码")

            Spacer(modifier = Modifier.height(28.dp))

            AuthField(
                value = target,
                onValueChange = { target = it; errorMessage = null },
                placeholder = "输入手机号/邮箱",
                contentDescription = "手机号或邮箱",
                icon = AuthFieldIcon.PHONE,
                keyboardType = KeyboardType.Email,
            )

            Spacer(modifier = Modifier.height(14.dp))

            AuthField(
                value = verifyCode,
                onValueChange = { verifyCode = it.filter { c -> c.isDigit() }.take(6); errorMessage = null },
                placeholder = "输入 6 位验证码",
                contentDescription = "验证码",
                icon = AuthFieldIcon.SHIELD,
                keyboardType = KeyboardType.NumberPassword,
                trailingContent = {
                    VerificationButton(
                        secondsRemaining = countdown,
                        enabled = countdown == 0 && !sendingCode,
                        sending = sendingCode
                    ) { requestCode() }
                }
            )

            devCodeHint?.let { code ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "开发模式验证码：$code（点按填入）",
                        color = BrandPurple,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { verifyCode = code }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AuthField(
                value = newPassword,
                onValueChange = { newPassword = it; errorMessage = null },
                placeholder = "设置新密码（至少 6 位）",
                contentDescription = "新密码",
                icon = AuthFieldIcon.LOCK,
                password = true,
                passwordVisible = showPassword,
                onTogglePasswordVisibility = { showPassword = !showPassword },
                imeAction = ImeAction.Done,
            )

            errorMessage?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(it, color = ERROR_RED, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(26.dp))

            BrandPrimaryButton(
                label = if (isLoading) "重置中…" else "重置密码",
                enabled = formValid && !isLoading,
                onClick = { doReset() }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                "返回登录",
                fontSize = 13.sp,
                color = BrandPurple,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(8.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            AgreementText(
                prefix = "重置密码同意",
                checked = agreed,
                onCheckedChange = { agreed = it }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
