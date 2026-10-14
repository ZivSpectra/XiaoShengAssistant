package com.zhizhou.xxtassistant.data

/** 哪些作业值得消耗一次详情页请求进行截止时间核对。 */
object HomeworkCheckPolicy {

    fun needsDetailCheck(homework: Homework, now: Long): Boolean {
        if (homework.status == Homework.DONE) return false
        return homework.endTime <= 0L || homework.endTime < now
    }
}
