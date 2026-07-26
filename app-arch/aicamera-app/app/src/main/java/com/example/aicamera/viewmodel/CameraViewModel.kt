package com.example.aicamera.viewmodel

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraCapture
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.camera.CameraConfig.FlashMode
import com.example.aicamera.camera.CameraControls
import com.example.aicamera.camera.CameraManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 相机链路 ViewModel。
 *
 * ★ CameraManager / CameraCapture 经 Hilt 注入（修复历史上绕过 DI 的 P0）。
 * 手势相关：相机每次绑定/重绑都会让 [CameraUiState.cameraBindVersion] 自增，
 * UI 层监听该值把手势分发器重新 attach 到新的 Camera 实例
 * （修复切换前后摄后捏合变焦/点击对焦失效的问题）。
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val cameraManager: CameraManager,
    private val cameraCapture: CameraCapture
) : ViewModel() {

    data class CameraUiState(
        val isInitialized: Boolean = false,
        val hasPermission: Boolean = false,
        val cameraFace: CameraFace = CameraFace.BACK,
        val flashMode: FlashMode = FlashMode.AUTO,
        val zoomRatio: Float = 1.0f,
        val isFocusLocked: Boolean = false,
        val isCapturing: Boolean = false,
        val latestPhotoUri: android.net.Uri? = null,
        /** 相机绑定代次：每次 bind/rebind 成功自增，UI 据此重挂手势 */
        val cameraBindVersion: Int = 0
    )

    /** 对焦圆圈动画数据 */
    data class FocusCircleData(
        val centerX: Float,
        val centerY: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _frameFlow = MutableSharedFlow<FrameData>(replay = 0, extraBufferCapacity = 2)
    val frameFlow: SharedFlow<FrameData> = _frameFlow.asSharedFlow()

    data class FrameData(
        val imageProxy: ImageProxy,
        val rotation: Int,
        val isFrontCamera: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _captureResult = MutableSharedFlow<CameraCapture.CaptureResult>()
    val captureResult: SharedFlow<CameraCapture.CaptureResult> = _captureResult.asSharedFlow()

    private val _focusCircle = MutableSharedFlow<FocusCircleData>(replay = 0, extraBufferCapacity = 2)
    val focusCircle: SharedFlow<FocusCircleData> = _focusCircle.asSharedFlow()

    /** Pro 模式：EV 补偿索引 */
    private val _evIndex = MutableStateFlow(0)
    val evIndex: StateFlow<Int> = _evIndex.asStateFlow()

    /** Pro 模式：ISO（null = 自动） */
    private val _isoValue = MutableStateFlow<Int?>(null)
    val isoValue: StateFlow<Int?> = _isoValue.asStateFlow()

    /** Pro 模式：白平衡模式（CaptureRequest.CONTROL_AWB_MODE_*，null = 自动） */
    private val _awbMode = MutableStateFlow<Int?>(null)
    val awbMode: StateFlow<Int?> = _awbMode.asStateFlow()

    val controls = CameraControls(
        onCameraFaceChanged = { face ->
            _uiState.update { it.copy(cameraFace = face) }
        },
        onZoomChanged = { ratio ->
            _uiState.update { it.copy(zoomRatio = ratio) }
        }
    )

    fun initialize(
        previewView: androidx.camera.view.PreviewView,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        onReady: (Boolean) -> Unit = {}
    ) {
        cameraManager.init(previewView, lifecycleOwner) { success ->
            _uiState.update { it.copy(isInitialized = success) }

            if (success) {
                bumpBindVersion()

                // ★ P0: 不包 viewModelScope.launch，直接 tryEmit + 失败时 close
                cameraManager.setOnFrameAvailable { imageProxy ->
                    val frameData = FrameData(
                        imageProxy = imageProxy,
                        rotation = imageProxy.imageInfo.rotationDegrees,
                        isFrontCamera = cameraManager.isFrontCamera()
                    )
                    val emitted = _frameFlow.tryEmit(frameData)
                    if (!emitted) {
                        // ★ Buffer 满或下游未连接，关闭帧防止 CameraX 阻塞
                        imageProxy.close()
                    }
                }
            }
            onReady(success)
        }
    }

    private fun bumpBindVersion() {
        _uiState.update { it.copy(cameraBindVersion = it.cameraBindVersion + 1) }
    }

    fun takePhoto(saveToGallery: Boolean = true) {
        val imageCapture = cameraManager.getImageCapture()
        if (imageCapture == null) {
            Log.w(CameraConfig.TAG, "[VM] ImageCapture 未就绪，无法拍照")
            return
        }

        _uiState.update { it.copy(isCapturing = true) }

        cameraCapture.takePhoto(
            imageCapture = imageCapture,
            flashMode = uiState.value.flashMode,
            saveToGallery = saveToGallery,
            callback = object : CameraCapture.OnCaptureCallback {
                override fun onResult(result: CameraCapture.CaptureResult) {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            latestPhotoUri = (result as? CameraCapture.CaptureResult.Success)?.uri
                                ?: it.latestPhotoUri
                        )
                    }
                    viewModelScope.launch {
                        _captureResult.emit(result)
                    }
                }
            }
        )
    }

    /** 用户点击对焦 → 发射 focusCircle 事件 */
    fun onFocusTapped(cx: Float, cy: Float) {
        _focusCircle.tryEmit(FocusCircleData(cx, cy))
    }

    /** 长按锁定对焦（由手势分发器回调） */
    fun setFocusLocked(locked: Boolean) {
        _uiState.update { it.copy(isFocusLocked = locked) }
    }

    /** 加载最新照片的 URI */
    fun refreshLatestPhoto() {
        val uri = CameraCapture.getLatestPhotoUri(appContext)
        if (uri != null) {
            _uiState.update { it.copy(latestPhotoUri = uri) }
        }
    }

    fun toggleCamera() {
        val newFace = cameraManager.toggleCamera()
        if (newFace != _uiState.value.cameraFace) {
            _uiState.update { it.copy(cameraFace = newFace) }
            // 切换成功 → Camera 实例已更换，通知 UI 重挂手势
            bumpBindVersion()
        }
    }

    fun switchTo(face: CameraFace) {
        if (cameraManager.switchCamera(face)) {
            _uiState.update { it.copy(cameraFace = face) }
            bumpBindVersion()
        }
    }

    fun cycleFlash() {
        val camera = cameraManager.camera ?: return
        val imageCapture = cameraManager.getImageCapture() ?: return
        val newMode = controls.cycleFlashMode(camera, imageCapture)
        _uiState.update { it.copy(flashMode = newMode) }
    }

    fun setFlash(mode: FlashMode) {
        val camera = cameraManager.camera ?: return
        val imageCapture = cameraManager.getImageCapture() ?: return
        controls.setFlashMode(mode, camera, imageCapture)
        _uiState.update { it.copy(flashMode = mode) }
    }

    fun setZoom(ratio: Float) {
        val camera = cameraManager.camera ?: return
        controls.setZoomRatio(ratio, camera)
    }

    // ── Pro 模式 ──────────────────────────────────────────────

    /** EV 补偿范围（相机未就绪返回 (0,0)） */
    fun evRange(): Pair<Int, Int> {
        val range = cameraManager.camera?.cameraInfo?.exposureState?.exposureCompensationRange
            ?: return 0 to 0
        return range.lower to range.upper
    }

    fun setExposureCompensation(index: Int) {
        val camera = cameraManager.camera ?: return
        val pro = com.example.aicamera.camera.CameraProControls(camera)
        if (pro.setExposureCompensation(index)) {
            _evIndex.value = index
        }
    }

    /** 设置 ISO（null 恢复自动），需要重绑 UseCase 生效 */
    fun setProIso(iso: Int?) {
        _isoValue.value = iso
        applyProOptionsAndRebind()
    }

    /** 设置白平衡（null 恢复自动），需要重绑 UseCase 生效 */
    fun setProAwb(awbMode: Int?) {
        _awbMode.value = awbMode
        applyProOptionsAndRebind()
    }

    private fun applyProOptionsAndRebind() {
        cameraManager.proOptions = CameraManager.ProOptions(
            iso = _isoValue.value,
            awbMode = _awbMode.value
        )
        if (cameraManager.rebind()) {
            bumpBindVersion()
        }
    }

    fun getCamera() = cameraManager.camera
    fun getPreviewView() = cameraManager.getPreviewView()
    fun currentCameraFace(): CameraFace = _uiState.value.cameraFace

    fun onPause() {
        cameraManager.unbind()
    }

    fun onResume() {
        // 已初始化过才需要重绑；重绑成功后 Camera 实例更换，通知 UI 重挂手势
        if (_uiState.value.isInitialized && cameraManager.rebind()) {
            bumpBindVersion()
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
    }
}
