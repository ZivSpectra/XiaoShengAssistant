package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun listSnapshotCannotReplaceDeadlineConfirmedByDetailPage() {
        val confirmed = homework(
            endTime = 1_791_270_000_000L,
            officialEndTime = 1_791_270_000_000L
        ).copy(deadlineVerified = true)
        val laterList = confirmed.copy(
            endTime = 1_791_370_000_000L,
            officialEndTime = 1_791_370_000_000L,
            deadlineVerified = false
        )

        val merged = HomeworkCachePolicy.merge(confirmed, laterList)

        assertEquals(confirmed.endTime, merged.endTime)
        assertEquals(confirmed.endTime, merged.officialEndTime)
        assertTrue(merged.deadlineVerified)
    }

    @Test
    fun unknownWithoutPreviousValueStaysUnknown() {
        val incoming = homework(endTime = 0L)

        val merged = HomeworkCachePolicy.merge(null, incoming)

        assertEquals(0L, merged.endTime)
        assertNull(merged.officialEndTime)
    }

    @Test
    fun completedStatusIsNotDowngradedByAnIncompleteListSnapshot() {
        val existing = homework(endTime = 1_791_270_000_000L).copy(status = Homework.DONE)
        val incoming = existing.copy(status = Homework.PENDING, endTime = 0L, officialEndTime = null)

        val merged = HomeworkCachePolicy.merge(existing, incoming)

        assertEquals(Homework.DONE, merged.status)
        assertEquals(existing.endTime, merged.endTime)
    }

    @Test
    fun duplicateSnapshotItemsAreCoalescedWithKnownDeadlineWinning() {
        val unknown = homework(endTime = 0L)
        val known = unknown.copy(
            endTime = 1_791_370_000_000L,
            officialEndTime = 1_791_370_000_000L,
            status = Homework.DONE
        )

        val merged = HomeworkCachePolicy.mergeSnapshot(
            existing = emptyList(),
            incoming = listOf(unknown, known)
        )

        assertEquals(1, merged.size)
        assertEquals(known.endTime, merged.single().endTime)
        assertEquals(Homework.DONE, merged.single().status)
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
    fun missingHomeworkIsRemovedOnlyWhenDetailProbeConfirmsItsStableKey() {
        val known = homework(endTime = 1_791_270_000_000L)

        val merged = HomeworkCachePolicy.mergeSnapshot(
            existing = listOf(known),
            incoming = emptyList(),
            confirmedRemovedKeys = setOf(HomeworkCachePolicy.keyOf(known))
        )

        assertEquals(emptyList<Homework>(), merged)
    }

    @Test
    fun anUnconfirmedMissingHomeworkIsStillPreserved() {
        val known = homework(endTime = 1_791_270_000_000L)

        val merged = HomeworkCachePolicy.mergeSnapshot(
            existing = listOf(known),
            incoming = emptyList(),
            confirmedRemovedKeys = emptySet()
        )

        assertEquals(listOf(known), merged)
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

    @Test
    fun browserSupplementCompletesApiItemWithoutDroppingOtherItems() {
        val apiUnknown = homework(endTime = 0L)
        val apiOnly = homework(endTime = 1_791_270_000_000L).copy(id = "work-2")
        val browserKnown = apiUnknown.copy(
            endTime = 1_791_370_000_000L,
            officialEndTime = 1_791_370_000_000L,
            url = "https://mooc1.chaoxing.com/detail"
        )

        val merged = HomeworkCachePolicy.mergeHomeworkSources(
            primary = listOf(apiUnknown, apiOnly),
            supplement = listOf(browserKnown)
        )

        assertEquals(2, merged.size)
        assertEquals(browserKnown.endTime, merged.first { it.id == apiUnknown.id }.endTime)
        assertEquals(apiOnly.id, merged.first { it.id == apiOnly.id }.id)
    }

    @Test
    fun verifiedBrowserDeadlineWinsOverUnverifiedApiDeadline() {
        val api = homework(endTime = 1_791_270_000_000L)
        val detail = api.copy(
            endTime = 1_791_370_000_000L,
            officialEndTime = 1_791_370_000_000L,
            deadlineVerified = true
        )

        val merged = HomeworkCachePolicy.mergeHomeworkSources(
            primary = listOf(api),
            supplement = listOf(detail)
        ).single()

        assertEquals(detail.endTime, merged.endTime)
        assertTrue(merged.deadlineVerified)
    }

    @Test
    fun duplicatePrimarySourceItemsKeepKnownDeadlineWhenUnknownRowArrivesLater() {
        val known = homework(endTime = 1_791_370_000_000L)
        val unknown = known.copy(endTime = 0L, officialEndTime = null)

        val merged = HomeworkCachePolicy.mergeHomeworkSources(
            primary = listOf(known, unknown),
            supplement = emptyList()
        )

        assertEquals(1, merged.size)
        assertEquals(known.endTime, merged.single().endTime)
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
