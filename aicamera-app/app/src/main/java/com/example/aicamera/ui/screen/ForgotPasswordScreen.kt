package com.example.aicamera.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.components.UnderlineTextField
import com.example.aicamera.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 忘记密码页 — 两步流程。
 *
 * Step 1：输入邮箱/手机号 → 发送验证码（60s 倒计时）
 * Step 2：输入验证码 + 新密码 → 重置
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onResetSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }

    var step by remember { mutableIntStateOf(1) }
    var account by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var codeSent by remember { mutableStateOf(false) }
    var verifyCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000L); countdown-- }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            brush = Brush.radialGradient(colors = listOf(GradientPurple, GradientPink), radius = 1200f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("重置密码", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextDark)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (step == 1) "输入注册时使用的邮箱或手机号" else "输入验证码和新密码",
                fontSize = 14.sp, color = TextGray
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (step == 1) {
                // Step 1: 输入账号 + 发送验证码
                UnderlineTextField(
                    value = account, onValueChange = { account = it },
                    placeholder = "输入邮箱或手机号", leadingIcon = "📧",
                    keyboardType = KeyboardType.Email
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (account.isBlank()) { errorMessage = "请输入邮箱或手机号"; return@Button }
                        codeSent = true; countdown = 60
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DeepPurple),
                    enabled = account.isNotBlank() && countdown == 0
                ) {
                    Text(
                        if (!codeSent) "发送验证码"
                        else if (countdown > 0) "重新发送 (${countdown}s)"
                        else "重新发送",
                        fontSize = 16.sp
                    )
                }

                if (codeSent && countdown > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("（模拟验证码：123456）", fontSize = 12.sp, color = DeepPurple)
                }

                if (codeSent) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { step = 2 }, modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DeepPurple)
                    ) { Text("下一步", fontSize = 16.sp) }
                }
            } else {
                // Step 2: 验证码 + 新密码
                UnderlineTextField(
                    value = verifyCode, onValueChange = { verifyCode = it.filter { c -> c.isDigit() }.take(6) },
                    placeholder = "输入 6 位验证码", leadingIcon = "✅",
                    keyboardType = KeyboardType.Number
                )
                Spacer(modifier = Modifier.height(16.dp))
                UnderlineTextField(
                    value = newPassword, onValueChange = { newPassword = it },
                    placeholder = "设置新密码（至少 6 位）", leadingIcon = "🔒",
                    isPassword = true, passwordVisible = showPassword,
                    onTogglePasswordVisibility = { showPassword = !showPassword }
                )
                Spacer(modifier = Modifier.height(16.dp))
                UnderlineTextField(
                    value = confirmPassword, onValueChange = { confirmPassword = it },
                    placeholder = "确认新密码", leadingIcon = "🔒",
                    isPassword = true
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, color = Color(0xFFE53935), fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { step = 1 }, modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(25.dp)
                    ) { Text("上一步", color = DeepPurple) }

                    Button(
                        onClick = {
                            if (!authRepo.verifyCode(verifyCode)) { errorMessage = "验证码错误"; return@Button }
                            if (newPassword.length < 6) { errorMessage = "密码至少 6 位"; return@Button }
                            if (newPassword != confirmPassword) { errorMessage = "两次密码不一致"; return@Button }
                            isLoading = true
                            // MVP: 本地重新注册（模拟重置）
                            onResetSuccess()
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DeepPurple),
                        enabled = verifyCode.length >= 4 && newPassword.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        else Text("重置密码", fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = DeepPurple, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("返回登录", color = DeepPurple, fontSize = 14.sp)
            }
        }
    }
}
