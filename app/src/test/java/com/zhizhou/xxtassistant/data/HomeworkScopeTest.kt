package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkScopeTest {
    private val start = 1_000L
    private val end = 2_000L

    @Test
    fun includesCompletedHomeworkInsideSelectedRange() {
        assertTrue(HomeworkScope.contains(homework(endTime = 1_500L, status = Homework.DONE), false, start, end))
    }

    @Test
    fun includesSubmittedHomeworkInsideSelectedRange() {
        assertTrue(HomeworkScope.contains(homework(endTime = 1_500L, status = Homework.SUBMITTED), false, start, end))
    }

    @Test
    fun excludesHomeworkOutsideSelectedRange() {
        assertFalse(HomeworkScope.contains(homework(endTime = 999L), false, start, end))
        assertFalse(HomeworkScope.contains(homework(endTime = 2_001L), false, start, end))
    }

    @Test
    fun excludesUnknownHomeworkFromSelectedRange() {
        val unknownPending = homework(endTime = 0L, status = Homework.PENDING)
        val unknownSubmitted = homework(endTime = 0L, status = Homework.SUBMITTED)
        assertFalse(HomeworkScope.contains(unknownPending, false, start, end))
        assertFalse(HomeworkScope.contains(unknownSubmitted, false, start, end))
    }

    @Test
    fun excludesCompletedHomeworkWithUnknownDeadline() {
        val unknown = homework(endTime = 0L, status = Homework.DONE)
        assertFalse(HomeworkScope.contains(unknown, false, start, end))
        assertTrue(HomeworkScope.contains(unknown, true, start, end))
    }

    @Test
    fun defaultDashboardIncludesOnlyPendingDebtOutsideDefaultRange() {
        val now = 3_000L
        assertTrue(
            HomeworkScope.contains(
                homework(endTime = 500L), false, start, end, includeMissed = true, now = now
            )
        )
        assertFalse(
            HomeworkScope.contains(
                homework(endTime = 500L, status = Homework.SUBMITTED),
                false, start, end, includeMissed = true, now = now
            )
        )
        assertFalse(
            HomeworkScope.contains(
                homework(endTime = 500L, status = Homework.DONE),
                false, start, end, includeMissed = true, now = now
            )
        )
    }

    @Test
    fun explicitlySelectedDateRangeDoesNotLeakExpiredHomework() {
        assertFalse(
            HomeworkScope.contains(
                homework(endTime = 500L), false, start, end, includeMissed = false, now = 3_000L
            )
        )
        assertTrue(
            HomeworkScope.contains(
                homework(endTime = 1_500L), false, start, end, includeMissed = false, now = 3_000L
            )
        )
    }

    @Test
    fun hiddenScopeKeysDistinguishDefaultCustomAndAllRanges() {
        assertEquals("all", HomeworkScope.scopeKey(true, false, start, end))
        assertEquals("default:1000:2000", HomeworkScope.scopeKey(false, false, start, end))
        assertEquals("custom:1000:2000", HomeworkScope.scopeKey(false, true, start, end))
    }

    @Test
    fun hiddenScopeKeyIsStableForTheSameRange() {
        assertEquals(
            HomeworkScope.scopeKey(false, true, start, end),
            HomeworkScope.scopeKey(false, true, start, end)
        )
    }

    private fun homework(endTime: Long, status: Int = Homework.PENDING) = Homework(
        id = "test",
        accountId = "test-account",
        courseName = "Test course",
        title = "Test homework",
        endTime = endTime,
        status = status,
        url = ""
    )
}
