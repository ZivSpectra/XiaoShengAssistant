package com.zhizhou.xxtassistant.remind

/** Course notices stay visible until the user explicitly acknowledges them. */
internal object CourseReminderPolicy {
    data class Lifetime(val ongoing: Boolean, val autoCancel: Boolean)

    val untilAcknowledged = Lifetime(ongoing = true, autoCancel = false)

    fun exactAlarmsEffective(enabled: Boolean, permissionGranted: Boolean): Boolean =
        enabled && permissionGranted

    fun alarmModeChanged(previous: Boolean?, current: Boolean): Boolean =
        previous == null || previous != current

    fun triggerAt(startAt: Long, leadMinutes: Int): Long =
        startAt - leadMinutes * 60_000L

    fun upcomingTriggerAt(startAt: Long, now: Long, leadMinutes: Int): Long? {
        if (startAt <= now) return null
        return maxOf(triggerAt(startAt, leadMinutes), minOf(now + 1_000L, startAt))
    }

    fun showTriggerAt(
        startAt: Long,
        now: Long,
        leadMinutes: Int,
        acknowledged: Boolean,
        activeNotification: Boolean
    ): Long? = if (acknowledged || activeNotification) null else upcomingTriggerAt(startAt, now, leadMinutes)
}
