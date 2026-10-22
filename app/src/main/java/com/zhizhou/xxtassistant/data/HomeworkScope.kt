package com.zhizhou.xxtassistant.data

/** 主列表的时间范围规则，独立出来便于回归测试。 */
object HomeworkScope {

    /**
     * Stable key for list-only hidden records. Keep this policy next to the
     * range predicate so the writer and all readers cannot drift apart.
     */
    @JvmStatic
    fun scopeKey(
        filterAll: Boolean,
        customized: Boolean,
        startDay: Long,
        endDay: Long
    ): String = when {
        filterAll -> "all"
        customized -> "custom:$startDay:$endDay"
        else -> "default:$startDay:$endDay"
    }

    @JvmStatic
    fun contains(homework: Homework, filterAll: Boolean, startDay: Long, endDay: Long): Boolean {
        if (filterAll) return true
        // Unknown deadlines cannot be proven to belong to a selected range.
        // They are shown by the dedicated unknown-deadline prompt instead of
        // contaminating a date-filtered list with old courses.
        if (homework.endTime <= 0L) return false
        return homework.endTime in startDay..endDay
    }

    /** The default dashboard can include pending debt; explicit ranges stay strict. */
    @JvmStatic
    fun contains(
        homework: Homework,
        filterAll: Boolean,
        startDay: Long,
        endDay: Long,
        includeMissed: Boolean,
        now: Long
    ): Boolean {
        if (filterAll) return true
        if (homework.endTime <= 0L) return false
        if (includeMissed && homework.status == Homework.PENDING && homework.endTime < now) {
            return true
        }
        return homework.endTime in startDay..endDay
    }
}
