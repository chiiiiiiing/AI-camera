package com.example.aicamera.viewmodel

import android.content.Context
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicamera.camera.CameraCapture
import com.example.aicamera.camera.CameraConfig
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.camera.CameraConfig.FlashMode
import com.example.aicamera.camera.CameraControls
import com.example.aicamera.camera.CameraManager
import com.example.aicamera.filter.BitmapFilterApplier
import com.example.aicamera.filter.PresetFilter
import com.example.aicamera.filter.CustomFilterConfig
import com.example.aicamera.filter.CustomFilterStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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

    /** 当前选中的系统预设滤镜 */
    private val _selectedFilter = MutableStateFlow<PresetFilter>(PresetFilter.Original)
    val selectedFilter: StateFlow<PresetFilter> = _selectedFilter.asStateFlow()
    private val customFilterStore = CustomFilterStore(appContext)
    private val _selectedCustomFilter = MutableStateFlow(customFilterStore.current())
    val selectedCustomFilter: StateFlow<CustomFilterConfig?> = _selectedCustomFilter.asStateFlow()
    private val _customFilters = MutableStateFlow(customFilterStore.all())
    val customFilters: StateFlow<List<CustomFilterConfig>> = _customFilters.asStateFlow()
    private val _customFilterActive = MutableStateFlow(false)
    val customFilterActive: StateFlow<Boolean> = _customFilterActive.asStateFlow()

    /** Pro 模式：EV 补偿索引 */
    private val _evIndex = MutableStateFlow(0)
    val evIndex: StateFlow<Int> = _evIndex.asStateFlow()

    /** Pro 模式：ISO（null = 自动） */
    private val _isoValue = MutableStateFlow<Int?>(null)
    val isoValue: StateFlow<Int?> = _isoValue.asStateFlow()

    /** Pro 模式：白平衡模式（CaptureRequest.CONTROL_AWB_MODE_*，null = 自动） */
    private val _awbMode = MutableStateFlow<Int?>(null)
    val awbMode: StateFlow<Int?> = _awbMode.asStateFlow()

    /** Pro 模式：快门曝光时间（纳秒，null = 自动） */
    private val _exposureTimeNs = MutableStateFlow<Long?>(null)
    val exposureTimeNs: StateFlow<Long?> = _exposureTimeNs.asStateFlow()

    /** Pro 模式：手动对焦距离（屈光度，0 = 无穷远，null = 自动对焦） */
    private val _focusDistance = MutableStateFlow<Float?>(null)
    val focusDistance: StateFlow<Float?> = _focusDistance.asStateFlow()

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

    fun selectFilter(filter: PresetFilter) {
        _selectedFilter.value = filter
        _customFilterActive.value = false
    }

    fun selectCustomFilter(filter: CustomFilterConfig) {
        customFilterStore.save(filter)
        _selectedCustomFilter.value = filter
        _customFilters.value = customFilterStore.all()
        _customFilterActive.value = true
    }

    fun importCustomFilter(filter: CustomFilterConfig) {
        customFilterStore.save(filter)
        _selectedCustomFilter.value = filter
        _customFilters.value = customFilterStore.all()
    }

    fun refreshImportedFilter() {
        _selectedCustomFilter.value = customFilterStore.current()
        _customFilters.value = customFilterStore.all()
    }

    fun takePhoto(saveToGallery: Boolean = true) {
        val imageCapture = cameraManager.getImageCapture()
        if (imageCapture == null) {
            Log.w(CameraConfig.TAG, "[VM] ImageCapture 未就绪，无法拍照")
            _uiState.update { it.copy(isCapturing = false) }
            viewModelScope.launch {
                _captureResult.emit(CameraCapture.CaptureResult.Error("相机尚未准备好，请稍后重试"))
            }
            return
        }

        _uiState.update { it.copy(isCapturing = true) }
        val filter = _selectedFilter.value
        val customFilter = _selectedCustomFilter.value.takeIf { _customFilterActive.value }

        cameraCapture.takePhoto(
            imageCapture = imageCapture,
            flashMode = uiState.value.flashMode,
            saveToGallery = saveToGallery,
            callback = object : CameraCapture.OnCaptureCallback {
                override fun onResult(result: CameraCapture.CaptureResult) {
                    val successUri = (result as? CameraCapture.CaptureResult.Success)?.uri
                    viewModelScope.launch {
                        val shouldFilter = customFilter != null || filter != PresetFilter.Original
                        val finalResult = if (successUri != null && shouldFilter) {
                            val filteredUri = createFilteredCopy(successUri, filter, customFilter, saveToGallery)
                            if (filteredUri != null) {
                                CameraCapture.CaptureResult.Success(filteredUri)
                            } else {
                                CameraCapture.CaptureResult.Success(
                                    uri = successUri,
                                    processingWarning = "滤镜处理失败，已安全保留原图"
                                )
                            }
                        } else {
                            result
                        }
                        val finalUri = (finalResult as? CameraCapture.CaptureResult.Success)?.uri
                        _uiState.update {
                            it.copy(
                                isCapturing = false,
                                latestPhotoUri = finalUri ?: successUri ?: it.latestPhotoUri
                            )
                        }
                        _captureResult.emit(finalResult)
                    }
                }
            }
        )
    }

    /** 原片保持不变，滤镜始终写入独立副本；大图限制在约 12MP，避免双 Bitmap OOM。 */
    private suspend fun createFilteredCopy(
        sourceUri: Uri,
        filter: PresetFilter,
        customFilter: CustomFilterConfig?,
        saveToGallery: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片尺寸无效" }
            var sampleSize = 1
            while ((bounds.outWidth / sampleSize) * (bounds.outHeight / sampleSize) > 12_000_000) {
                sampleSize *= 2
            }
            val bitmap = resolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                })
            } ?: error("无法读取原片")
            val filtered = if (customFilter != null) {
                BitmapFilterApplier.apply(bitmap, customFilter)
            } else {
                BitmapFilterApplier.apply(bitmap, filter)
            }
            val destination = createDerivedPhotoUri(saveToGallery) ?: error("无法创建滤镜副本")
            val written = resolver.openOutputStream(destination, "w")?.use { out ->
                filtered.compress(android.graphics.Bitmap.CompressFormat.JPEG, 94, out)
            } ?: false
            if (filtered !== bitmap) filtered.recycle()
            bitmap.recycle()
            if (!written) {
                resolver.delete(destination, null, null)
                error("滤镜副本写入失败")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                destination.scheme == "content"
            ) {
                resolver.update(destination, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            destination
        }.onFailure { e ->
            Log.w(CameraConfig.TAG, "[VM] 创建滤镜副本失败: " + e.message)
        }.getOrNull()
    }

    private fun createDerivedPhotoUri(saveToGallery: Boolean): Uri? {
        val name = "AiCamera_Filtered_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        if (saveToGallery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AiCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            return appContext.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            )
        }
        val directory = if (saveToGallery) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AiCamera")
        } else {
            appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.filesDir
        }
        if (!directory.exists() && !directory.mkdirs()) return null
        return Uri.fromFile(File(directory, name))
    }

    /** 用户点击对焦 → 发射 focusCircle 事件 */
    fun onFocusTapped(cx: Float, cy: Float) {
        _focusCircle.tryEmit(FocusCircleData(cx, cy))
    }

    enum class CaptureReadiness { FOCUSED, NOT_FOCUSED, TIMED_OUT, UNAVAILABLE }

    /**
     * 自动拍照前重新进行一次中心 AF + AE 测光。
     * 某些手机不返回成功状态，因此 1.4 秒超时后会降级继续拍摄，绝不无限卡住。
     */
    suspend fun prepareForCapture(timeoutMs: Long = 1400L): CaptureReadiness {
        val camera = cameraManager.camera ?: return CaptureReadiness.UNAVAILABLE
        val preview = cameraManager.getPreviewView() ?: return CaptureReadiness.UNAVAILABLE
        if (preview.width <= 0 || preview.height <= 0) return CaptureReadiness.UNAVAILABLE
        return withTimeoutOrNull(timeoutMs) {
            suspendCoroutine { continuation ->
                val point = preview.meteringPointFactory.createPoint(
                    preview.width / 2f,
                    preview.height / 2f
                )
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                val future = camera.cameraControl.startFocusAndMetering(action)
                future.addListener({
                    val result = runCatching { future.get() }.getOrNull()
                    continuation.resume(
                        if (result?.isFocusSuccessful == true) CaptureReadiness.FOCUSED
                        else CaptureReadiness.NOT_FOCUSED
                    )
                }, ContextCompat.getMainExecutor(appContext))
            }
        } ?: CaptureReadiness.TIMED_OUT
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

    fun updateTargetRotation(rotation: Int) {
        cameraManager.updateTargetRotation(rotation)
    }

    // ── 自动变焦（分步引导第①步） ─────────────────────────────

    private var autoZoomJob: Job? = null

    /**
     * 平滑变焦到目标倍率（分步引导的自动变焦）。
     * 用户在变焦期间捏合/拖滑杆 → [cancelAutoZoom] 立即让权。
     */
    fun rampZoomTo(target: Float, durationMs: Long = 700L) {
        val camera = cameraManager.camera ?: return
        autoZoomJob?.cancel()
        val zoomState = camera.cameraInfo.zoomState.value
        val maxZoom = zoomState?.maxZoomRatio ?: CameraConfig.MAX_ZOOM_RATIO
        val minZoom = zoomState?.minZoomRatio ?: CameraConfig.MIN_ZOOM_RATIO
        val clamped = target.coerceIn(minZoom, maxZoom)
        val start = _uiState.value.zoomRatio
        if (kotlin.math.abs(clamped - start) < 0.03f) return

        autoZoomJob = viewModelScope.launch {
            val steps = 16
            repeat(steps) { i ->
                kotlinx.coroutines.delay(durationMs / steps)
                // ease-out：前快后慢，接近目标时更稳
                val t = (i + 1).toFloat() / steps
                val eased = 1f - (1f - t) * (1f - t)
                controls.setZoomRatio(start + (clamped - start) * eased, camera)
            }
            controls.setZoomRatio(clamped, camera)
        }
    }

    /** 用户手动介入时取消自动变焦斜坡 */
    fun cancelAutoZoom() {
        autoZoomJob?.cancel()
    }

    /** 当前硬件变焦范围（UI 推给分步引导用） */
    fun zoomRange(): Pair<Float, Float> {
        val state = cameraManager.camera?.cameraInfo?.zoomState?.value
        return (state?.minZoomRatio ?: CameraConfig.MIN_ZOOM_RATIO) to
                (state?.maxZoomRatio ?: CameraConfig.MAX_ZOOM_RATIO)
    }

    /** ★ 获取 CameraX 实际变焦倍率（非乐观本地值），用于确认 Zoom 是否已完成 */
    fun actualZoomRatio(): Float {
        val camera = cameraManager.camera ?: return _uiState.value.zoomRatio
        return camera.cameraInfo.zoomState.value?.zoomRatio
            ?: _uiState.value.zoomRatio
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

    /** 设置快门曝光时间（纳秒，null 恢复自动），需要重绑 UseCase 生效 */
    fun setProExposureTime(ns: Long?) {
        _exposureTimeNs.value = ns
        applyProOptionsAndRebind()
    }

    /** 设置手动对焦距离（屈光度，null 恢复自动对焦），需要重绑 UseCase 生效 */
    fun setProFocusDistance(diopters: Float?) {
        _focusDistance.value = diopters
        applyProOptionsAndRebind()
    }

    private fun applyProOptionsAndRebind() {
        cameraManager.proOptions = CameraManager.ProOptions(
            iso = _isoValue.value,
            awbMode = _awbMode.value,
            exposureTimeNs = _exposureTimeNs.value,
            focusDistance = _focusDistance.value
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
