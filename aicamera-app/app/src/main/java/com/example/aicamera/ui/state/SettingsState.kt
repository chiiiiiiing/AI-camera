package com.example.aicamera.ui.state

/**
 * 全局设置状态 — 替代零散的 Boolean/Float 参数传递。
 *
 * 所有设置项的单一数据源，从 AppNavGraph 通过 Compose 参数传递到各子页面。
 * 后续可轻松迁移到 DataStore 持久化。
 */
data class SettingsState(
    val showGrid: Boolean = true,
    val showSubjectBox: Boolean = true,
    val showTargetZone: Boolean = true,
    val showHorizon: Boolean = true,
    val overlayAlpha: Float = 1.0f,
    val soundEnabled: Boolean = true,
    val saveOriginal: Boolean = true,
    val proModeEnabled: Boolean = false,
    /** 自动变焦开关（关闭时只提供构图引导，不改变用户焦段） */
    val aiAutoZoomEnabled: Boolean = true
)
