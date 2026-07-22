package com.example.aicamera.viewmodel

import android.app.Application
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraCapture
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.camera.CameraConfig.FlashMode
import com.example.aicamera.camera.CameraControls
import com.example.aicamera.camera.CameraFocus
import com.example.aicamera.camera.CameraManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    data class CameraUiState(
        val isInitialized: Boolean = false,
        val hasPermission: Boolean = false,
        val cameraFace: CameraFace = CameraFace.BACK,
        val flashMode: FlashMode = FlashMode.AUTO,
        val zoomRatio: Float = 1.0f,
        val isFocusLocked: Boolean = false,
        val isCapturing: Boolean = false,
        val latestPhotoUri: android.net.Uri? = null
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

    private val cameraManager = CameraManager(application)
    private val cameraCapture = CameraCapture(application)
    val controls = CameraControls(
        onCameraFaceChanged = { face ->
            _uiState.update { it.copy(cameraFace = face) }
        },
        onZoomChanged = { ratio ->
            _uiState.update { it.copy(zoomRatio = ratio) }
        }
    )
    private val focusController = CameraFocus(
        onFocusStateChanged = { focused ->
            _uiState.update { it.copy(isFocusLocked = focused) }
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

    fun takePhoto() {
        val imageCapture = cameraManager.getImageCapture()
        if (imageCapture == null) {
            Log.w(CameraConfig.TAG, "[VM] ImageCapture 未就绪，无法拍照")
            return
        }

        _uiState.update { it.copy(isCapturing = true) }

        cameraCapture.takePhoto(
            imageCapture = imageCapture,
            flashMode = uiState.value.flashMode,
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

    /** 加载最新照片的 URI */
    fun refreshLatestPhoto() {
        val uri = CameraCapture.getLatestPhotoUri(getApplication())
        if (uri != null) {
            _uiState.update { it.copy(latestPhotoUri = uri) }
        }
    }

    fun toggleCamera() {
        val newFace = cameraManager.toggleCamera()
        // ★ P1: 同步 UI 状态
        _uiState.update { it.copy(cameraFace = newFace) }
    }

    fun switchTo(face: CameraFace) {
        cameraManager.switchCamera(face)
        _uiState.update { it.copy(cameraFace = face) }
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

    fun enableTapToFocus(view: android.view.View) {
        val camera = cameraManager.camera ?: return
        // ★ CameraFocus 现在接受 PreviewView
        if (view is androidx.camera.view.PreviewView) {
            focusController.enableTapToFocus(view, camera)
        }
    }

    fun enablePinchToZoom(view: android.view.View) {
        val camera = cameraManager.camera ?: return
        controls.enablePinchToZoom(view, camera)
    }

    fun getCamera() = cameraManager.camera
    fun getPreviewView() = cameraManager.getPreviewView()
    fun currentCameraFace(): CameraFace = _uiState.value.cameraFace

    fun onPause() {
        cameraManager.unbind()
    }

    fun onResume() {
        cameraManager.rebind()
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
    }
}
