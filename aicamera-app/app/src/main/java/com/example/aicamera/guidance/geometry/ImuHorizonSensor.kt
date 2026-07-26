package com.example.aicamera.guidance.geometry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2

/**
 * IMU 水平倾角传感器 — 由重力向量计算设备绕镜头轴的倾角（ROLL）。
 *
 * ## 原理
 * 重力向量 g 在设备坐标系（x 右、y 沿屏幕向上、z 出屏）中的分量随设备
 * 绕 z 轴（镜头光轴）旋转而变化：
 *   设备顺时针倾 θ 时，g = (-g·sinθ, -g·cosθ, 0)
 *   故 θ = atan2(-gx, -gy)（正值 = 顺时针倾斜）
 *
 * ## 与引导指令的关系（docs/10 约定）
 * θ > +2° → 提示 ROLL_COUNTERCLOCKWISE；θ < -2° → ROLL_CLOCKWISE。
 *
 * ## 适用范围
 * App 锁定竖屏（Manifest screenOrientation=portrait），故只处理竖屏姿态；
 * 手机接近平放（|gz| 主导）时角度不可信，输出仍连续但权重应降低（由调用方决定）。
 *
 * ## 生命周期
 * [start] 注册监听（优先 TYPE_GRAVITY，退化 TYPE_ACCELEROMETER 低通滤波）；
 * [stop] 注销。由 PipelineViewModel 的 resume/pause 驱动，后台不耗电。
 */
@Singleton
class ImuHorizonSensor @Inject constructor(
    @ApplicationContext context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 最新倾角（度，正值 = 顺时针）。Volatile 保证推理线程读到最新值。 */
    @Volatile
    private var latestAngleDeg: Float? = null

    /** 加速度计低通滤波状态（无重力传感器时用） */
    private var filteredGravity: FloatArray? = null

    @Volatile
    private var listening = false

    /** 传感器是否可用（无任何重力来源时 false，调用方应走无水平信号分支） */
    val isAvailable: Boolean
        get() = gravitySensor != null || accelSensor != null

    fun start() {
        if (listening) return
        val sensor = gravitySensor ?: accelSensor
        if (sensor == null) {
            Log.w(TAG, "设备无重力/加速度传感器，水平仪不可用")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        listening = true
        Log.i(TAG, "水平传感器已启动: ${sensor.name}")
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
    }

    /** 读取最新倾角（度），无信号返回 null */
    fun latestAngleDegrees(): Float? = latestAngleDeg

    override fun onSensorChanged(event: SensorEvent) {
        val g = when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> event.values
            Sensor.TYPE_ACCELEROMETER -> {
                // 低通滤波提取重力分量（alpha=0.8 保留旧值，标准做法）
                val alpha = 0.8f
                val prev = filteredGravity ?: event.values.clone()
                val next = FloatArray(3) { i -> alpha * prev[i] + (1 - alpha) * event.values[i] }
                filteredGravity = next
                next
            }
            else -> return
        }

        // θ = atan2(-gx, -gy)，竖屏持机时即设备绕镜头轴的倾角
        val angleRad = atan2(-g[0].toDouble(), -g[1].toDouble())
        latestAngleDeg = Math.toDegrees(angleRad).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理；重力估计精度变化不影响连续输出
    }

    companion object {
        private const val TAG = "ImuHorizonSensor"
    }
}
