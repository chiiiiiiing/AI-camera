package com.example.aicamera.guidance.geometry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMU 姿态传感器 — 由重力/磁力计计算设备绕镜头轴的倾角（ROLL）、
 * 绕设备横轴的俯仰角（PITCH）以及绕设备竖轴的偏航角（YAW）。
 *
 * ## 原理
 * - ROLL / PITCH：由重力向量直接计算，无需磁力计，可靠。
 * - YAW：需要磁力计配合重力向量通过 [SensorManager.getRotationMatrix] 计算，
 *   受磁场干扰会漂移；输出时带 null 退化。
 *
 * ## 坐标系（Android 设备）
 * x → 右, y → 上（沿屏幕）, z → 出屏
 * 重力向量 g = (gx, gy, gz)
 * 竖屏正立时 g ≈ (0, +9.8, 0)
 *
 * ## 生命周期
 * [start] 注册监听（优先 TYPE_GRAVITY，退化 TYPE_ACCELEROMETER 低通滤波；
 *         同时注册 TYPE_MAGNETIC_FIELD 用于 YAW）；
 * [stop] 注销。由 PipelineViewModel 的 resume/pause 驱动，后台不耗电。
 */
@Singleton
class ImuHorizonSensor @Inject constructor(
    @ApplicationContext context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** 最新完整观测（经圆周平滑）。Volatile 保证推理线程读到最新值。 */
    @Volatile
    private var latestObservation: HorizonObservation? = null
    @Volatile
    private var latestGravityAtMs: Long = 0L

    /** 圆周角平滑器 — sin/cos 向量 EMA，消除 ±180° 跳变 */
    private val rollSmoother = CircularAngleSmoother(alpha = 0.35f)
    private val pitchSmoother = CircularAngleSmoother(alpha = 0.3f)
    private val yawSmoother = CircularAngleSmoother(alpha = 0.2f)

    /** 最近一次重力与磁力计读数 */
    private var gravityValues: FloatArray? = null
    private var magneticValues: FloatArray? = null

    /** 加速度计低通滤波状态（无重力传感器时用） */
    private var filteredGravity: FloatArray? = null

    @Volatile
    private var listening = false
    private var observationId = 0L

    /** 传感器是否可用（无任何重力来源时 false，调用方应走无水平信号分支） */
    val isAvailable: Boolean
        get() = gravitySensor != null || accelSensor != null

    /** 磁力计是否可用（false 时 YAW 始终为 null） */
    val isMagneticAvailable: Boolean
        get() = magneticSensor != null

    fun start() {
        if (listening) return

        val gravity = gravitySensor ?: accelSensor
        if (gravity == null) {
            Log.w(TAG, "设备无重力/加速度传感器，水平仪不可用")
            return
        }

        rollSmoother.reset()
        pitchSmoother.reset()
        yawSmoother.reset()
        gravityValues = null
        magneticValues = null
        filteredGravity = null
        latestObservation = null
        latestGravityAtMs = 0L

        sensorManager.registerListener(this, gravity, SensorManager.SENSOR_DELAY_NORMAL)
        magneticSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        listening = true
        Log.i(TAG, "姿态传感器已启动: gravity=${gravity.name}, mag=${magneticSensor?.name ?: "无"}")
    }

    fun stop() {
        if (listening) sensorManager.unregisterListener(this)
        listening = false
        latestObservation = null
        latestGravityAtMs = 0L
    }

    /** 读取最新倾角（度），无信号返回 null */
    fun latestAngleDegrees(): Float? = latestObservation()?.rollDegrees

    /** 读取最新完整观测（含 roll/pitch/yaw / isUpright / confidence） */
    fun latestObservation(): HorizonObservation? {
        if (!listening || SystemClock.elapsedRealtime() - latestGravityAtMs > MAX_OBSERVATION_AGE_MS) return null
        val observation = latestObservation ?: return null
        // The app's camera runs on the phone's built-in display. Read rotation
        // when consuming the observation, so 180-degree changes need no new sensor sample.
        val rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        return HorizonMath.inDisplayCoordinates(observation, rotation)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravityValues = event.values.clone()
                updateObservation(event.timestamp / 1_000_000L)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (gravitySensor != null) return
                // 低通滤波提取重力分量（alpha=0.8 保留旧值）
                val alpha = 0.8f
                val prev = filteredGravity ?: event.values.clone()
                val next = FloatArray(3) { i -> alpha * prev[i] + (1 - alpha) * event.values[i] }
                filteredGravity = next
                gravityValues = next
                updateObservation(event.timestamp / 1_000_000L)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues = event.values.clone()
                updateYaw()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理；重力估计精度变化不影响连续输出
    }

    @Synchronized
    private fun updateObservation(sampledAtMs: Long) {
        val g = gravityValues ?: return
        latestGravityAtMs = sampledAtMs
        val raw = HorizonMath.compute(g[0], g[1], g[2])
        val smoothedRoll = rollSmoother.smooth(raw.rollDegrees)
        val smoothedPitch = pitchSmoother.smooth(raw.pitchDegrees ?: 0f)

        latestObservation = HorizonObservation(
            rollDegrees = smoothedRoll,
            pitchDegrees = smoothedPitch,
            yawDegrees = latestObservation?.yawDegrees,
            isUpright = raw.isUpright,
            confidence = raw.confidence,
            observationId = ++observationId,
            sampledAtMs = sampledAtMs,
            rawRollDegrees = raw.rollDegrees
        )

        // 重力更新后也需要重新计算 YAW（如果磁力计已就绪）
        updateYaw()
    }

    @Synchronized
    private fun updateYaw() {
        val g = gravityValues ?: return
        val m = magneticValues ?: return

        val rotationMatrix = FloatArray(9)
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, g, m)
        if (!success) return

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val yawDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val smoothedYaw = yawSmoother.smooth(yawDeg)

        latestObservation = latestObservation?.copy(yawDegrees = smoothedYaw)
    }

    companion object {
        private const val TAG = "ImuHorizonSensor"
        private const val MAX_OBSERVATION_AGE_MS = 1_500L
    }
}
