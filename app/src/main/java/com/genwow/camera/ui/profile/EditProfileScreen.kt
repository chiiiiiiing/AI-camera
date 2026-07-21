package com.genwow.camera.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.data.demo.DemoProfile
import com.genwow.camera.ui.auth.AuthField
import com.genwow.camera.ui.auth.AuthFieldIcon
import com.genwow.camera.ui.auth.BrandPrimaryButton
import com.genwow.camera.ui.auth.LightSystemBars
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandOnSurfaceMuted
import com.genwow.camera.ui.theme.GenWowTheme

private val EditProfileBackground = Brush.verticalGradient(
    listOf(
        Color(0xFFFFE7F3),
        Color(0xFFF6E6F8),
        Color(0xFFE3DAFF),
        Color(0xFFFFFFFF),
    ),
)

@Composable
fun EditProfileScreen(
    profile: DemoProfile,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LightSystemBars()
    var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var bio by remember(profile.bio) { mutableStateOf(profile.bio) }
    var city by remember { mutableStateOf("杭州") }
    var socialId by remember { mutableStateOf("genwow_creator") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EditProfileBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "返回",
                color = BrandOnSurfaceMuted,
                fontSize = 14.sp,
                modifier = Modifier
                    .semantics {
                        contentDescription = "返回个人主页"
                        role = Role.Button
                    }
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "编辑资料",
                color = BrandOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(40.dp))
        }

        Spacer(Modifier.height(22.dp))
        ProfileAvatar(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Text(
            text = "当前为本地演示资料页，可直接用于视觉确认与页面联调。",
            color = BrandOnSurfaceMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(28.dp))

        EditSection(title = "基础信息") {
            AuthField(
                value = name,
                onValueChange = { name = it },
                placeholder = "输入昵称",
                contentDescription = "昵称输入框",
                icon = AuthFieldIcon.PERSON,
            )
            AuthField(
                value = bio,
                onValueChange = { bio = it },
                placeholder = "输入个人简介",
                contentDescription = "简介输入框",
                icon = AuthFieldIcon.SHIELD,
                imeAction = ImeAction.Next,
            )
        }

        Spacer(Modifier.height(16.dp))

        EditSection(title = "展示信息") {
            AuthField(
                value = city,
                onValueChange = { city = it },
                placeholder = "输入所在城市",
                contentDescription = "城市输入框",
                icon = AuthFieldIcon.PHONE,
                keyboardType = KeyboardType.Text,
            )
            AuthField(
                value = socialId,
                onValueChange = { socialId = it },
                placeholder = "输入社区 ID",
                contentDescription = "社区 ID 输入框",
                icon = AuthFieldIcon.LOCK,
                imeAction = ImeAction.Done,
            )
        }

        Spacer(Modifier.height(26.dp))
        BrandPrimaryButton(
            label = "保存资料",
            enabled = name.isNotBlank() && bio.isNotBlank(),
            onClick = onBack,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "当前不会写入远端账号系统，仅用于本地原型联调。",
            color = BrandOnSurfaceMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun EditSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = {
            Text(
                text = title,
                color = BrandOnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        },
    )
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun EditProfilePreview() {
    GenWowTheme {
        EditProfileScreen(
            profile = DemoCommunityRepository().profile,
            onBack = {},
        )
    }
}
