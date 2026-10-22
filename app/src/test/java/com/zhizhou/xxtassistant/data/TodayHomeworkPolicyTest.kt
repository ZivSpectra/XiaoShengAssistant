package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TodayHomeworkPolicyTest {
    @Test
    fun keepsOnlyUnfinishedHomeworksInsideSelectedDateRange() {
        val old = homework("old", endTime = 1_800_000_000_000L)
        val inRange = homework("current", endTime = 1_800_100_000_000L)
        val outside = homework("future", endTime = 1_800_200_000_000L)
        val completed = homework("done", endTime = 1_800_100_000_000L, status = Homework.DONE)

        val result = TodayHomeworkPolicy.filter(
            listOf(old, inRange, outside, completed),
            filterAll = false,
            startDay = 1_800_099_000_000L,
            endDay = 1_800_101_000_000L
        )

        assertEquals(listOf("current"), result.map { it.id })
    }

    private fun homework(id: String, endTime: Long, status: Int = Homework.PENDING) = Homework(
        id = id,
        accountId = "account",
        courseName = "course",
        title = id,
        endTime = endTime,
        status = status,
        url = ""
    )
}
