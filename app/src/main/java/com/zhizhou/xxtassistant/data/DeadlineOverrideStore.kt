package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 保存用户对自动作业截止时间的覆盖，并控制官方时间提示频率。 */
class DeadlineOverrideStore(context: Context) {

    data class Prompt(
        val homework: Homework,
        val officialEndTime: Long,
        val customEndTime: Long
    )

    private data class Record(
        val overrideEndTime: Long,
        val lastPromptOfficial: Long,
        val lastPromptAt: Long
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_deadline_overrides", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MAP = "records"
    }

    /** 读取列表时应用用户覆盖；原始官方时间仍保存在 officialEndTime。 */
    fun apply(homework: Homework): Homework {
        val records = read()
        val record = records[key(homework)] ?: records[legacyKey(homework)] ?: return homework
        return homework.copy(
            endTime = record.overrideEndTime,
            officialEndTime = homework.endTime.takeIf { it > 0L } ?: homework.officialEndTime,
            deadlineOverridden = true
        )
    }

    /** 处理一次新抓到的官方时间。返回提示时已写入冷却记录，避免刷新反复弹窗。 */
    fun observe(homework: Homework): Prompt? {
        if (homework.isManual() || homework.endTime <= 0L) return null
        val all = read().toMutableMap()
        val k = key(homework)
        val old = all[k] ?: all[legacyKey(homework)] ?: return null
        val shouldPrompt = old.overrideEndTime != homework.endTime &&
            old.lastPromptOfficial != homework.endTime
        if (!shouldPrompt) return null
        all[k] = old.copy(
            lastPromptOfficial = homework.endTime,
            lastPromptAt = System.currentTimeMillis()
        )
        write(all)
        return Prompt(homework, homework.endTime, old.overrideEndTime)
    }

    fun setOverride(homework: Homework, overrideEndTime: Long) {
        require(overrideEndTime > 0L)
        val all = read().toMutableMap()
        val old = all[key(homework)] ?: all[legacyKey(homework)]
        all[key(homework)] = Record(
            overrideEndTime = overrideEndTime,
            lastPromptOfficial = old?.lastPromptOfficial ?: 0L,
            lastPromptAt = old?.lastPromptAt ?: 0L
        )
        write(all)
    }

    fun clearOverride(homework: Homework) {
        val all = read().toMutableMap()
        all.remove(key(homework))
        all.remove(legacyKey(homework))
        write(all)
    }

    /** 用户选择继续使用自定义时间；相同官方时间不再提示。 */
    fun keepCustom(homework: Homework) {
        val all = read().toMutableMap()
        val old = all[key(homework)] ?: all[legacyKey(homework)] ?: return
        all[key(homework)] = old.copy(
            lastPromptOfficial = homework.officialEndTime ?: homework.endTime,
            lastPromptAt = System.currentTimeMillis()
        )
        write(all)
    }

    private fun key(homework: Homework): String =
        "${homework.accountId}:${homework.courseKey}:${homework.id}"

    private fun legacyKey(homework: Homework): String = "${homework.accountId}:${homework.id}"

    private fun read(): Map<String, Record> {
        val raw = prefs.getString(KEY_MAP, "{}") ?: "{}"
        return try {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { k ->
                    val o = root.optJSONObject(k) ?: return@forEach
                    val override = o.optLong("overrideEndTime")
                    if (override > 0L) put(k, Record(
                        override,
                        o.optLong("lastPromptOfficial"),
                        o.optLong("lastPromptAt")
                    ))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun write(records: Map<String, Record>) {
        val root = JSONObject()
        records.forEach { (k, r) ->
            root.put(k, JSONObject()
                .put("overrideEndTime", r.overrideEndTime)
                .put("lastPromptOfficial", r.lastPromptOfficial)
                .put("lastPromptAt", r.lastPromptAt))
        }
        prefs.edit().putString(KEY_MAP, root.toString()).apply()
    }
}
