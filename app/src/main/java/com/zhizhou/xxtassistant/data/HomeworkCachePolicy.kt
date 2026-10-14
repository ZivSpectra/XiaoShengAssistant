package com.zhizhou.xxtassistant.data

/**
 * 合并网络快照和本地缓存的规则。
 *
 * 列表接口有时只返回标题/状态，暂时没有返回截止时间。此时不能把已经由
 * 作业详情页确认过的时间降级成未知，否则下一次打开应用时作业会从日期列表中消失。
 */
object HomeworkCachePolicy {

    fun merge(existing: Homework?, incoming: Homework): Homework {
        if (existing == null) {
            return incoming.copy(
                officialEndTime = incoming.officialEndTime
                    ?: incoming.endTime.takeIf { it > 0L }
            )
        }

        val incomingKnown = incoming.endTime > 0L
        val existingKnown = existing.endTime > 0L
        val mergedEndTime = if (!incomingKnown && existingKnown) {
            existing.endTime
        } else {
            incoming.endTime
        }
        val mergedOfficial = when {
            incoming.officialEndTime != null && incoming.officialEndTime > 0L -> incoming.officialEndTime
            incomingKnown -> incoming.endTime
            existing.officialEndTime != null && existing.officialEndTime > 0L -> existing.officialEndTime
            existingKnown -> existing.endTime
            else -> null
        }

        return incoming.copy(
            endTime = mergedEndTime,
            officialEndTime = mergedOfficial
        )
    }

    /**
     * 合并一次课程抓取快照。普通列表快照不提供可靠的“作业已移除”证据，
     * 因此只要旧作业没有被新条目替代，就保留它；未知时间也不能成为删除理由。
     */
    fun mergeSnapshot(
        existing: List<Homework>,
        incoming: List<Homework>
    ): List<Homework> {
        val oldByKey = existing.associateBy(::key)
        val mergedIncoming = incoming.map { item -> merge(oldByKey[key(item)], item) }
        val preserved = missingFrom(existing, incoming)
        return (mergedIncoming + preserved).distinctBy(::key)
    }

    /** 返回网络快照未包含的旧自动作业；用于缓存与当前列表保持一致的保留规则。 */
    fun missingFrom(existing: List<Homework>, incoming: List<Homework>): List<Homework> {
        val incomingKeys = incoming.mapTo(HashSet(), ::key)
        return existing.filter { old -> !old.isManual() && key(old) !in incomingKeys }
    }

    private fun key(homework: Homework): String =
        "${homework.accountId}:${homework.courseKey}:${homework.id}"
}
