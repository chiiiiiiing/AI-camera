package com.example.aicamera.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.data.AuthRepository
import com.example.aicamera.ui.components.UnderlineTextField
import com.example.aicamera.ui.theme.*

/**
 * 登录页 — 支持邮箱/手机号/用户名 + 密码登录。
 *
 * ★ 新增：
 * - 邮箱/手机号 Tab 切换
 * - 密码可见性切换（眼睛图标）
 * - 验证码快捷登录入口
 * - 微信 / QQ 第三方登录入口
 * - 忘记密码流程
 * - 输入校验 + 错误提示
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }

    // ── 登录方式：0=手机号, 1=邮箱/用户名 ──
    var loginMethod by remember { mutableIntStateOf(0) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // ★ 输入校验
    val isEmailMode = loginMethod == 1
    val isAccountValid = when {
        isEmailMode -> account.isNotBlank() && account.length >= 3
        else -> account.isNotBlank() && account.length >= 11
    }

    fun clearError() { if (errorMessage != null) errorMessage = null }

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
            // ── Logo 区 ──
            Text(
                text = "帧好 GenWow",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "AI 构图，拍出你的风格",
                fontSize = 14.sp,
                color = TextGray
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── 登录方式切换 Tab ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf("📱 手机号" to 0, "📧 邮箱" to 1).forEach { (label, method) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (method == loginMethod) DeepPurple else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { loginMethod = method; clearError() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (method == loginMethod) Color.White else TextDark.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 账号输入框 ──
            UnderlineTextField(
                value = account,
                onValueChange = { account = it; clearError() },
                placeholder = if (isEmailMode) "输入邮箱 / 用户名" else "输入手机号",
                leadingIcon = if (isEmailMode) "📧" else "📱",
                keyboardType = if (isEmailMode) KeyboardType.Email else KeyboardType.Phone,
                isError = errorMessage != null
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 密码输入框 ──
            UnderlineTextField(
                value = password,
                onValueChange = { password = it; clearError() },
                placeholder = "输入密码",
                leadingIcon = "🔒",
                keyboardType = KeyboardType.Password,
                isPassword = true,
                passwordVisible = showPassword,
                onTogglePasswordVisibility = { showPassword = !showPassword },
                imeAction = ImeAction.Done,
                onImeAction = {
                    // 键盘 Done → 触发登录
                    if (isAccountValid && password.isNotBlank()) {
                        // 触发登录逻辑（见按钮 onClick）
                    }
                },
                isError = errorMessage != null
            )

            // ── 错误提示 ──
            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = Color(0xFFE53935),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
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
                    color = DeepPurple,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        onNavigateToForgotPassword?.invoke()
                    }
                )
                Text(
                    text = "验证码登录",
                    fontSize = 13.sp,
                    color = TextGray,
                    modifier = Modifier.clickable { /* TODO: 切到验证码登录模式 */ }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 登录按钮 ──
            Button(
                onClick = {
                    if (!isAccountValid) {
                        errorMessage = if (isEmailMode) "请输入正确的邮箱或用户名" else "请输入正确的手机号"
                        return@Button
                    }
                    if (password.isBlank()) {
                        errorMessage = "请输入密码"
                        return@Button
                    }

                    isLoading = true
                    errorMessage = null

                    val result = authRepo.login(account, password)
                    isLoading = false

                    when (result) {
                        is AuthRepository.LoginResult.Success -> onLoginSuccess()
                        is AuthRepository.LoginResult.Error -> errorMessage = result.message
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepPurple,
                    contentColor = Color.White,
                    disabledContainerColor = DeepPurple.copy(alpha = 0.5f)
                ),
                enabled = isAccountValid && password.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp
                    )
                } else {
                    Text("登录", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 注册入口 ──
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("还没有账号？", fontSize = 13.sp, color = TextGray)
                TextButton(onClick = onNavigateToRegister) {
                    Text("立即注册", fontSize = 14.sp, color = DeepPurple, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── 第三方登录分割线 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = TextLightGray.copy(alpha = 0.3f))
                Text(
                    "  第三方账号登录  ", fontSize = 12.sp, color = TextGray
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = TextLightGray.copy(alpha = 0.3f))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 微信 + QQ 按钮 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 微信
                Button(
                    onClick = { /* TODO: 微信 OAuth */ },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF07C160),
                        contentColor = Color.White
                    )
                ) {
                    Text("微  信", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                // QQ
                Button(
                    onClick = { /* TODO: QQ OAuth */ },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF12B7F5),
                        contentColor = Color.White
                    )
                ) {
                    Text("Q   Q", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── 协议 ──
            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "登录即同意", fontSize = 11.sp, color = TextLightGray
                )
                Text(
                    "《用户服务协议》", fontSize = 11.sp, color = DeepPurple,
                    modifier = Modifier.clickable { /* TODO: 打开协议页 */ }
                )
                Text(
                    "和", fontSize = 11.sp, color = TextLightGray
                )
                Text(
                    "《隐私政策》", fontSize = 11.sp, color = DeepPurple,
                    modifier = Modifier.clickable { /* TODO: 打开隐私政策 */ }
                )
            }
        }
    }
}
