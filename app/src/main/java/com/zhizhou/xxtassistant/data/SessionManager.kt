package com.zhizhou.xxtassistant.data

import android.annotation.SuppressLint
import android.content.Context
import com.zhizhou.xxtassistant.util.CookieIdentity

/**
 * 会话管理器：负责保存 / 读取 / 清除学习通登录 Cookie。
 *
 * Cookie 就是"登录凭证小纸条"——你登录成功后，服务器发给你一张纸条，
 * 之后每次请求带上它，服务器就知道"这是同一个已登录的人"。
 * 后续所有请求学习通作业接口的代码，都要从这个类里拿 Cookie 带上。
 */
@SuppressLint("ApplySharedPref")
class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences("xxt_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_COOKIE = "cookie"
    }

    /** Compatibility API; new writes are routed through the encrypted account store. */
    @Deprecated("Use AccountManager.addAccountByDomain to preserve cookie host scope")
    fun saveCookie(cookie: String) {
        require(CookieIdentity.singleUid(listOf(cookie)) != null) { "A valid account UID is required" }
        check(AccountManager(appContext).addAccount(cookie) != null) {
            "Could not securely store session"
        }
    }

    /** Reads the active session from the encrypted account store. */
    fun getCookie(): String {
        val accounts = AccountManager(appContext)
        return accounts.getActiveUid()?.let { accounts.getCookie(it) }.orEmpty()
    }

    /**
     * 判断一段 Cookie 是否"已登录"。
     * 规则：找到 "_uid=" 这个标志，如果它后面跟的数字不为空也不为 0，就说明登录成功。
     */
    fun isLoggedIn(cookie: String = getCookie()): Boolean =
        CookieIdentity.singleUid(listOf(cookie)) != null

    /** Clears the encrypted account session and any remaining legacy plaintext. */
    fun clear() {
        AccountManager(appContext).logout()
        legacyPrefs.edit().remove(KEY_COOKIE).commit()
    }
}
