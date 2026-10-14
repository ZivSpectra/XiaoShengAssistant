package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 会话管理器：负责保存 / 读取 / 清除学习通登录 Cookie。
 *
 * Cookie 就是"登录凭证小纸条"——你登录成功后，服务器发给你一张纸条，
 * 之后每次请求带上它，服务器就知道"这是同一个已登录的人"。
 * 后续所有请求学习通作业接口的代码，都要从这个类里拿 Cookie 带上。
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_COOKIE = "cookie"

        // 登录成功的判断标志：学习通登录后，Cookie 里一定会有 "_uid=数字ID"
        // （相当于服务器发的小纸条上写着"我是第几号用户"）
        private const val LOGIN_FLAG = "_uid="
    }

    /** 登录成功后，把收集到的 Cookie 存起来（存进 App 私有存储，别处读不到） */
    fun saveCookie(cookie: String) {
        prefs.edit().putString(KEY_COOKIE, cookie).apply()
    }

    /** 取出之前存好的 Cookie */
    fun getCookie(): String = prefs.getString(KEY_COOKIE, "") ?: ""

    /**
     * 判断一段 Cookie 是否"已登录"。
     * 规则：找到 "_uid=" 这个标志，如果它后面跟的数字不为空也不为 0，就说明登录成功。
     */
    fun isLoggedIn(cookie: String = getCookie()): Boolean {
        val idx = cookie.indexOf(LOGIN_FLAG)
        if (idx < 0) return false
        val valueStart = idx + LOGIN_FLAG.length
        val valueEnd = cookie.indexOf(';', valueStart).let { if (it < 0) cookie.length else it }
        val uid = cookie.substring(valueStart, valueEnd).trim()
        return uid.isNotEmpty() && uid != "0"
    }

    /** 退出登录：清空保存的 Cookie */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
