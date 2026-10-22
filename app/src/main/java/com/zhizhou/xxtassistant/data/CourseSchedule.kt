package com.zhizhou.xxtassistant.data

/**
 * 一节课的本地排课记录。课程表和学习通作业是两套数据：课表可以来自教务系统、
 * 表格或手动输入，即使学习通暂时没有作业也应该独立存在。
 */
data class CourseSchedule(
    val id: String,
    val accountId: String,
    val term: String,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    /**
     * Explicit teaching weeks. An empty list keeps the legacy contiguous
     * [startWeek, endWeek] representation.
     */
    val weeks: List<Int> = emptyList(),
    /** 0 = every week, 1 = odd weeks, 2 = even weeks. */
    val weekParity: Int = 0,
    val source: Int = SOURCE_MANUAL,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_MANUAL = 0
        const val SOURCE_TABLE = 1
        const val SOURCE_ACADEMIC_WEB = 2
    }

    fun occursInWeek(week: Int): Boolean {
        if (week < 1) return false
        if (weeks.isNotEmpty()) return week in weeks
        if (weekParity == 1) return week % 2 == 1 && week in startWeek..endWeek
        if (weekParity == 2) return week % 2 == 0 && week in startWeek..endWeek
        return week in startWeek..endWeek
    }

    fun isValid(): Boolean = name.isNotBlank() &&
        weekday in 1..7 &&
        startPeriod in 1..24 &&
        endPeriod in startPeriod..24 &&
        startWeek >= 1 && endWeek >= startWeek &&
        weeks.distinct().all { it in 1..60 } && weekParity in 0..2

    fun periodText(): String = if (startPeriod == endPeriod) {
        "第 $startPeriod 节"
    } else {
        "第 $startPeriod-$endPeriod 节"
    }

    fun locationText(): String = listOf(room.trim(), teacher.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}
