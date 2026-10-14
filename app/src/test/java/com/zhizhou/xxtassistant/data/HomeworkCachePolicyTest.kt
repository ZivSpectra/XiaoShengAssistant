package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeworkCachePolicyTest {

    @Test
    fun temporaryUnknownSnapshotKeepsKnownDeadline() {
        val existing = homework(endTime = 1_791_270_000_000L, officialEndTime = 1_791_270_000_000L)
        val incoming = existing.copy(title = "更新后的标题", endTime = 0L, officialEndTime = null)

        val merged = HomeworkCachePolicy.merge(existing, incoming)

        assertEquals(existing.endTime, merged.endTime)
        assertEquals(existing.endTime, merged.officialEndTime)
        assertEquals("更新后的标题", merged.title)
    }

    @Test
    fun newerKnownDeadlineReplacesOldDeadline() {
        val existing = homework(endTime = 1_791_270_000_000L)
        val incoming = existing.copy(endTime = 1_791_370_000_000L)

        val merged = HomeworkCachePolicy.merge(existing, incoming)

        assertEquals(incoming.endTime, merged.endTime)
        assertEquals(incoming.endTime, merged.officialEndTime)
    }

    @Test
    fun unknownWithoutPreviousValueStaysUnknown() {
        val incoming = homework(endTime = 0L)

        val merged = HomeworkCachePolicy.merge(null, incoming)

        assertEquals(0L, merged.endTime)
        assertNull(merged.officialEndTime)
    }

    @Test
    fun successfulListSnapshotDoesNotDeleteKnownHomeworkThatWasTemporarilyOmitted() {
        val known = homework(endTime = 1_791_270_000_000L)

        val merged = HomeworkCachePolicy.mergeSnapshot(
            existing = listOf(known),
            incoming = emptyList()
        )

        assertEquals(listOf(known), merged)
    }

    @Test
    fun failedCourseKeepsUnknownAndKnownCachedHomework() {
        val known = homework(endTime = 1_791_270_000_000L)
        val unknown = homework(endTime = 0L).copy(id = "work-2")

        val merged = HomeworkCachePolicy.mergeSnapshot(
            existing = listOf(known, unknown),
            incoming = emptyList()
        )

        assertEquals(listOf(known, unknown), merged)
    }

    @Test
    fun successfulSnapshotDoesNotDeletePreviouslyKnownHomeworkWithUnknownDeadline() {
        val unknown = homework(endTime = 0L)

        val merged = HomeworkCachePolicy.mergeSnapshot(
            existing = listOf(unknown),
            incoming = emptyList()
        )

        assertEquals(listOf(unknown), merged)
    }

    @Test
    fun missingFromSnapshotKeepsUnknownAutomaticHomeworkButNotManualItems() {
        val unknown = homework(endTime = 0L)
        val manual = unknown.copy(id = "manual-1", source = Homework.MANUAL)

        val missing = HomeworkCachePolicy.missingFrom(
            existing = listOf(unknown, manual),
            incoming = emptyList()
        )

        assertEquals(listOf(unknown), missing)
    }

    private fun homework(endTime: Long, officialEndTime: Long? = null) = Homework(
        id = "work-1",
        accountId = "account-1",
        courseName = "课程",
        title = "作业",
        endTime = endTime,
        status = Homework.PENDING,
        url = "https://mooc1.chaoxing.com/work",
        courseKey = "course-1",
        officialEndTime = officialEndTime
    )
}
