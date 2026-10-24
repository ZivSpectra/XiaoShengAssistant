package com.zhizhou.xxtassistant.data

import android.content.Context
import java.time.LocalDate

/** Only package names and local daily durations are recorded; no screen content is collected. */
class FocusSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xxt_focus_settings", Context.MODE_PRIVATE)

    fun registerSettingsListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterSettingsListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun load(): FocusSettings = FocusSettings(
        enabled = prefs.getBoolean("enabled", false),
        entertainmentPackages = prefs.getStringSet("entertainment", emptySet())?.toSet().orEmpty(),
        studyPackage = prefs.getString("study", null),
        dailyLimitMinutes = prefs.getInt("limit_minutes", 30).coerceIn(1, 1440)
    )

    fun save(settings: FocusSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled && settings.configured)
            .putStringSet("entertainment", settings.entertainmentPackages.toSet())
            .putString("study", settings.studyPackage)
            .putInt("limit_minutes", settings.dailyLimitMinutes.coerceIn(1, 1440))
            .apply()
    }

    fun usedToday(packageName: String, date: LocalDate = LocalDate.now()): Long =
        prefs.getLong(usageKey(date, packageName), 0L)

    fun addUsage(packageName: String, elapsedMillis: Long, date: LocalDate = LocalDate.now()) {
        if (elapsedMillis <= 0L) return
        val key = usageKey(date, packageName)
        val editor = prefs.edit()
        val day = date.toString()
        if (prefs.getString("usage_day", null) != day) {
            prefs.all.keys.filter { it.startsWith("usage_") && it != "usage_day" && !it.startsWith("usage_${day}_") }
                .forEach(editor::remove)
            editor.putString("usage_day", day)
        }
        editor.putLong(key, usedToday(packageName, date) + elapsedMillis).apply()
    }

    private fun usageKey(date: LocalDate, packageName: String) = "usage_${date}_$packageName"
}
