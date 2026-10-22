package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeworkStatsTest {
    @Test
    fun submittedIsNotCountedAsDone() {
        val list = listOf(
            homework(Homework.PENDING, 3_000L),
            homework(Homework.SUBMITTED, 3_000L),
            homework(Homework.DONE, 3_000L)
        )

        assertEquals(HomeworkStats(unfinished = 1, done = 1, missed = 0),
            HomeworkStatsCalculator.calculate(list, now = 2_000L))
    }

    @Test
    fun missedRemainsPartOfUnfinished() {
        val list = listOf(homework(Homework.PENDING, 1_000L))

        assertEquals(HomeworkStats(unfinished = 1, done = 0, missed = 1),
            HomeworkStatsCalculator.calculate(list, now = 2_000L))
    }

    private fun homework(status: Int, endTime: Long) = Homework(
        id = status.toString(),
        accountId = "account",
        courseName = "课程",
        title = "作业",
        endTime = endTime,
        status = status,
        url = ""
    )
}
