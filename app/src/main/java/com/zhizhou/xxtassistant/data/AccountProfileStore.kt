package com.zhizhou.xxtassistant.data

import android.content.Context

/** Stores only the display name returned by the learning platform, never credentials. */
class AccountProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xxt_account_profiles", Context.MODE_PRIVATE)

    fun displayName(accountId: String): String? = prefs.getString(key(accountId), null)?.trim()?.takeIf { it.isNotBlank() }

    fun saveDisplayName(accountId: String, name: String) {
        val clean = name.trim().take(80)
        if (clean.isNotBlank() && !isGeneric(clean)) prefs.edit().putString(key(accountId), clean).apply()
    }

    private fun key(accountId: String): String = accountId.take(80)

    private fun isGeneric(value: String): Boolean = value in setOf("学习通", "超星学习通", "登录", "我的课程")
}
