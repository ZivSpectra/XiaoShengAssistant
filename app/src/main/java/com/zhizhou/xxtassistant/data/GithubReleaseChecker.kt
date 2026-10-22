package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small, optional GitHub release checker. It never sends account data and it
 * is deliberately independent of the learning-platform network code.
 */
object GithubReleaseChecker {
    const val repositoryUrl = "https://github.com/ZivSpectra/XiaoShengAssistant"
    private const val releasesApi = "https://api.github.com/repos/ZivSpectra/XiaoShengAssistant/releases/latest"
    private const val preferenceFile = "xxt_update_check"
    private const val lastCheckKey = "last_check_at"
    private const val lastReleaseKey = "last_release"
    private const val checkCooldownMs = 6L * 60L * 60L * 1000L
    private const val maxResponseBytes = 512 * 1024

    data class Release(
        val tag: String,
        val name: String,
        val htmlUrl: String,
        val publishedAt: String?
    )

    data class Result(
        val release: Release?,
        val isNewer: Boolean,
        val fromCache: Boolean,
        val coolingDown: Boolean = false,
        val error: String? = null
    )

    fun currentVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun checkAsync(
        context: Context,
        force: Boolean = false,
        callback: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = parseRelease(prefs.getString(lastReleaseKey, null))
        if (!force && now - prefs.getLong(lastCheckKey, 0L) in 0 until checkCooldownMs) {
            callback(
                Result(
                    cached,
                    cached?.let { isNewerThan(currentVersion(appContext), it.tag) } == true,
                    fromCache = cached != null,
                    coolingDown = true
                )
            )
            return
        }
        EXECUTOR.execute {
            val result = runCatching { fetch(appContext) }.fold(
                onSuccess = { release ->
                    prefs.edit()
                        .putLong(lastCheckKey, System.currentTimeMillis())
                        .putString(lastReleaseKey, releaseToJson(release))
                        .apply()
                    Result(release, isNewerThan(currentVersion(appContext), release.tag), fromCache = false)
                },
                onFailure = { error ->
                    prefs.edit().putLong(lastCheckKey, System.currentTimeMillis()).apply()
                    // A stale successful response is still more useful than a
                    // transient network error, but never claim it is current.
                    Result(
                        cached,
                        cached?.let { isNewerThan(currentVersion(appContext), it.tag) } == true,
                        fromCache = cached != null,
                        coolingDown = false,
                        error = error.message ?: "无法连接 GitHub"
                    )
                }
            )
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }
    }

    /** Parses GitHub's release tag without accepting arbitrary non-version text. */
    fun isNewerThan(current: String, candidate: String): Boolean =
        compareVersions(normalizeVersion(candidate), normalizeVersion(current)) > 0

    internal fun parseReleaseJson(json: String): Release? = runCatching {
        val objectValue = JSONObject(json)
        if (objectValue.optBoolean("draft", false) || objectValue.optBoolean("prerelease", false)) return null
        val tag = objectValue.optString("tag_name").trim().takeIf { it.isNotBlank() } ?: return null
        val url = objectValue.optString("html_url").trim().takeIf { it.startsWith("https://github.com/", ignoreCase = true) }
            ?: return null
        Release(
            tag = tag,
            name = objectValue.optString("name").trim(),
            htmlUrl = url,
            publishedAt = objectValue.optString("published_at").trim().takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun fetch(context: Context): Release {
        val connection = (URL(releasesApi).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "XiaoShengAssistant/${currentVersion(context).ifBlank { "unknown" }}")
        }
        return try {
            val response = connection
            if (response.responseCode !in 200..299) error("GitHub 返回 HTTP ${response.responseCode}")
            val bytes = response.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxResponseBytes) error("GitHub 响应过大")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            parseReleaseJson(bytes.toString(Charsets.UTF_8)) ?: error("GitHub 返回的版本信息无效")
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeVersion(value: String): List<Int> {
        val match = Regex("(?:^|[^0-9])v?(\\d+(?:\\.\\d+){0,3})(?:[^0-9]|$)", RegexOption.IGNORE_CASE)
            .find(value.trim()) ?: return emptyList()
        return match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }.orEmpty()
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        if (left.isEmpty() || right.isEmpty()) return 0
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun parseRelease(raw: String?): Release? = raw?.let(::parseReleaseJson)

    private fun releaseToJson(release: Release): String = JSONObject()
        .put("tag_name", release.tag)
        .put("name", release.name)
        .put("html_url", release.htmlUrl)
        .put("published_at", release.publishedAt)
        .toString()

    private val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xxt-github-release-check").apply { isDaemon = true }
    }
}
