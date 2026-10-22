package com.zhizhou.xxtassistant.data

/** Single source of truth for the homework cards shown on the Today tab. */
object TodayHomeworkPolicy {
    fun filter(
        homeworks: List<Homework>,
        filterAll: Boolean,
        startDay: Long,
        endDay: Long
    ): List<Homework> = homeworks
        .filter { it.isUnfinished() }
        .filter { HomeworkScope.contains(it, filterAll, startDay, endDay) }
        .sortedBy { it.endTime }
}
