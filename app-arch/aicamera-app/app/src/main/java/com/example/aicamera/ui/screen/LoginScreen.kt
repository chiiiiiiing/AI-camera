package com.example.aicamera.ui.screen

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

/**
 * 登录页面。
 *
 * 支持用户名或手机号 + 密码登录。
 * 错误提示、登录成功后持久化会话。
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }

    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    // 清除错误
    fun clearError() {
        if (errorMessage != null) errorMessage = null
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
                text = "登录",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )

            Spacer(modifier = Modifier.height(36.dp))

            // ── 手机号/邮箱/用户名输入框 ──
            UnderlineTextField(
                value = account,
                onValueChange = {
                    account = it
                    clearError()
                },
                placeholder = "输入手机号 / 用户名",
                leadingIcon = "👤",
                keyboardType = KeyboardType.Email
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── 密码输入框 ──
            UnderlineTextField(
                value = password,
                onValueChange = {
                    password = it
                    clearError()
                },
                placeholder = "输入密码",
                leadingIcon = "🔒",
                keyboardType = KeyboardType.Password,
                isPassword = !showPassword
            )

            // 错误提示
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage!!,
                    color = Color(0xFFE53935),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── 辅助文字行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "忘记密码",
                    fontSize = 13.sp,
                    color = TextGray,
                    modifier = Modifier.clickable { /* TODO: 跳转密码找回 */ }
                )
                Text(
                    text = "注册账号",
                    fontSize = 13.sp,
                    color = DeepPurple,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onNavigateToRegister() }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── 登录按钮 ──
            Button(
                onClick = {
                    if (account.isBlank() || password.isBlank()) {
                        errorMessage = "请输入账号和密码"
                        return@Button
                    }

                    isLoading = true
                    errorMessage = null

                    val result = authRepo.login(account, password)
                    isLoading = false

                    when (result) {
                        is AuthRepository.LoginResult.Success -> {
                            onLoginSuccess()
                        }
                        is AuthRepository.LoginResult.Error -> {
                            errorMessage = result.message
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepPurple,
                    contentColor = Color.White,
                    disabledContainerColor = DeepPurple.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.6f)
                ),
                enabled = account.isNotBlank() && password.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "登录",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // ── 第三方登录分割 ──
            Text(
                text = "第三方账号登录",
                fontSize = 13.sp,
                color = TextGray
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── 微信 + QQ 图标 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF07C160), shape = RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("微信", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF12B7F5), shape = RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("QQ", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── 底部协议文字 ──
            Text(
                text = "登录同意《用户服务协议》",
                fontSize = 11.sp,
                color = TextLightGray,
                textAlign = TextAlign.Center
            )
        }
    }
}
