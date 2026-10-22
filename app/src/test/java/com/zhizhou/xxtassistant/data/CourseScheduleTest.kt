package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseScheduleTest {
    @Test
    fun legacyRangeRemainsValidWhenNoExplicitWeeksAreStored() {
        val schedule = sample(startWeek = 3, endWeek = 8)
        assertFalse(schedule.occursInWeek(2))
        assertTrue(schedule.occursInWeek(3))
        assertTrue(schedule.occursInWeek(8))
        assertFalse(schedule.occursInWeek(9))
    }

    @Test
    fun explicitWeeksOverrideTheLegacyRange() {
        val schedule = sample(startWeek = 1, endWeek = 16, weeks = listOf(1, 3, 5, 7))
        assertTrue(schedule.occursInWeek(5))
        assertFalse(schedule.occursInWeek(2))
        assertFalse(schedule.occursInWeek(9))
    }

    @Test
    fun oddAndEvenPatternsAreRestrictedToTheConfiguredRange() {
        val odd = sample(startWeek = 2, endWeek = 6, weekParity = 1)
        val even = sample(startWeek = 2, endWeek = 6, weekParity = 2)
        assertFalse(odd.occursInWeek(2))
        assertTrue(odd.occursInWeek(3))
        assertFalse(even.occursInWeek(3))
        assertTrue(even.occursInWeek(6))
    }

    private fun sample(
        startWeek: Int = 1,
        endWeek: Int = 20,
        weeks: List<Int> = emptyList(),
        weekParity: Int = 0
    ) = CourseSchedule(
        id = "course",
        accountId = "account",
        term = "2026-2027-1",
        name = "课程",
        weekday = 1,
        startPeriod = 1,
        endPeriod = 2,
        startWeek = startWeek,
        endWeek = endWeek,
        weeks = weeks,
        weekParity = weekParity
    )
}
