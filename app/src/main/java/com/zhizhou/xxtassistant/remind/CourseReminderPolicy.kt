package com.zhizhou.xxtassistant.remind

/** Course notices stay visible until the user explicitly acknowledges them. */
internal object CourseReminderPolicy {
    data class Lifetime(val ongoing: Boolean, val autoCancel: Boolean)

    val untilAcknowledged = Lifetime(ongoing = true, autoCancel = false)

    fun exactAlarmsEffective(enabled: Boolean, permissionGranted: Boolean): Boolean =
        enabled && permissionGranted

    fun alarmModeChanged(previous: Boolean?, current: Boolean): Boolean =
        previous == null || previous != current
}
