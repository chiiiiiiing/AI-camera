package com.example.aicamera.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aicamera.data.AuthRepository

/**
 * 编辑资料页。
 *
 * 功能：头像/封面更换、昵称、简介、网站
 */
@Composable
fun EditProfileScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository.getInstance(context) }

    var username by remember { mutableStateOf(authRepo.currentUsername ?: "") }
    var bio by remember { mutableStateOf(authRepo.currentBio ?: "") }
    var avatarUri by remember { mutableStateOf<Uri?>(authRepo.currentAvatarUri?.let { Uri.parse(it) }) }
    var isSaving by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) avatarUri = uri
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text("编辑资料", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                isSaving = true
                authRepo.updateBio(bio)
                avatarUri?.let { authRepo.saveAvatarUri(it.toString()) }
                isSaving = false
                onBack()
            }, enabled = !isSaving) {
                Text(if (isSaving) "保存中..." else "保存", color = Color(0xFF8B5FBF), fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // ── 头像 ──
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape).background(Color.DarkGray)
                    .clickable { avatarPicker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(avatarUri).crossfade(true).size(192).build(),
                        contentDescription = "头像", modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Filled.Person, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(Color(0xFF8B5FBF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("点击更换头像", color = Color(0xFF8B5FBF), fontSize = 13.sp)

            Spacer(modifier = Modifier.height(32.dp))

            // ── 昵称 ──
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("昵称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = darkTextFieldColors()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 简介 ──
            OutlinedTextField(
                value = bio, onValueChange = { bio = it },
                label = { Text("简介") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                colors = darkTextFieldColors()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 网站 ──
            OutlinedTextField(
                value = "", onValueChange = { },
                label = { Text("网站") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://", color = Color.Gray) },
                colors = darkTextFieldColors()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFF8B5FBF),
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    focusedLabelColor = Color(0xFF8B5FBF),
    unfocusedLabelColor = Color.Gray,
    cursorColor = Color(0xFF8B5FBF)
)
