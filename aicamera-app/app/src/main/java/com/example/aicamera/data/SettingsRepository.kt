package com.example.aicamera.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aicamera.ui.state.SettingsState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 全局设置 DataStore（进程重启后保留） */
private val Context.settingsDataStore by preferencesDataStore(name = "aicamera_settings")

/**
 * 设置仓库 — SettingsState 的持久化单一数据源。
 *
 * 修复历史问题：设置之前只存在 NavGraph 的 remember 内存态，进程一死全部丢失。
 * 现在通过 DataStore 持久化，AppNavGraph 订阅 [settings] Flow，
 * 所有页面的修改经 [update] 写回。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val SHOW_SUBJECT_BOX = booleanPreferencesKey("show_subject_box")
        val SHOW_TARGET_ZONE = booleanPreferencesKey("show_target_zone")
        val SHOW_HORIZON = booleanPreferencesKey("show_horizon")
        val OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SAVE_ORIGINAL = booleanPreferencesKey("save_original")
        val PRO_MODE = booleanPreferencesKey("pro_mode_enabled")
        val AUTO_ZOOM = booleanPreferencesKey("auto_zoom_enabled")
    }

    /** 设置流（DataStore 异常时回落到默认值） */
    val settings: Flow<SettingsState> = context.settingsDataStore.data.map { p ->
        val defaults = SettingsState()
        SettingsState(
            showGrid = p[Keys.SHOW_GRID] ?: defaults.showGrid,
            showSubjectBox = p[Keys.SHOW_SUBJECT_BOX] ?: defaults.showSubjectBox,
            showTargetZone = p[Keys.SHOW_TARGET_ZONE] ?: defaults.showTargetZone,
            showHorizon = p[Keys.SHOW_HORIZON] ?: defaults.showHorizon,
            overlayAlpha = p[Keys.OVERLAY_ALPHA] ?: defaults.overlayAlpha,
            soundEnabled = p[Keys.SOUND_ENABLED] ?: defaults.soundEnabled,
            saveOriginal = p[Keys.SAVE_ORIGINAL] ?: defaults.saveOriginal,
            proModeEnabled = p[Keys.PRO_MODE] ?: defaults.proModeEnabled,
            aiAutoZoomEnabled = p[Keys.AUTO_ZOOM] ?: defaults.aiAutoZoomEnabled
        )
    }

    /** 全量写回设置 */
    suspend fun update(s: SettingsState) {
        context.settingsDataStore.edit { p ->
            p[Keys.SHOW_GRID] = s.showGrid
            p[Keys.SHOW_SUBJECT_BOX] = s.showSubjectBox
            p[Keys.SHOW_TARGET_ZONE] = s.showTargetZone
            p[Keys.SHOW_HORIZON] = s.showHorizon
            p[Keys.OVERLAY_ALPHA] = s.overlayAlpha
            p[Keys.SOUND_ENABLED] = s.soundEnabled
            p[Keys.SAVE_ORIGINAL] = s.saveOriginal
            p[Keys.PRO_MODE] = s.proModeEnabled
            p[Keys.AUTO_ZOOM] = s.aiAutoZoomEnabled
        }
    }
}
