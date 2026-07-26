package com.example.aicamera.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.theme.DeepPurple
import com.example.aicamera.ui.theme.TextDark
import com.example.aicamera.ui.theme.TextLightGray

/**
 * 下划线样式输入框 — 左侧 emoji 图标 + Material3 下划线输入框 + 可选尾部图标。
 */
@Composable
fun UnderlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    /** ★ 密码可见性切换（仅 isPassword=true 时生效） */
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    /** ★ IME 动作 */
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    /** ★ 错误状态 */
    isError: Boolean = false
) {
    val visualTransformation = when {
        isPassword && !passwordVisible -> androidx.compose.ui.text.input.PasswordVisualTransformation()
        else -> VisualTransformation.None
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = leadingIcon,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 12.dp)
            )

            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(placeholder, color = TextLightGray, fontSize = 15.sp)
                },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = if (isError) Color(0xFFE53935) else DeepPurple,
                    unfocusedIndicatorColor = if (isError) Color(0xFFE53935).copy(alpha = 0.5f) else TextLightGray.copy(alpha = 0.4f),
                    focusedTextColor = TextDark,
                    unfocusedTextColor = TextDark,
                    cursorColor = DeepPurple
                ),
                singleLine = true,
                isError = isError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onNext = { onImeAction?.invoke() }
                ),
                visualTransformation = visualTransformation,
                trailingIcon = if (isPassword && onTogglePasswordVisibility != null) {
                    {
                        val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(icon, contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                tint = TextLightGray, modifier = Modifier.size(20.dp))
                        }
                    }
                } else null
            )
        }
    }
}
