package com.zhizhou.xxtassistant.data

/** 主列表的时间范围规则，独立出来便于回归测试。 */
object HomeworkScope {

    @JvmStatic
    fun contains(homework: Homework, filterAll: Boolean, startDay: Long, endDay: Long): Boolean {
        if (filterAll) return true
        // Unknown deadlines cannot be proven to belong to a selected range.
        // They are shown by the dedicated unknown-deadline prompt instead of
        // contaminating a date-filtered list with old courses.
        if (homework.endTime <= 0L) return false
        return homework.endTime in startDay..endDay
    }
}
