package com.example.aicamera.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.theme.DeepPurple
import com.example.aicamera.ui.theme.TextDark
import com.example.aicamera.ui.theme.TextLightGray

/**
 * 下划线样式输入框 — 左侧 emoji 图标 + Material3 下划线输入框。
 * 被 LoginScreen 和 RegisterScreen 共用。
 */
@Composable
fun UnderlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
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
                    focusedIndicatorColor = DeepPurple,
                    unfocusedIndicatorColor = TextLightGray.copy(alpha = 0.4f),
                    focusedTextColor = TextDark,
                    unfocusedTextColor = TextDark,
                    cursorColor = DeepPurple
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (isPassword) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                }
            )
        }
    }
}
