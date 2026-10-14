package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkCheckPolicyTest {
    private val now = 2_000L

    @Test
    fun unknownPendingHomeworkNeedsDetailCheck() {
        assertTrue(HomeworkCheckPolicy.needsDetailCheck(homework(0L, Homework.PENDING), now))
    }

    @Test
    fun expiredPendingHomeworkNeedsDetailCheck() {
        assertTrue(HomeworkCheckPolicy.needsDetailCheck(homework(1_999L, Homework.PENDING), now))
    }

    @Test
    fun futureHomeworkDoesNotNeedRepeatedDetailCheck() {
        assertFalse(HomeworkCheckPolicy.needsDetailCheck(homework(2_001L, Homework.PENDING), now))
    }

    @Test
    fun completedUnknownHomeworkDoesNotTriggerDeepCheck() {
        assertFalse(HomeworkCheckPolicy.needsDetailCheck(homework(0L, Homework.DONE), now))
    }

    private fun homework(endTime: Long, status: Int) = Homework(
        id = "work-1",
        accountId = "account-1",
        courseName = "课程",
        title = "作业",
        endTime = endTime,
        status = status,
        url = "",
        courseKey = "course-1"
    )
}
