package com.example.aicamera.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.components.UnderlineTextField
import com.example.aicamera.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 注册页面 — 三步注册流程，支持邮箱和手机号。
 *
 * Step 0：选择注册方式（邮箱 / 手机号）
 * Step 1：输入邮箱/手机号 → 获取验证码（60s 倒计时，模拟验证码 123456）
 * Step 2：输入验证码 → 点击下一步验证
 * Step 3：设置用户名 + 密码 + 确认密码 → 完成注册
 */
@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    // ★ 注册方式：0=手机号, 1=邮箱
    var regMethod by remember { mutableIntStateOf(0) }
    val isEmailMode = regMethod == 1
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }

    // ── Step 状态 ──
    var currentStep by remember { mutableStateOf(1) }

    // Step 1
    var phone by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }
    var codeSent by remember { mutableStateOf(false) }

    // Step 2
    var verifyCode by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf<String?>(null) }

    // Step 3
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var registerError by remember { mutableStateOf<String?>(null) }

    // 倒计时
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000L)
            countdown--
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(GradientPurple, GradientPink),
                    radius = 1200f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 标题 ──
            Text(
                text = "注册",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Spacer(modifier = Modifier.height(12.dp))
            // ★ 注册方式切换
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf("📱 手机号" to 0, "📧 邮箱" to 1).forEach { (label, method) ->
                    Box(
                        modifier = Modifier.weight(1f).background(
                            if (method == regMethod) DeepPurple else Color.Transparent, RoundedCornerShape(10.dp)
                        ).clickable { regMethod = method }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (method == regMethod) Color.White else TextDark.copy(alpha = 0.6f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (currentStep) {
                    1 -> "Step 1/3 — 手机号验证"
                    2 -> "Step 2/3 — 输入验证码"
                    else -> "Step 3/3 — 设置账号信息"
                },
                fontSize = 14.sp,
                color = TextGray
            )

            Spacer(modifier = Modifier.height(36.dp))

            // ══════════════════════════════════════════════════════
            // Step 1：手机号
            // ══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = currentStep == 1,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 4 }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 4 })
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UnderlineTextField(
                        value = phone,
                        onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                        placeholder = "输入手机号",
                        leadingIcon = "📱",
                        keyboardType = KeyboardType.Phone
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 获取验证码按钮
                    Button(
                        onClick = {
                            codeSent = true
                            countdown = 60
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepPurple,
                            contentColor = Color.White
                        ),
                        enabled = phone.length >= 11 && countdown == 0
                    ) {
                        Text(
                            text = if (!codeSent) "获取验证码"
                            else if (countdown > 0) "重新发送 (${countdown}s)"
                            else "重新发送",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 模拟提示
                    if (codeSent && countdown > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "（模拟验证码：123456）",
                            fontSize = 12.sp,
                            color = DeepPurple
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { currentStep = 2 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepPurple,
                            contentColor = Color.White
                        ),
                        enabled = codeSent
                    ) {
                        Text("下一步", fontSize = 16.sp)
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            // Step 2：验证码
            // ══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = currentStep == 2,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 4 }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 4 })
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UnderlineTextField(
                        value = verifyCode,
                        onValueChange = {
                            verifyCode = it.filter { c -> c.isDigit() }.take(6)
                            codeError = null
                        },
                        placeholder = "输入 6 位验证码",
                        leadingIcon = "✅",
                        keyboardType = KeyboardType.Number
                    )

                    if (codeError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(codeError!!, color = Color(0xFFE53935), fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { currentStep = 1 },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text("上一步", color = DeepPurple)
                        }

                        Button(
                            onClick = {
                                if (authRepo.verifyCode(verifyCode)) {
                                    currentStep = 3
                                    codeError = null
                                } else {
                                    codeError = "验证码错误，请重试"
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepPurple,
                                contentColor = Color.White
                            ),
                            enabled = verifyCode.length >= 4
                        ) {
                            Text("下一步", fontSize = 16.sp)
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            // Step 3：设置账号信息
            // ══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = currentStep == 3,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 4 }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 4 })
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UnderlineTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "设置用户名",
                        leadingIcon = "👤"
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    UnderlineTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "设置密码（至少 6 位）",
                        leadingIcon = "🔒",
                        isPassword = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    UnderlineTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        placeholder = "确认密码",
                        leadingIcon = "🔒",
                        isPassword = true
                    )

                    if (registerError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(registerError!!, color = Color(0xFFE53935), fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { currentStep = 2 },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text("上一步", color = DeepPurple)
                        }

                        Button(
                            onClick = {
                                // 校验
                                when {
                                    password != confirmPassword -> {
                                        registerError = "两次输入的密码不一致"
                                    }
                                    password.length < 6 -> {
                                        registerError = "密码至少 6 位"
                                    }
                                    username.isBlank() || username.length < 2 -> {
                                        registerError = "用户名至少 2 个字符"
                                    }
                                    else -> {
                                        // ★ 根据注册方式选择对应方法
                                        val result = if (isEmailMode) {
                                            authRepo.registerWithEmail(phone, username, password)
                                        } else {
                                            authRepo.register(username, password, phone)
                                        }
                                        when (result) {
                                            is AuthRepository.RegisterResult.Success -> {
                                                // 注册成功 → 自动登录 → 进入主页
                                                authRepo.login(username, password)
                                                onRegisterSuccess()
                                            }
                                            is AuthRepository.RegisterResult.Error -> {
                                                registerError = result.message
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepPurple,
                                contentColor = Color.White
                            ),
                            enabled = username.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank()
                        ) {
                            Text("完成注册", fontSize = 16.sp)
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            // 底部（所有 Step 共用）
            // ══════════════════════════════════════════════════════
            Spacer(modifier = Modifier.height(36.dp))

            TextButton(onClick = onNavigateToLogin) {
                Text(
                    text = "已有账号？返回登录",
                    fontSize = 13.sp,
                    color = DeepPurple
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "注册同意《用户服务协议》",
                fontSize = 11.sp,
                color = TextLightGray,
                textAlign = TextAlign.Center
            )
        }
    }
}
