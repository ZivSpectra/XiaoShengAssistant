package com.zhizhou.xxtassistant.util

import java.net.URI
import java.util.Locale

/** Validates the account identity carried by one or more platform cookie jars. */
internal object CookieIdentity {
    private val uidPattern = Regex("(?:^|;\\s*)_uid=([^;\\s]*)")
    private val allowedHosts = setOf(
        "passport2.chaoxing.com", "passport.chaoxing.com", "i.chaoxing.com",
        "mooc1.chaoxing.com", "mooc2.chaoxing.com", "mooc2-ans.chaoxing.com",
        "mooc1-api.chaoxing.com"
    )
    fun singleUid(cookieValues: Iterable<String>): String? {
        val rawIds = cookieValues.flatMap { value -> uidPattern.findAll(value).map { it.groupValues[1] } }
            .filter { it.isNotBlank() }
            .toSet()
        if ("0" in rawIds && rawIds.any { it != "0" }) return null
        val ids = rawIds.filter { it != "0" }.toSet()
        return ids.singleOrNull()
    }

    fun matchesUid(cookieValues: Iterable<String>, expectedUid: String): Boolean =
        expectedUid.isNotBlank() && singleUid(cookieValues) == expectedUid

    /**
     * Domain jars may contain ordinary session cookies without `_uid`, but a
     * jar must never contain a different account or a conflicting pair of
     * account identifiers. `_uid=0` is treated as invalid rather than as an
     * absent identifier.
     */
    fun belongsToUidOrHasNoUid(cookieValues: Iterable<String>, expectedUid: String): Boolean {
        if (expectedUid.isBlank()) return false
        val rawIds = cookieValues.flatMap { value ->
            uidPattern.findAll(value).map { it.groupValues[1] }
        }.filter { it.isNotBlank() }
            .toSet()
        if (rawIds.any { it == "0" }) return false
        return rawIds.isEmpty() || rawIds == setOf(expectedUid)
    }

    fun normalizeAllowedHost(value: String): String? = runCatching {
        val trimmed = value.trim()
        val uri = if (trimmed.contains("://")) URI(trimmed) else URI("https://$trimmed")
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val secureScheme = uri.scheme == null || uri.scheme.equals("https", ignoreCase = true)
        host.takeIf {
            secureScheme && uri.rawUserInfo.isNullOrEmpty() &&
                (uri.port == -1 || uri.port == 443) && it in allowedHosts
        }
    }.getOrNull()

    /** Return only the exact host jar; never broaden cookie scope by suffix. */
    fun cookieForExactHost(host: String, cookiesByHost: Map<String, String>): String? {
        val allowedHost = normalizeAllowedHost(host) ?: return null
        return cookiesByHost[allowedHost]?.takeIf { it.isNotBlank() }
    }
}
