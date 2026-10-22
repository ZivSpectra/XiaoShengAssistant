package com.zhizhou.xxtassistant.remind

import android.content.Context

/**
 * Prevents the AlarmManager and WorkManager backup paths from sounding twice
 * for the same reminder. The deadline is part of the key so a later deadline
 * correction can legitimately create a new reminder for the same assignment.
 */
internal object ReminderDeliveryStore {
    private const val PREFS = "xxt_reminder_delivery"
    private const val MAX_AGE_MS = 8L * 24L * 60L * 60L * 1000L

    @Synchronized
    fun claim(context: Context, token: String, endTime: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (token.isBlank()) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$token|$endTime"
        if (prefs.contains(key)) return false
        val editor = prefs.edit().putLong(key, now)
        prefs.all.forEach { (storedKey, value) ->
            val timestamp = value as? Long ?: return@forEach
            if (now - timestamp > MAX_AGE_MS) editor.remove(storedKey)
        }
        // A transient storage failure must not silently drop a user-visible
        // reminder. If the claim cannot be persisted, allow this delivery and
        // accept that a later retry may produce one duplicate rather than no
        // notification at all.
        return editor.commit() || !prefs.contains(key)
    }
}
