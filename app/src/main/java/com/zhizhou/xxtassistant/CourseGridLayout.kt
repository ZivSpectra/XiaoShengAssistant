package com.zhizhou.xxtassistant

import com.zhizhou.xxtassistant.data.CourseSchedule

/** A course's horizontal slot within one connected group of overlapping classes. */
internal data class CourseGridPlacement(
    val course: CourseSchedule,
    val lane: Int,
    val laneCount: Int
)

/**
 * Keeps unrelated classes full-width. A weekday can contain several separate
 * conflict groups, and only courses inside the same group need side-by-side
 * lanes.
 */
internal fun buildGridPlacements(schedules: List<CourseSchedule>): List<CourseGridPlacement> =
    buildList {
        for (weekday in 1..7) {
            val courses = schedules.asSequence()
                .filter { it.weekday == weekday }
                .sortedWith(
                    compareBy<CourseSchedule> { it.startPeriod }
                        .thenByDescending { it.endPeriod }
                        .thenBy { it.name }
                        .thenBy { it.id }
                )
                .toList()
            val group = mutableListOf<CourseSchedule>()
            var groupEnd = 0

            fun appendGroup() {
                if (group.isEmpty()) return
                val laneEnds = mutableListOf<Int>()
                val assigned = group.map { course ->
                    val lane = laneEnds.indexOfFirst { end -> end < course.startPeriod }
                        .let { if (it >= 0) it else laneEnds.size }
                    if (lane == laneEnds.size) laneEnds += course.endPeriod
                    else laneEnds[lane] = course.endPeriod
                    course to lane
                }
                val laneCount = laneEnds.size.coerceAtLeast(1)
                assigned.forEach { (course, lane) -> add(CourseGridPlacement(course, lane, laneCount)) }
                group.clear()
            }

            courses.forEach { course ->
                if (group.isNotEmpty() && course.startPeriod > groupEnd) appendGroup()
                group += course
                groupEnd = maxOf(groupEnd, course.endPeriod)
            }
            appendGroup()
        }
    }
