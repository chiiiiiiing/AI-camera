package com.example.aicamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.aicamera.ui.navigation.AppNavGraph
import com.example.aicamera.viewmodel.CameraViewModel
import com.example.aicamera.viewmodel.PipelineViewModel
import com.example.aicamera.viewmodel.RenderViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主 Activity — App 导航入口
 *
 * ## 架构
 * ```
 * MainActivity
 *   └── AppNavGraph (Navigation Compose)
 *         ├── SplashScreen    (2s → 自动跳转登录)
 *         ├── LoginScreen     (登录 → 主页)
 *         ├── RegisterScreen  (注册 → 主页)
 *         └── MainScreen      (底部三 Tab)
 *               ├── CameraScreen    (相机 + 辅助线)
 *               ├── CommunityScreen (社区动态)
 *               └── ProfileScreen   (个人主页)
 * ```
 *
 * ViewModel 在 Activity 层创建，通过 NavGraph 注入到各子页面。
 * 相机仅在用户进入 MainScreen 后初始化（PreviewView 首次组合时）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ── ViewModel（Activity 级 scope，跨 Tab 保持状态） ──

    private val cameraViewModel: CameraViewModel by viewModels()
    private val pipelineViewModel: PipelineViewModel by viewModels()
    private val renderViewModel: RenderViewModel by viewModels()

    // ── 权限 ──

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "相机权限是拍出好照片的基础", Toast.LENGTH_LONG).show()
        }
    }

    // ── Lifecycle ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 预检相机权限（实际请求在 CameraScreen 中按需触发）
        if (!hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val navController = rememberNavController()

            AppNavGraph(
                navController = navController,
                cameraViewModel = cameraViewModel,
                pipelineViewModel = pipelineViewModel,
                renderViewModel = renderViewModel
            )
        }
    }

    override fun onResume() {
        super.onResume()
        cameraViewModel.onResume()
        pipelineViewModel.resume()
    }

    override fun onPause() {
        super.onPause()
        cameraViewModel.onPause()
        pipelineViewModel.pause()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
