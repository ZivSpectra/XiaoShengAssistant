package com.zhizhou.xxtassistant.data

/**
 * 作业数据模型：一条作业的所有信息
 *
 * status 取值（从学习通页面提取的关键字映射而来）：
 *  - PENDING   未提交（需要你做，红）
 *  - SUBMITTED 已提交待批阅（橙）
 *  - DONE      已完成/已批阅（绿）
 */
data class Homework(
    val id: String,                 // 作业唯一标识（学习通的 workId，手动作业为 manual_时间戳）
    val accountId: String,          // 所属学习通账号 uid（换号后用于恢复本地记录）
    val courseName: String,         // 课程名
    val title: String,              // 作业名
    val endTime: Long,              // 截止时间（毫秒时间戳）
    val status: Int,                // PENDING / SUBMITTED / DONE
    val url: String,                // 学习通里对应的作业页面地址（点击跳转用；手动作业为空）
    val source: Int = AUTO,         // 来源：AUTO=学习通自动检测 / MANUAL=自己手动添加
    val courseKey: String = "",    // 课程稳定键：courseId+classId 或本地手动课程 ID
    val officialEndTime: Long? = null,
    val deadlineOverridden: Boolean = false
) {
    companion object {
        const val PENDING = 0
        const val SUBMITTED = 1
        const val DONE = 2

        const val AUTO = 0    // 学习通自动抓取的作业
        const val MANUAL = 1  // 自己手动添加的作业（线下布置的）
    }

    /** 是否算"未完成"（需要提醒的） */
    fun isUnfinished(): Boolean = status == PENDING

    /** 是否已错过截止时间还没交（最紧急，深红） */
    fun isMissed(now: Long = System.currentTimeMillis()): Boolean =
        status == PENDING && endTime > 0L && endTime < now

    /** 是否是手动添加的作业 */
    fun isManual(): Boolean = source == MANUAL

    /** 状态文字（界面显示用） */
    fun statusText(): String = when (status) {
        PENDING -> "未完成"
        SUBMITTED -> "待批阅"
        else -> "已完成"
    }

    /** 剩余时间文字（界面显示用）：例如 "3 小时 25 分钟后截止" */
    fun remainingText(now: Long = System.currentTimeMillis()): String {
        if (endTime <= 0L) return "截止时间未知"
        val diff = endTime - now
        val minute = 60 * 1000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff <= 0 -> "已截止"
            diff < hour -> "${diff / minute} 分钟后截止"
            diff < day -> {
                val h = diff / hour
                val m = (diff % hour) / minute
                if (m > 0) "${h} 小时 ${m} 分钟后截止" else "${h} 小时后截止"
            }
            else -> "${diff / day} 天后截止"
        }
    }

    /** 短格式剩余时间（双列窄卡片用）：例如 "3 天" "2 小时" "25 分" */
    fun shortRemainingText(now: Long = System.currentTimeMillis()): String {
        if (endTime <= 0L) return "未知"
        val diff = endTime - now
        val minute = 60 * 1000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff <= 0 -> "已截止"
            diff < hour -> "${diff / minute} 分"
            diff < day -> "${diff / hour} 小时"
            else -> "${diff / day} 天"
        }
    }
}
