package com.zhizhou.xxtassistant.remind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun courseTriggerUsesConfiguredLeadTime() {
        val startAt = 10_000_000L
        assertEquals(startAt - 30 * 60_000L, CourseReminderPolicy.triggerAt(startAt, 30))
        assertEquals(startAt - 45 * 60_000L, CourseReminderPolicy.triggerAt(startAt, 45))
    }

    @Test
    fun rebuildingWithinLeadWindowStillSchedulesUpcomingCourse() {
        val startAt = 10_000_000L
        assertEquals(
            startAt - 30 * 60_000L,
            CourseReminderPolicy.upcomingTriggerAt(startAt, startAt - 45 * 60_000L, 30)
        )
        assertEquals(
            startAt - 5 * 60_000L + 1_000L,
            CourseReminderPolicy.upcomingTriggerAt(startAt, startAt - 5 * 60_000L, 30)
        )
        assertEquals(null, CourseReminderPolicy.upcomingTriggerAt(startAt, startAt, 30))
    }

    @Test
    fun alreadyVisibleOrAcknowledgedCourseIsNotRescheduled() {
        val startAt = 10_000_000L
        val now = startAt - 5 * 60_000L
        assertEquals(null, CourseReminderPolicy.showTriggerAt(startAt, now, 30, true, false))
        assertEquals(null, CourseReminderPolicy.showTriggerAt(startAt, now, 30, false, true))
        assertEquals(now + 1_000L, CourseReminderPolicy.showTriggerAt(startAt, now, 30, false, false))
    }
}
