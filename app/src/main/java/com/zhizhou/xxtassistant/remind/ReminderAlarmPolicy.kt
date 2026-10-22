package com.zhizhou.xxtassistant.remind

/**
 * Pure policy for choosing the strongest local alarm available.
 *
 * Alarm-clock alarms are still local-only, but Android treats them as a
 * user-visible alarm and gives them better Doze/OEM survival than a normal
 * exact alarm. They are only selected when the user enabled precise mode and
 * the system granted the special-access permission.
 */
internal object ReminderAlarmPolicy {
    fun useAlarmClock(exactModeEnabled: Boolean, exactPermissionGranted: Boolean): Boolean =
        exactModeEnabled && exactPermissionGranted
}
