package com.example.aicamera.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.R
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.auth.AgreementText
import com.example.aicamera.ui.auth.AuthBrandHeader
import com.example.aicamera.ui.auth.AuthField
import com.example.aicamera.ui.auth.AuthFieldIcon
import com.example.aicamera.ui.auth.BrandPrimaryButton
import com.example.aicamera.ui.auth.LightSystemBars
import com.example.aicamera.ui.auth.VerificationButton
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPurple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 注册页 — GenWow 设计规范。
 *
 * 流程（对齐 Figma 创建新账号画板）：
 * 用户名 → 密码 → 手机号/邮箱 → 获取验证码（60s 倒计时）→ 注册
 *
 * 验证码为服务器真随机码（5 分钟有效），开发模式经 devCode 展示；
 * 服务器离线时本地兜底生成同策略验证码。
 */
@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }          // 手机号或邮箱
    var verifyCode by remember { mutableStateOf("") }
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
    val formValid = agreed && username.length in 2..20 && password.length >= 6 && targetValid && verifyCode.length == 6

    fun requestCode() {
        if (countdown > 0 || sendingCode) return
        if (!targetValid) {
            errorMessage = "请先输入正确的手机号或邮箱"
            return
        }
        errorMessage = null
        sendingCode = true
        scope.launch {
            when (val r = authRepo.requestCode(target.trim(), "register")) {
                is AuthRepository.CodeResult.Sent -> {
                    countdown = 60
                    devCodeHint = r.devCode
                }
                is AuthRepository.CodeResult.Error -> errorMessage = r.message
            }
            sendingCode = false
        }
    }

    fun doRegister() {
        if (!formValid || isLoading) return
        isLoading = true
        errorMessage = null
        scope.launch {
            val codeOk = authRepo.verifyCode(target.trim(), "register", verifyCode)
            if (!codeOk) {
                isLoading = false
                errorMessage = "验证码错误或已过期"
                return@launch
            }
            val isPhone = PHONE_RE.matches(target)
            val result = if (isPhone) {
                authRepo.register(username.trim(), password, target.trim())
            } else {
                authRepo.registerWithEmail(target.trim(), username.trim(), password)
            }
            when (result) {
                is AuthRepository.RegisterResult.Success -> {
                    val loginResult = authRepo.login(target.trim(), password)
                    isLoading = false
                    when (loginResult) {
                        is AuthRepository.LoginResult.Success -> onRegisterSuccess()
                        is AuthRepository.LoginResult.Error -> errorMessage = loginResult.message
                    }
                }
                is AuthRepository.RegisterResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                }
            }
        }
    }

    LightSystemBars()

    Box(modifier = Modifier.fillMaxSize()) {
        // Figma 原图背景
        Image(
            painter = painterResource(R.drawable.bg_hub),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 52.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "注册",
                color = BrandOnSurface,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(38.dp))

            AuthField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                placeholder = "新建用户名（2-20 字符）",
                contentDescription = "用户名",
                icon = AuthFieldIcon.PERSON,
            )

            Spacer(modifier = Modifier.height(14.dp))

            AuthField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                placeholder = "新建密码（至少 6 位）",
                contentDescription = "密码",
                icon = AuthFieldIcon.LOCK,
                password = true,
                passwordVisible = showPassword,
                onTogglePasswordVisibility = { showPassword = !showPassword },
            )

            Spacer(modifier = Modifier.height(14.dp))

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
                imeAction = ImeAction.Done,
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

            errorMessage?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(it, color = ERROR_RED, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(26.dp))

            BrandPrimaryButton(
                label = if (isLoading) "注册中…" else "注册",
                enabled = formValid && !isLoading,
                onClick = { doRegister() }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                "已有账号？返回登录",
                fontSize = 13.sp,
                color = BrandPurple,
                modifier = Modifier
                    .clickable(onClick = onNavigateToLogin)
                    .padding(8.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            AgreementText(
                prefix = "注册同意",
                checked = agreed,
                onCheckedChange = { agreed = it }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
