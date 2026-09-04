package com.example.aicamera.filter

import android.content.Context

class CustomFilterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("custom_filters", Context.MODE_PRIVATE)

    fun current(): CustomFilterConfig? = prefs.getString(KEY, null)?.let(CustomFilterConfig::fromText)

    /** 本地滤镜库。兼容旧版本只保存一个 active filter 的数据。 */
    fun all(): List<CustomFilterConfig> {
        val saved = prefs.getStringSet(KEY_LIBRARY, emptySet()).orEmpty()
            .mapNotNull(CustomFilterConfig::fromText)
        val active = current()
        return (listOfNotNull(active) + saved)
            .distinctBy { it.id }
            .sortedByDescending { it.id == active?.id }
    }

    fun save(config: CustomFilterConfig) {
        val code = config.shareCode()
        val library = prefs.getStringSet(KEY_LIBRARY, emptySet()).orEmpty().toMutableSet().apply {
            removeAll { CustomFilterConfig.fromText(it)?.id == config.id }
            add(code)
        }
        prefs.edit()
            .putString(KEY, code)
            .putStringSet(KEY_LIBRARY, library)
            .apply()
    }

    companion object {
        private const val KEY = "active_custom_filter"
        private const val KEY_LIBRARY = "custom_filter_library"
    }
}
