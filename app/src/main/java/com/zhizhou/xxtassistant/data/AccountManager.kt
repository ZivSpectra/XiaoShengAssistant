package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 账号管理器：只保持当前登录凭证；本地业务数据仍按 uid 隔离，换号后可恢复。
 *
 * 【安全设计 - 最重要的一条】
 * 所有账号的 Cookie 只保存在手机 App 自己的私有存储里（其他 App 读不到），
 * 永远不会上传到任何服务器。本项目没有任何一行"把数据发出去"的代码——
 * 网络请求只发给学习通官方域名（*.chaoxing.com），只读作业数据，绝不自动提交。
 *
 * 存储结构：一个 JSON 对象  { "账号ID(uid)": "该账号的Cookie", ... }
 */
class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_accounts", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ACTIVE_UID = "active_uid"
        // 登录成功标志与账号 ID 提取：Cookie 里的 _uid=数字
        private const val LOGIN_FLAG = "_uid="
    }

    /** 登录成功后保存当前账号；同一个账号重复登录会覆盖旧 Cookie。 */
    fun addAccount(cookie: String): String? {
        val uid = extractUid(cookie) ?: return null
        val json = getAccountsJson()
        json.put(uid, cookie)
        prefs.edit()
            .putString(KEY_ACCOUNTS, json.toString())
            .putString(KEY_ACTIVE_UID, uid)
            .apply()
        return uid
    }

    /** 取出所有账号：Map<账号ID, Cookie> */
    fun getAccounts(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val json = getAccountsJson()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key)
        }
        return result
    }

    /** 读取指定账号的 Cookie，供 WebView 在切换账号时恢复会话。 */
    fun getCookie(uid: String): String? = getAccounts()[uid]

    /**
     * 将验证页/WebView 最新拿到的 Cookie 合并回已有账号。
     * 验证页不一定会重新下发 _uid，因此不能调用 addAccount 直接替换账号。
     */
    fun updateCookie(uid: String, cookie: String): Boolean {
        if (uid.isBlank() || cookie.isBlank()) return false
        val json = getAccountsJson()
        val old = json.optString(uid, "")
        val merged = mergeCookies(old, cookie)
        // 验证页不能把另一个账号的 Cookie 写入当前账号，也不能用没有 uid 的
        // 空 Cookie 伪装成“验证完成”。
        if (merged.isBlank() || extractUid(merged) != uid) return false
        json.put(uid, merged)
        prefs.edit().putString(KEY_ACCOUNTS, json.toString()).putString(KEY_ACTIVE_UID, uid).apply()
        return true
    }

    /** 当前登录账号。退出登录后为空，但历史本地作业数据不受影响。 */
    fun getActiveUid(): String? = prefs.getString(KEY_ACTIVE_UID, null)

    fun hasActiveSession(): Boolean = getActiveUid()?.let { getCookie(it) != null } == true

    /** 退出当前账号：清除所有保存的登录凭证，不触碰按 uid 保存的本地业务数据。 */
    fun logout() {
        prefs.edit()
            .remove(KEY_ACCOUNTS)
            .remove(KEY_ACTIVE_UID)
            .apply()
    }

    /** 删除某个账号 */
    fun removeAccount(uid: String) {
        val json = getAccountsJson()
        json.remove(uid)
        val edit = prefs.edit().putString(KEY_ACCOUNTS, json.toString())
        if (getActiveUid() == uid) edit.remove(KEY_ACTIVE_UID)
        edit.apply()
    }

    /** 是否一个账号都没有 */
    fun isEmpty(): Boolean = !hasActiveSession()

    /** 从 Cookie 文本里提取账号 ID（_uid=后面的数字） */
    private fun extractUid(cookie: String): String? {
        val idx = cookie.indexOf(LOGIN_FLAG)
        if (idx < 0) return null
        val start = idx + LOGIN_FLAG.length
        val end = cookie.indexOf(';', start).let { if (it < 0) cookie.length else it }
        val uid = cookie.substring(start, end).trim()
        return if (uid.isNotEmpty() && uid != "0") uid else null
    }

    private fun getAccountsJson(): JSONObject {
        val raw = prefs.getString(KEY_ACCOUNTS, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun mergeCookies(vararg sources: String): String {
        val values = LinkedHashMap<String, String>()
        sources.flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { item ->
                val index = item.indexOf('=')
                val name = item.substring(0, index).trim()
                val value = item.substring(index + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) values[name] = value
            }
        return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }
}
