package com.zhizhou.xxtassistant.data

/**
 * Pure scheduling rules for the confirmation shown after a manual homework
 * deadline. Kept separate from SharedPreferences so the retry semantics can
 * be tested without an Android runtime.
 */
internal object ManualDeadlinePromptPolicy {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    data class Record(
        val endTime: Long,
        val nextPromptAt: Long
    )

    fun shouldPrompt(record: Record?, endTime: Long, now: Long): Boolean {
        return record == null || record.endTime != endTime || record.nextPromptAt <= now
    }

    fun snoozed(endTime: Long, now: Long): Record =
        Record(endTime = endTime, nextPromptAt = now + DAY_MS)
}
