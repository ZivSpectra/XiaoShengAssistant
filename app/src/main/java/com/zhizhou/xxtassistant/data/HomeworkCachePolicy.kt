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
        val mergedEndTime = when {
            // A detail-page observation is authoritative. A later list page
            // must never replace it with a stale or differently interpreted
            // summary value.
            incoming.deadlineVerified && incomingKnown -> incoming.endTime
            existing.deadlineVerified && existingKnown -> existing.endTime
            !incomingKnown && existingKnown -> existing.endTime
            else -> incoming.endTime
        }
        val mergedOfficial = when {
            incoming.deadlineVerified && incomingKnown -> incoming.endTime
            existing.deadlineVerified && existingKnown -> existing.endTime
            incoming.officialEndTime != null && incoming.officialEndTime > 0L -> incoming.officialEndTime
            incomingKnown -> incoming.endTime
            existing.officialEndTime != null && existing.officialEndTime > 0L -> existing.officialEndTime
            existingKnown -> existing.endTime
            else -> null
        }

        return incoming.copy(
            endTime = mergedEndTime,
            officialEndTime = mergedOfficial,
            // A list response can omit or mislabel completion while the
            // detail/source snapshot is still authoritative. Keep the
            // strongest server-observed state in the cache; a user's local
            // completion toggle is stored separately and can still be undone.
            status = mergeStatus(existing.status, incoming.status),
            deadlineVerified = (incoming.deadlineVerified && incomingKnown) ||
                (existing.deadlineVerified && existingKnown)
        )
    }

    /**
     * 合并一次课程抓取快照。普通列表快照不提供可靠的“作业已移除”证据，
     * 因此只要旧作业没有被新条目替代，就保留它；未知时间也不能成为删除理由。
     */
    fun mergeSnapshot(
        existing: List<Homework>,
        incoming: List<Homework>,
        confirmedRemovedKeys: Set<String> = emptySet()
    ): List<Homework> {
        val oldByKey = existing.associateBy(::key)
        // Some endpoints return the same work twice (for example an API row
        // followed by its browser fallback). Coalesce before distinctBy so a
        // later row with a reliable deadline cannot be discarded merely
        // because the first duplicate was unknown.
        val incomingByKey = LinkedHashMap<String, Homework>()
        incoming.forEach { item ->
            val identity = key(item)
            val previous = incomingByKey[identity]
            incomingByKey[identity] = if (previous == null) item else merge(previous, item)
        }
        val mergedIncoming = incomingByKey.values.map { item -> merge(oldByKey[key(item)], item) }
        // A list response is not proof that an omitted item was deleted.  Only
        // detail probes that explicitly observed removal may remove a cached item.
        val preserved = missingFrom(existing, incoming)
            .filterNot { key(it) in confirmedRemovedKeys }
        return (mergedIncoming + preserved).distinctBy(::key)
    }

    /**
     * Merge two representations of the same course's list (for example the
     * task-list API and the browser list fallback).  The fallback is a
     * supplement, not a replacement: an incomplete HTML parse must not erase
     * an item that the API already returned.
     *
     * The stable identity is course key + homework id.  A known deadline wins
     * over an unknown one, while the later source may still provide the usable
     * official detail URL.
     */
    fun mergeHomeworkSources(
        primary: List<Homework>,
        supplement: List<Homework>
    ): List<Homework> {
        val merged = LinkedHashMap<String, Homework>()
        primary.forEach { item ->
            val identity = key(item)
            val previous = merged[identity]
            merged[identity] = if (previous == null) item else mergeSourceItem(previous, item)
        }
        supplement.forEach { item ->
            val identity = key(item)
            val previous = merged[identity]
            merged[identity] = if (previous == null) item else mergeSourceItem(previous, item)
        }
        return merged.values.toList()
    }

    /** 返回网络快照未包含的旧自动作业；用于缓存与当前列表保持一致的保留规则。 */
    fun missingFrom(existing: List<Homework>, incoming: List<Homework>): List<Homework> {
        val incomingKeys = incoming.mapTo(HashSet(), ::key)
        return existing.filter { old -> !old.isManual() && key(old) !in incomingKeys }
    }

    /** Stable identity used when a detail probe confirms that one cached item was removed. */
    fun keyOf(homework: Homework): String = key(homework)

    private fun key(homework: Homework): String =
        "${homework.accountId}:${homework.courseKey}:${homework.id}"

    private fun mergeSourceItem(primary: Homework, supplement: Homework): Homework {
        val endTime = when {
            supplement.deadlineVerified && supplement.endTime > 0L -> supplement.endTime
            primary.deadlineVerified && primary.endTime > 0L -> primary.endTime
            primary.endTime > 0L -> primary.endTime
            supplement.endTime > 0L -> supplement.endTime
            else -> 0L
        }
        val official = when {
            supplement.deadlineVerified && supplement.endTime > 0L -> supplement.endTime
            primary.deadlineVerified && primary.endTime > 0L -> primary.endTime
            primary.officialEndTime != null && primary.officialEndTime > 0L -> primary.officialEndTime
            supplement.officialEndTime != null && supplement.officialEndTime > 0L -> supplement.officialEndTime
            else -> null
        }
        val status = mergeStatus(primary.status, supplement.status)
        return primary.copy(
            title = supplement.title.ifBlank { primary.title },
            courseName = supplement.courseName.ifBlank { primary.courseName },
            endTime = endTime,
            status = status,
            url = supplement.url.ifBlank { primary.url },
            officialEndTime = official,
            deadlineOverridden = primary.deadlineOverridden || supplement.deadlineOverridden,
            deadlineVerified = (primary.deadlineVerified && primary.endTime > 0L) ||
                (supplement.deadlineVerified && supplement.endTime > 0L)
        )
    }

    /**
     * Completion states are monotonic while merging server snapshots. This is
     * intentionally limited to cache/source reconciliation: local completion
     * is kept in LocalCompletionStore and its toggle remains reversible.
     */
    private fun mergeStatus(existing: Int, incoming: Int): Int = when {
        existing == Homework.DONE || incoming == Homework.DONE -> Homework.DONE
        existing == Homework.SUBMITTED || incoming == Homework.SUBMITTED -> Homework.SUBMITTED
        else -> Homework.PENDING
    }
}
