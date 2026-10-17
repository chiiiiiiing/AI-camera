package com.example.aicamera.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.R
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.auth.AgreementText
import com.example.aicamera.ui.auth.AuthField
import com.example.aicamera.ui.auth.AuthFieldIcon
import com.example.aicamera.ui.auth.BrandPrimaryButton
import com.example.aicamera.ui.auth.LightSystemBars
import com.example.aicamera.ui.auth.VerificationButton
import com.example.aicamera.ui.theme.BrandError
import com.example.aicamera.ui.theme.BrandOnSurface
import com.example.aicamera.ui.theme.BrandOnSurfaceMuted
import com.example.aicamera.ui.theme.BrandPurple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录页 — GenWow 设计规范（Canvas 线性图标，无 emoji）。
 *
 * 两种登录方式：
 * - 密码登录：手机号/邮箱 + 密码
 * - 验证码登录：手机号/邮箱 + 6 位验证码（未注册自动开户）
 *
 * 服务器在线走服务器（账号跨设备持久），离线自动回退本地账号。
 * 第三方（微信/QQ）因无开放平台资质不接入，仅保留视觉占位。
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    // 登录方式：false=密码登录, true=验证码登录
    var codeMode by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
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

    val accountValid = account.isNotBlank() &&
            (PHONE_RE.matches(account) || EMAIL_RE.matches(account) || (!codeMode && account.length >= 2))
    val formValid = agreed && if (codeMode) {
        accountValid && (PHONE_RE.matches(account) || EMAIL_RE.matches(account)) && verifyCode.length == 6
    } else {
        accountValid && password.length >= 6
    }

    fun doLogin() {
        if (!formValid || isLoading) return
        isLoading = true
        errorMessage = null
        scope.launch {
            val result = if (codeMode) {
                authRepo.loginWithCode(account.trim(), verifyCode)
            } else {
                authRepo.login(account.trim(), password)
            }
            isLoading = false
            when (result) {
                is AuthRepository.LoginResult.Success -> onLoginSuccess()
                is AuthRepository.LoginResult.Error -> errorMessage = result.message
            }
        }
    }

    fun requestCode() {
        if (countdown > 0 || sendingCode) return
        if (!(PHONE_RE.matches(account) || EMAIL_RE.matches(account))) {
            errorMessage = "请先输入正确的手机号或邮箱"
            return
        }
        errorMessage = null
        sendingCode = true
        scope.launch {
            when (val r = authRepo.requestCode(account.trim(), "login")) {
                is AuthRepository.CodeResult.Sent -> {
                    countdown = 60
                    devCodeHint = r.devCode
                }
                is AuthRepository.CodeResult.Error -> errorMessage = r.message
            }
            sendingCode = false
        }
    }

    LightSystemBars()

    Box(modifier = Modifier.fillMaxSize()) {
        // Figma 原图背景
        Image(
            painter = painterResource(R.drawable.bg_login),
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
                text = "登录",
                color = BrandOnSurface,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(38.dp))

            AuthField(
                value = account,
                onValueChange = { account = it; errorMessage = null },
                placeholder = "输入手机号/邮箱",
                contentDescription = "账号",
                icon = AuthFieldIcon.PERSON,
                keyboardType = KeyboardType.Email,
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (codeMode) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = "开发模式验证码：$code（点按填入）",
                            color = BrandPurple,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { verifyCode = code }
                        )
                    }
                }
            } else {
                AuthField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    placeholder = "输入密码",
                    contentDescription = "密码",
                    icon = AuthFieldIcon.LOCK,
                    password = true,
                    passwordVisible = showPassword,
                    onTogglePasswordVisibility = { showPassword = !showPassword },
                    imeAction = ImeAction.Done,
                )
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(it, color = ERROR_RED, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "忘记密码",
                    fontSize = 13.sp,
                    color = BrandOnSurfaceMuted,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onNavigateToForgotPassword?.invoke() }.padding(vertical = 4.dp)
                )
                Text(
                    text = "注册账号",
                    fontSize = 13.sp,
                    color = BrandOnSurfaceMuted,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onNavigateToRegister() }.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            BrandPrimaryButton(
                label = if (isLoading) "登录中…" else "登录",
                enabled = formValid && !isLoading,
                onClick = { doLogin() }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (codeMode) "密码登录" else "验证码登录",
                fontSize = 13.sp,
                color = BrandPurple,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    codeMode = !codeMode
                    errorMessage = null
                }
            )

            Spacer(modifier = Modifier.height(34.dp))

            Text(
                text = "第三方账号登录",
                color = BrandOnSurfaceMuted,
                fontSize = 12.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                WeChatIcon(
                    modifier = Modifier.size(36.dp),
                    tint = BrandPurple,
                )
                QQIcon(
                    modifier = Modifier.size(36.dp),
                    tint = BrandPurple,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AgreementText(
                prefix = "登录同意",
                checked = agreed,
                onCheckedChange = { agreed = it }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── 第三方登录占位图标（无实际 SDK 接入） ────────────────────────

@Composable
private fun WeChatIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 两个对话气泡
        drawCircle(tint, 8.5.dp.toPx(), Offset(size.width * 0.35f, size.height * 0.42f), style = stroke)
        drawCircle(tint, 7.dp.toPx(), Offset(size.width * 0.68f, size.height * 0.58f), style = stroke)
        // 小尾巴
        val tail1 = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.48f)
            lineTo(size.width * 0.20f, size.height * 0.58f)
            lineTo(size.width * 0.34f, size.height * 0.54f)
        }
        val tail2 = Path().apply {
            moveTo(size.width * 0.74f, size.height * 0.64f)
            lineTo(size.width * 0.82f, size.height * 0.72f)
            lineTo(size.width * 0.70f, size.height * 0.70f)
        }
        drawPath(tail1, tint, style = stroke)
        drawPath(tail2, tint, style = stroke)
    }
}

@Composable
private fun QQIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 企鹅简笔：头部圆 + 身椭圆 + 两翼
        drawCircle(tint, 7.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.36f), style = stroke)
        drawRoundRect(
            tint,
            topLeft = Offset(size.width * 0.32f, size.height * 0.46f),
            size = Size(size.width * 0.36f, size.height * 0.42f),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = stroke,
        )
        // 翅膀
        drawArc(
            tint,
            startAngle = 140f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.48f),
            size = Size(size.width * 0.18f, size.height * 0.28f),
            style = stroke,
        )
        drawArc(
            tint,
            startAngle = 320f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(size.width * 0.70f, size.height * 0.48f),
            size = Size(size.width * 0.18f, size.height * 0.28f),
            style = stroke,
        )
    }
}

internal val PHONE_RE = Regex("^1\\d{10}$")
internal val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
internal val ERROR_RED = BrandError
