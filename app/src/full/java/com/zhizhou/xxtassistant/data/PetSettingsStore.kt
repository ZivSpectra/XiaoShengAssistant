package com.zhizhou.xxtassistant.data

import android.content.Context

/** 保存桌宠是否由用户开启。只保存开关，不保存账号、Cookie 或屏幕内容。 */
class PetSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xxt_pet_settings", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
