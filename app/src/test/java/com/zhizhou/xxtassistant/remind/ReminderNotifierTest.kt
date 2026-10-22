package com.zhizhou.xxtassistant.remind

import com.zhizhou.xxtassistant.data.Homework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNotifierTest {
    @Test
    fun notificationId_isStableForTheSameReminder() {
        val first = ReminderNotifier.notificationId(
            accountId = "account-1",
            course = "模拟电子技术",
            title = "第五章作业",
            minutesLeft = 30L,
            url = "https://mooc1.chaoxing.com/work/1"
        )
        val second = ReminderNotifier.notificationId(
            accountId = "account-1",
            course = "模拟电子技术",
            title = "第五章作业",
            minutesLeft = 30L,
            url = "https://mooc1.chaoxing.com/work/1"
        )

        assertEquals(first, second)
        assertTrue(first > 0)
    }

    @Test
    fun notificationId_separatesReminderNodes() {
        val thirtyMinutes = ReminderNotifier.notificationId(
            "account-1", "模拟电子技术", "第五章作业", 30L,
            "https://mooc1.chaoxing.com/work/1"
        )
        val threeHours = ReminderNotifier.notificationId(
            "account-1", "模拟电子技术", "第五章作业", 180L,
            "https://mooc1.chaoxing.com/work/1"
        )

        assertNotEquals(thirtyMinutes, threeHours)
    }

    @Test
    fun notificationId_usesStableHomeworkTokenForSameNamedAssignments() {
        val first = ReminderNotifier.notificationId(
            accountId = "account-1",
            course = "模拟电子技术",
            title = "作业一",
            minutesLeft = 30L,
            url = "https://mooc1.chaoxing.com/work/shared",
            reminderToken = "account-1:course-1:work-1:30"
        )
        val second = ReminderNotifier.notificationId(
            accountId = "account-1",
            course = "模拟电子技术",
            title = "作业一",
            minutesLeft = 30L,
            url = "https://mooc1.chaoxing.com/work/shared",
            reminderToken = "account-1:course-1:work-2:30"
        )

        assertNotEquals(first, second)
    }

    @Test
    fun notificationId_keepsLegacyFieldFallbackWithoutToken() {
        val legacy = ReminderNotifier.notificationId(
            "account-1", "模拟电子技术", "作业一", 30L,
            "https://mooc1.chaoxing.com/work/shared"
        )
        val explicitEmptyToken = ReminderNotifier.notificationId(
            "account-1", "模拟电子技术", "作业一", 30L,
            "https://mooc1.chaoxing.com/work/shared", ""
        )

        assertEquals(legacy, explicitEmptyToken)
    }

    @Test
    fun reminderNodeMinutes_parsesOnlySupportedReminderNodes() {
        assertEquals(30, reminderNodeMinutes("account:course:homework:30"))
        assertEquals(10_080, reminderNodeMinutes("account:course:homework:10080"))
        assertEquals(null, reminderNodeMinutes("account:course:homework:0"))
        assertEquals(null, reminderNodeMinutes("account:course:homework:10081"))
        assertEquals(null, reminderNodeMinutes("account:course:homework:later"))
    }

    @Test
    fun staleReminderIsRejectedWhenHomeworkIsCompletedExpiredHiddenOrBelongsToAnotherAccount() {
        val homework = Homework(
            id = "work-1",
            accountId = "account-1",
            courseName = "课程",
            title = "作业",
            endTime = 10_000L,
            status = Homework.PENDING,
            url = "",
            courseKey = "course-1"
        )
        val token = homework.reminderToken(30)

        assertTrue(isCurrentReminderEligible(homework, "account-1", token, 30, 9_000L, false))
        assertEquals(false, isCurrentReminderEligible(homework.copy(status = Homework.DONE), "account-1", token, 30, 9_000L, false))
        assertEquals(false, isCurrentReminderEligible(homework, "account-1", token, 30, 10_000L, false))
        assertEquals(false, isCurrentReminderEligible(homework, "account-1", token, 30, 9_000L, true))
        assertEquals(false, isCurrentReminderEligible(homework, "account-2", token, 30, 9_000L, false))
        assertEquals(false, isCurrentReminderEligible(homework, "account-1", token + "-stale", 30, 9_000L, false))
    }

    @Test
    fun remainingReminderMinutes_roundsUpAndNeverGoesNegative() {
        assertEquals(30L, remainingReminderMinutes(endTime = 1_800_000L, now = 1L))
        assertEquals(2L, remainingReminderMinutes(endTime = 120_001L, now = 1L))
        assertEquals(1L, remainingReminderMinutes(endTime = 60_001L, now = 1L))
        assertEquals(0L, remainingReminderMinutes(endTime = 60_000L, now = 60_000L))
        assertEquals(0L, remainingReminderMinutes(endTime = 59_999L, now = 60_000L))
    }
}
