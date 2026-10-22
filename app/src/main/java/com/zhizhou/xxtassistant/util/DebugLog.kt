package com.zhizhou.xxtassistant.util

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 联调日志：把抓取过程中的关键信息写进手机本地文件，
 * 方便"真机跑不通时"排查问题（页面结构变了、接口被拦截等）。
 *
 * 【安全红线】这个日志类绝不记录 Cookie / 密码 / 账号标识或可复用会话参数。
 * 页面诊断只记录长度和结构特征，不保存 HTML、姓名、学号或完整 URL 查询参数。
 */
object DebugLog {

    private const val LOG_FILE = "debug_log.txt"
    private const val ROTATED_LOG_FILE = "debug_log.previous.txt"
    /** Keep diagnostics bounded so a long-running debug build cannot fill storage. */
    private const val MAX_LOG_BYTES = 256 * 1024L
    private const val MAX_LINE_CHARS = 2048
    private const val MAX_READ_CHARS = 64 * 1024
    private const val MAX_READ_BYTES = 64 * 1024L

    /** 联调日志：多线程抓取时也要保证一行一行完整写入 */
    @Synchronized
    fun log(context: Context, message: String) {
        // 通过应用标志判断构建类型，避免 Release 产物生成 BuildConfig 依赖。
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        try {
            val file = File(context.filesDir, LOG_FILE)
            val line = "[${System.currentTimeMillis()}] ${boundedLine(message)}\n"
            appendLine(file, line, MAX_LOG_BYTES)
        } catch (_: Exception) {
            // 日志写失败不影响主功能
        }
    }

    /** Read only a bounded tail for the in-app diagnostics dialog. */
    fun read(context: Context): String {
        val directory = context.filesDir
        val rotated = readTail(File(directory, ROTATED_LOG_FILE), MAX_READ_BYTES)
        val current = readTail(File(directory, LOG_FILE), MAX_READ_BYTES)
        return (rotated + current).takeLast(MAX_READ_CHARS)
    }

    /** Re-sanitize older log lines before they are copied or shared. */
    fun readForCopy(context: Context): String = sanitizeForCopy(read(context))

    private fun sanitizeForCopy(logs: String): String = logs
        .lineSequence()
        .joinToString("\n") { boundedLine(it) }

    /** 返回不包含正文和查询参数的页面结构摘要。 */
    fun pageInfo(text: String): String {
        if (text.isBlank()) return "页面为空"
        val lower = text.lowercase()
        val markers = buildList {
            if (lower.contains("<li")) add("li")
            if (lower.contains("workid") || lower.contains("taskrefid")) add("work-id")
            if (lower.contains("/mooc2/work") || lower.contains("task-list")) add("work-list")
            if (lower.trimStart().startsWith("{") || lower.trimStart().startsWith("[")) add("json")
            if (lower.contains("作答时间") || lower.contains("deadline") || lower.contains("endtime")) add("deadline")
            if (lower.contains("暂无作业") || lower.contains("暂无任务")) add("empty")
            if (lower.contains("登录") || lower.contains("login")) add("login")
            if (lower.contains("processverify") || lower.contains("yzmtips")) add("verify")
        }
        return "页面长度=${text.length}，结构=${markers.ifEmpty { listOf("unknown") }.joinToString(",")}"
    }

    /** 只保留 URL 的协议、域名和路径，查询参数可能含会话令牌，不能写日志。 */
    fun safeUrl(url: String): String =
        url.substringBefore('?').substringBefore('#').takeIf { it.isNotBlank() } ?: "<url>"

    private fun sanitize(message: String): String {
        val withoutUrls = Regex("https?://\\S+").replace(message) { safeUrl(it.value) }
        return Regex("(?i)(cookie|authorization|token|enc|cpi|uid|password|passwd|session|jsessionid|account|username|courseid|classid|clazzid|workid|taskrefid|studentid|userid|phone|mobile)\\s*[=:]\\s*[^,;\\s]+")
            .replace(withoutUrls) { match ->
                val raw = match.value
                val separator = if (raw.contains('=')) "=" else ":"
                raw.substringBefore(separator).trimEnd() + separator + "<redacted>"
            }
    }

    private fun boundedLine(message: String): String = sanitize(message).take(MAX_LINE_CHARS)

    private fun appendLine(file: File, line: String, maxBytes: Long) {
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8)
        if (lineBytes.size > maxBytes) return
        if (file.exists() && file.length() + lineBytes.size > maxBytes) {
            val backup = File(file.parentFile, ROTATED_LOG_FILE)
            backup.delete()
            if (file.length() <= maxBytes) {
                // Keep one previous segment for support diagnostics.
                if (!file.renameTo(backup)) file.writeBytes(byteArrayOf())
            } else {
                // An oversized legacy log must not become an oversized backup.
                if (!file.delete()) file.writeBytes(byteArrayOf())
            }
        }
        file.appendBytes(lineBytes)
    }

    private fun readTail(file: File, maxBytes: Long): String {
        if (!file.isFile || maxBytes <= 0L) return ""
        return try {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                val start = (length - maxBytes).coerceAtLeast(0L)
                input.seek(start)
                val bytes = ByteArray((length - start).toInt())
                input.readFully(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** Pure helper for JVM tests; production code should call [log]. */
    internal fun sanitizeForTest(message: String): String = sanitize(message)

    /** Pure helper for JVM tests; mirrors the production per-line limit. */
    internal fun boundedLineForTest(message: String): String = boundedLine(message)

    internal fun sanitizeForCopyForTest(logs: String): String = sanitizeForCopy(logs)

    /** Pure helpers for JVM tests. */
    internal fun appendLineForTest(file: File, line: String, maxBytes: Long) =
        appendLine(file, line, maxBytes)

    internal fun readTailForTest(file: File, maxBytes: Long): String =
        readTail(file, maxBytes)
}
