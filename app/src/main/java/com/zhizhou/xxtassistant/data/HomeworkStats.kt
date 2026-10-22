package com.zhizhou.xxtassistant.data

/**
 * 主列表顶部统计的纯规则，避免把待批阅状态误算成已完成。
 *
 * 未完成仍包含已过期但尚未完成的作业；欠账是其中的一个子集，和列表中的
 * 红色欠账区域保持一致。
 */
data class HomeworkStats(
    val unfinished: Int,
    val done: Int,
    val missed: Int
)

object HomeworkStatsCalculator {
    fun calculate(list: List<Homework>, now: Long = System.currentTimeMillis()): HomeworkStats =
        HomeworkStats(
            unfinished = list.count { it.status == Homework.PENDING },
            done = list.count { it.status == Homework.DONE },
            missed = list.count { it.isMissed(now) }
        )
}
