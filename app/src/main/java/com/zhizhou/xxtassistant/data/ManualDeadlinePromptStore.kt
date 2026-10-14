package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences

/** 记录手动作业过期确认，避免用户每次打开应用都被重复询问。 */
class ManualDeadlinePromptStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_manual_deadline_prompts", Context.MODE_PRIVATE)

    fun wasPrompted(accountId: String, homeworkId: String, endTime: Long): Boolean =
        prefs.getStringSet(accountId, emptySet())?.contains(key(homeworkId, endTime)) == true

    fun markPrompted(accountId: String, homeworkId: String, endTime: Long) {
        val values = prefs.getStringSet(accountId, emptySet())?.toMutableSet() ?: mutableSetOf()
        values += key(homeworkId, endTime)
        prefs.edit().putStringSet(accountId, values).apply()
    }

    private fun key(homeworkId: String, endTime: Long): String = "$homeworkId:$endTime"
}
