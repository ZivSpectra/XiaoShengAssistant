package com.zhizhou.xxtassistant.util

import android.content.Context
import java.io.File

/**
 * 联调日志：把抓取过程中的关键信息写进手机本地文件，
 * 方便"真机跑不通时"排查问题（页面结构变了、接口被拦截等）。
 *
 * 【安全红线】这个日志类绝不记录 Cookie / 密码 / 任何账号敏感信息，
 * 只记录请求的网址、返回状态、页面片段等诊断信息。
 */
object DebugLog {

    private const val LOG_FILE = "debug_log.txt"

    /** 联调日志：多线程抓取时也要保证一行一行完整写入 */
    @Synchronized
    fun log(context: Context, message: String) {
        // 通过应用标志判断构建类型，避免 Release 产物生成 BuildConfig 依赖。
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        try {
            val file = File(context.filesDir, LOG_FILE)
            val line = "[${System.currentTimeMillis()}] $message\n"
            file.appendText(line)
        } catch (_: Exception) {
            // 日志写失败不影响主功能
        }
    }

    /** 截断一段文本（防止日志文件被页面片段撑爆） */
    fun trim(text: String, maxLen: Int = 1500): String =
        if (text.length <= maxLen) text else text.substring(0, maxLen) + "…(截断)"
}
