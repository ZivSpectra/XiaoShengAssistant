package com.zhizhou.xxtassistant.util

import java.net.URI
import java.util.Locale

/** Converts persisted cookie host keys into URLs accepted by CookieManager.setCookie. */
internal object WebViewCookieTarget {
    /** Returns true only for an absolute HTTP(S) URL with a real host. */
    fun isHttpUrl(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        (scheme == "http" || scheme == "https") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo.isNullOrEmpty()
    }.getOrDefault(false)

    /** User-entered academic pages and their top-level redirects must use TLS. */
    fun isHttpsUrl(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo.isNullOrEmpty()
    }.getOrDefault(false)

    /**
     * Normalizes the value typed into the academic-system address bar.
     * Users commonly paste a bare host such as `portal.example.edu`; treating
     * that as a relative WebView URL makes the page appear to do nothing.
     * Bare hosts are upgraded to HTTPS, while explicit HTTP remains rejected
     * by the caller so credentials are never silently sent in clear text.
     */
    fun normalizeAcademicUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return candidate.takeIf(::isHttpsUrl)
    }

    fun isSafeAcademicTopLevelUrl(url: String): Boolean =
        url.equals("about:blank", ignoreCase = true) || isHttpsUrl(url)

    /**
     * Top-level pages opened by the learning-platform WebViews must remain on
     * the HTTPS Chaoxing origin set. Subresources are still handled by WebView
     * and are intentionally not passed through this guard.
     */
    fun isSecureLearningPlatformUrl(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            isLearningPlatformHost(uri.host.orEmpty())
    }.getOrDefault(false)

    fun fromDomain(domain: String): String {
        val value = domain.trim().trimEnd('/')
        return if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)) {
            value
        } else {
            "https://$value/"
        }
    }

    /** Returns the exact origin, never a guessed registrable-domain suffix. */
    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" }
            ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            ?: return null
        val port = uri.port.takeIf { it >= 0 && it != defaultPort(scheme) }
        "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()

    fun isLearningPlatformHost(host: String): Boolean {
        val normalized = host.trim('.').lowercase(Locale.ROOT)
        return normalized == "chaoxing.com" || normalized.endsWith(".chaoxing.com")
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
}
