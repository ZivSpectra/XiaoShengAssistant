package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 记录自动作业的本地完成标记。它只控制小笙列表和提醒，不会向学习通提交任何内容。
 */
class LocalCompletionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_local_completion", Context.MODE_PRIVATE)

    fun isCompleted(accountId: String, homework: Homework): Boolean =
        read(accountId).contains(key(homework))

    fun setCompleted(accountId: String, homework: Homework, completed: Boolean) {
        val values = read(accountId).toMutableSet()
        if (completed) values += key(homework) else values -= key(homework)
        prefs.edit().putStringSet(accountId, values).apply()
    }

    private fun key(homework: Homework): String = "${homework.courseKey}:${homework.id}"

    private fun read(accountId: String): Set<String> =
        prefs.getStringSet(accountId, emptySet())?.toSet() ?: emptySet()
}
