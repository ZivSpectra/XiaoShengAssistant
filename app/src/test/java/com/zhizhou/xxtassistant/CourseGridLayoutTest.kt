package com.zhizhou.xxtassistant

import com.zhizhou.xxtassistant.data.CourseSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseGridLayoutTest {
    @Test
    fun unrelatedConflictGroupsKeepFullWidth() {
        val first = course("first", 1, 1, 2)
        val conflict = course("conflict", 1, 1, 2, name = "同一时段")
        val later = course("later", 1, 5, 6)

        val placements = buildGridPlacements(listOf(first, conflict, later)).associateBy { it.course.id }

        assertEquals(2, placements.getValue("first").laneCount)
        assertEquals(2, placements.getValue("conflict").laneCount)
        assertEquals(1, placements.getValue("later").laneCount)
    }

    @Test
    fun adjacentPeriodsAreNotOverlapping() {
        val first = course("first", 1, 1, 2)
        val next = course("next", 1, 3, 4)

        val placements = buildGridPlacements(listOf(first, next)).associateBy { it.course.id }

        assertEquals(1, placements.getValue("first").laneCount)
        assertEquals(1, placements.getValue("next").laneCount)
    }

    @Test
    fun actualPeriodOverlapUsesSeparateLanes() {
        val first = course("first", 1, 1, 2)
        val overlapping = course("overlapping", 1, 2, 3)

        val placements = buildGridPlacements(listOf(first, overlapping)).associateBy { it.course.id }

        assertEquals(2, placements.getValue("first").laneCount)
        assertEquals(2, placements.getValue("overlapping").laneCount)
        assertEquals(0, placements.getValue("first").lane)
        assertEquals(1, placements.getValue("overlapping").lane)
    }

    private fun course(
        id: String,
        weekday: Int,
        start: Int,
        end: Int,
        name: String = id
    ) = CourseSchedule(
        id = id,
        accountId = "account",
        term = "2026-2027-1",
        name = name,
        weekday = weekday,
        startPeriod = start,
        endPeriod = end
    )
}
