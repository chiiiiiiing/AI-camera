package com.genwow.camera.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.R
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.BrandPurple
import com.genwow.camera.ui.theme.BrandPink
import com.genwow.camera.ui.theme.BrandChineseTextStyle
import com.genwow.camera.ui.theme.BrandEnglishTextStyle
import com.genwow.camera.ui.theme.GenWowMotion
import com.genwow.camera.ui.theme.SystemBarsAppearance

enum class AuthFieldIcon { PERSON, LOCK, PHONE, SHIELD }

@Composable
fun LightSystemBars() {
    SystemBarsAppearance(light = true)
}

@Composable
fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    contentDescription: String,
    icon: AuthFieldIcon,
    modifier: Modifier = Modifier,
    error: String? = null,
    password: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val lineColor by animateColorAsState(
        targetValue = when {
            error != null -> Color(0xFFC76464)
            focused -> BrandPurple
            else -> BrandOnSurface.copy(alpha = 0.82f)
        },
        animationSpec = tween(GenWowMotion.FocusMillis),
        label = "auth field line",
    )
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthFieldIconCanvas(icon, Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { this.contentDescription = contentDescription },
                textStyle = TextStyle(
                    color = BrandOnSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                ),
                visualTransformation = if (password && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(placeholder, color = BrandOnSurfaceMuted, fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                },
            )
            if (password && onTogglePasswordVisibility != null) {
                PasswordVisibilityButton(
                    visible = passwordVisible,
                    modifier = Modifier
                        .semantics { this.contentDescription = "切换密码可见性" }
                        .clickable(onClick = onTogglePasswordVisibility)
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                )
            } else {
                trailingContent?.invoke()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(lineColor))
        AnimatedVisibility(error != null) {
            Text(
                text = error.orEmpty(),
                color = Color(0xFFC76464),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun AuthBrandHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_genwow_logo),
            contentDescription = "帧好 GenWow 标志",
            modifier = Modifier.size(78.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text("帧好", style = BrandChineseTextStyle)
        Spacer(Modifier.height(6.dp))
        Text("GenWow", style = BrandEnglishTextStyle)
        Spacer(Modifier.height(28.dp))
        Text(title, color = BrandOnSurface, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(subtitle, color = BrandOnSurfaceMuted, fontSize = 14.sp)
        }
    }
}

@Composable
fun BrandPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = tween(GenWowMotion.PressMillis),
        label = "auth button press",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .scale(scale)
            .background(
                BrandPurple.copy(alpha = if (enabled) 1f else 0.68f),
                RoundedCornerShape(8.dp),
            )
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PasswordVisibilityButton(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (visible) BrandPurple else BrandPink.copy(alpha = 0.92f)
    Canvas(modifier.size(20.dp)) {
        val stroke = Stroke(
            width = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val eyePath = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.5f)
            cubicTo(
                size.width * 0.24f,
                size.height * 0.20f,
                size.width * 0.76f,
                size.height * 0.20f,
                size.width * 0.92f,
                size.height * 0.5f,
            )
            cubicTo(
                size.width * 0.76f,
                size.height * 0.80f,
                size.width * 0.24f,
                size.height * 0.80f,
                size.width * 0.08f,
                size.height * 0.5f,
            )
            close()
        }
        drawPath(eyePath, color = tint, style = stroke)
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.14f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        if (!visible) {
            drawLine(
                color = tint,
                start = Offset(size.width * 0.20f, size.height * 0.82f),
                end = Offset(size.width * 0.82f, size.height * 0.18f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun VerificationButton(
    secondsRemaining: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = if (secondsRemaining > 0) "${secondsRemaining}s" else "获取验证码"
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BrandPurple, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AgreementText(prefix: String) {
    Text(
        text = "$prefix《用户服务协议》",
        color = BrandOnSurfaceMuted,
        fontSize = 11.sp,
    )
}

@Composable
fun ThirdPartyLoginRow() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.42f)))
            Text(
                "第三方账号登录",
                color = BrandOnSurfaceMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.42f)))
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(46.dp)) {
            ThirdPartyIconButton(ThirdPartyProvider.WECHAT)
            ThirdPartyIconButton(ThirdPartyProvider.QQ)
        }
    }
}

private enum class ThirdPartyProvider(val description: String) {
    WECHAT("微信登录（未接入）"),
    QQ("QQ 登录（未接入）"),
}

@Composable
private fun ThirdPartyIconButton(provider: ThirdPartyProvider) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = provider.description },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                id = when (provider) {
                    ThirdPartyProvider.WECHAT -> R.drawable.third_party_wechat_1
                    ThirdPartyProvider.QQ -> R.drawable.third_party_qq_1
                },
            ),
            contentDescription = null,
            modifier = Modifier.size(width = 32.dp, height = 31.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun AuthFieldIconCanvas(icon: AuthFieldIcon, modifier: Modifier) {
    Canvas(modifier) {
        val color = Color.Black.copy(alpha = 0.9f)
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            AuthFieldIcon.PERSON -> {
                drawCircle(color, radius = size.minDimension * 0.13f, center = Offset(size.width / 2, size.height * 0.3f), style = stroke)
                drawArc(color, 200f, 140f, false, topLeft = Offset(size.width * 0.23f, size.height * 0.47f), size = androidx.compose.ui.geometry.Size(size.width * 0.54f, size.height * 0.43f), style = stroke)
                drawLine(color, Offset(size.width * 0.25f, size.height * 0.82f), Offset(size.width * 0.75f, size.height * 0.82f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            AuthFieldIcon.LOCK -> {
                drawRoundRect(color, topLeft = Offset(size.width * 0.22f, size.height * 0.42f), size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
                drawArc(color, 180f, 180f, false, topLeft = Offset(size.width * 0.32f, size.height * 0.12f), size = androidx.compose.ui.geometry.Size(size.width * 0.36f, size.height * 0.5f), style = stroke)
            }
            AuthFieldIcon.PHONE -> {
                val path = Path().apply {
                    moveTo(size.width * 0.32f, size.height * 0.14f)
                    cubicTo(size.width * 0.16f, size.height * 0.3f, size.width * 0.36f, size.height * 0.72f, size.width * 0.7f, size.height * 0.86f)
                }
                drawPath(path, color, style = stroke)
                drawLine(color, Offset(size.width * 0.3f, size.height * 0.16f), Offset(size.width * 0.42f, size.height * 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.62f, size.height * 0.69f), Offset(size.width * 0.75f, size.height * 0.82f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            AuthFieldIcon.SHIELD -> {
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.08f)
                    lineTo(size.width * 0.82f, size.height * 0.22f)
                    lineTo(size.width * 0.78f, size.height * 0.65f)
                    lineTo(size.width * 0.5f, size.height * 0.9f)
                    lineTo(size.width * 0.22f, size.height * 0.65f)
                    lineTo(size.width * 0.18f, size.height * 0.22f)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, Offset(size.width * 0.36f, size.height * 0.48f), Offset(size.width * 0.47f, size.height * 0.6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.47f, size.height * 0.6f), Offset(size.width * 0.68f, size.height * 0.38f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
