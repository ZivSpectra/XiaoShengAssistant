package com.zhizhou.xxtassistant.remind

import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.CoursePeriod
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
        assertNotEquals(
            courseOccurrenceKey("account-1", "course-1", 1_000L),
            courseOccurrenceKey("account-1", "course-1", 2_000L)
        )
    }

    @Test
    fun preparationNotificationId_separatesAccounts() {
        assertNotEquals(
            preparationNotificationId("account-1", 1_000L),
            preparationNotificationId("account-2", 1_000L)
        )
    }

    @Test
    fun preparationSummary_containsActualTimesAndCourseName() {
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

        val summary = preparationSummary(courses, listOf(
            CoursePeriod(1, 8 * 60, 8 * 60 + 45),
            CoursePeriod(2, 8 * 60 + 55, 9 * 60 + 40),
            CoursePeriod(3, 10 * 60, 10 * 60 + 45),
            CoursePeriod(4, 10 * 60 + 55, 11 * 60 + 40)
        ))

        assertEquals("08:00-09:40  模拟电子技术\n10:00-11:40  线性代数", summary)
        assertFalse(summary.contains("A-101"))
        assertFalse(summary.contains("张老师"))
    }
}
