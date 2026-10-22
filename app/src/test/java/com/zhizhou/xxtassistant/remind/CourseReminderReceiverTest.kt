package com.zhizhou.xxtassistant.remind

import com.zhizhou.xxtassistant.data.CourseSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CourseReminderReceiverTest {
    @Test
    fun notificationId_separatesAccountsAndOccurrences() {
        val first = courseNotificationId("account-1", "course-1", 1_000L)

        assertEquals(first, courseNotificationId("account-1", "course-1", 1_000L))
        assertNotEquals(first, courseNotificationId("account-2", "course-1", 1_000L))
        assertNotEquals(first, courseNotificationId("account-1", "course-1", 2_000L))
    }

    @Test
    fun preparationNotificationId_separatesAccounts() {
        assertNotEquals(
            preparationNotificationId("account-1", 1_000L),
            preparationNotificationId("account-2", 1_000L)
        )
    }

    @Test
    fun preparationSummary_containsOnlyPeriodAndCourseName() {
        val courses = listOf(
            CourseSchedule(
                id = "late",
                accountId = "account-1",
                term = "",
                name = "线性代数",
                room = "A-101",
                weekday = 1,
                startPeriod = 3,
                endPeriod = 4
            ),
            CourseSchedule(
                id = "early",
                accountId = "account-1",
                term = "",
                name = "模拟电子技术",
                teacher = "张老师",
                room = "B-201",
                weekday = 1,
                startPeriod = 1,
                endPeriod = 2
            )
        )

        val summary = preparationSummary(courses)

        assertEquals("第 1-2 节  模拟电子技术\n第 3-4 节  线性代数", summary)
        assertFalse(summary.contains("A-101"))
        assertFalse(summary.contains("张老师"))
    }
}
