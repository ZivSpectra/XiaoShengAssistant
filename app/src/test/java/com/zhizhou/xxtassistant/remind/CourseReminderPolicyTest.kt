package com.zhizhou.xxtassistant.remind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseReminderPolicyTest {
    @Test
    fun courseNoticeStaysUntilUserAcknowledgesIt() {
        val lifetime = CourseReminderPolicy.untilAcknowledged

        assertTrue(lifetime.ongoing)
        assertFalse(lifetime.autoCancel)
    }

    @Test
    fun courseAlarmsRebuildWhenEffectiveExactModeChanges() {
        assertTrue(CourseReminderPolicy.alarmModeChanged(previous = null, current = false))
        assertTrue(CourseReminderPolicy.alarmModeChanged(previous = false, current = true))
        assertTrue(CourseReminderPolicy.alarmModeChanged(previous = true, current = false))
        assertFalse(CourseReminderPolicy.alarmModeChanged(previous = true, current = true))
        assertFalse(CourseReminderPolicy.exactAlarmsEffective(enabled = true, permissionGranted = false))
        assertTrue(CourseReminderPolicy.exactAlarmsEffective(enabled = true, permissionGranted = true))
    }

    @Test
    fun preciseModeUsesAlarmClockOnlyAfterSpecialAccessIsGranted() {
        assertFalse(ReminderAlarmPolicy.useAlarmClock(false, true))
        assertFalse(ReminderAlarmPolicy.useAlarmClock(true, false))
        assertTrue(ReminderAlarmPolicy.useAlarmClock(true, true))
    }
}
