package com.zhizhou.xxtassistant.data

import android.content.Context

/** 控制当前账号的未知截止时间汇总提示频率，默认每 24 小时最多提示一次。 */
class UnknownDeadlinePromptStore(context: Context) {

    private val prefs = context.getSharedPreferences("xxt_unknown_deadline", Context.MODE_PRIVATE)

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }

    fun shouldPrompt(accountId: String, now: Long = System.currentTimeMillis()): Boolean {
        return prefs.getLong(key(accountId), 0L) <= now
    }

    fun snooze(accountId: String, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(key(accountId), now + DAY_MS).apply()
    }

    private fun key(accountId: String): String = "account:$accountId"
}
