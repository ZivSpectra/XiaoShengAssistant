package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Records the next time an expired manual homework may be shown again.
 *
 * The record is deliberately a next-at timestamp rather than a permanent
 * "prompted" flag: dismissing the dialog means "ask me tomorrow", while
 * marking the homework complete removes the record entirely.
 */
class ManualDeadlinePromptStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_manual_deadline_prompts", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RECORDS = "records"
    }

    /** True while the item is in its 24-hour snooze window. */
    fun wasPrompted(
        accountId: String,
        homeworkId: String,
        endTime: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val record = read()[key(accountId, homeworkId)]
        return !ManualDeadlinePromptPolicy.shouldPrompt(record, endTime, now)
    }

    /** Schedule the next prompt for tomorrow after the user dismisses it. */
    fun snooze(
        accountId: String,
        homeworkId: String,
        endTime: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val all = read().toMutableMap()
        all[key(accountId, homeworkId)] = ManualDeadlinePromptPolicy.snoozed(endTime, now)
        write(all)
    }

    /** Clear a prompt record after the user explicitly marks the homework done. */
    fun clear(accountId: String, homeworkId: String) {
        val all = read().toMutableMap()
        if (all.remove(key(accountId, homeworkId)) != null) write(all)
    }

    /**
     * Kept as a source-compatible alias for older call sites. New code should
     * call [snooze] or [clear] according to the user's action.
     */
    @Deprecated("Use snooze() or clear() to preserve the user's action")
    fun markPrompted(accountId: String, homeworkId: String, endTime: Long) =
        snooze(accountId, homeworkId, endTime)

    private fun key(accountId: String, homeworkId: String): String = "$accountId:$homeworkId"

    private fun read(): Map<String, ManualDeadlinePromptPolicy.Record> {
        val raw = prefs.getString(KEY_RECORDS, "{}") ?: "{}"
        return try {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { k ->
                    val item = root.optJSONObject(k) ?: return@forEach
                    val endTime = item.optLong("endTime")
                    val nextPromptAt = item.optLong("nextPromptAt")
                    if (endTime > 0L && nextPromptAt > 0L) {
                        put(k, ManualDeadlinePromptPolicy.Record(endTime, nextPromptAt))
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun write(records: Map<String, ManualDeadlinePromptPolicy.Record>) {
        val root = JSONObject()
        records.forEach { (k, record) ->
            root.put(k, JSONObject()
                .put("endTime", record.endTime)
                .put("nextPromptAt", record.nextPromptAt))
        }
        prefs.edit().putString(KEY_RECORDS, root.toString()).apply()
    }
}
