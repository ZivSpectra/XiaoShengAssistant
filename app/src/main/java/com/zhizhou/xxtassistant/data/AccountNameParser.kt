package com.zhizhou.xxtassistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Extracts a learner name only from explicit account/profile objects, never course names. */
object AccountNameParser {
    private val profileKeys = setOf("userinfo", "user", "person", "logininfo", "studentinfo", "profile")
    private val nameKeys = setOf("realname", "name", "displayname", "nickname", "username")
    private val idKeys = setOf("uid", "userid", "personid", "studentid")

    /** Visible profile text is accepted only when a name label is explicit. */
    @JvmStatic
    fun parseVisibleProfile(text: String): String? {
        // WebView pages often wrap the label and value in separate spans. Strip
        // tags first so the same explicit-label rule works for text and markup.
        val normalized = text.replace(Regex("<[^>]+>"), "")
        val match = Regex("(?m)(?:^|\\n)\\s*(?:真实姓名|姓名|昵称)\\s*[:：]\\s*([^\\r\\n|｜]+)")
            .find(normalized) ?: return null
        return match.groupValues[1].trim().takeIf(::isPlausibleName)
    }

    @JvmStatic
    fun parse(raw: String, expectedAccountId: String): String? {
        // The course endpoint is JSON. Parse profile fields structurally instead
        // of regex-scanning the full response; malformed optional profile data
        // must never stop course or homework loading.
        runCatching { findProfileName(JSONObject(raw), expectedAccountId, allowUnscoped = true) }
            .getOrNull()
            ?.let { return it }

        // A few course responses put uid/realname beside channelList rather
        // than under userInfo. Keep this fallback narrow: require the same
        // expected uid in the surrounding JSON object before accepting it.
        val rootName = runCatching {
            val root = Regex(
                "(?is)\\{[^{}]*\\\"(?:uid|userId|personId|studentId)\\\"\\s*:\\s*\\\"?"
                    + Regex.escape(expectedAccountId)
                    + "\\\"?[^{}]*\\\"(?:realName|realname|displayName|nickname)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
            )
            root.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf(::isPlausibleName)
        }.getOrNull()
        if (rootName != null) return rootName

        // Compatibility for profile JSON embedded in an HTML wrapper and for
        // local JVM tests that do not provide Android's org.json implementation.
        return runCatching {
            val profile = Regex("""(?is)\"(?:userInfo|user|person|loginInfo|studentInfo|profile)\"\s*:\s*\{([^{}]*)}""")
                .find(raw)?.groupValues?.getOrNull(1) ?: return@runCatching null
            val id = Regex("""(?i)\"(?:uid|userId|personId|studentId)\"\s*:\s*\"?([^,\"}]*)""")
                .find(profile)?.groupValues?.getOrNull(1)?.trim()
            if (id != null && id != expectedAccountId) return@runCatching null
            val name = Regex("""(?i)\"(?:realName|name|displayName|nickname|username)\"\s*:\s*\"([^\"]+)\"""")
                .find(profile)?.groupValues?.getOrNull(1)?.trim()
            name?.takeIf(::isPlausibleName)
        }.getOrNull()
    }

    @JvmStatic
    fun parseAuthenticatedPage(raw: String, expectedAccountId: String): String? =
        parse(raw, expectedAccountId) ?: parseVisibleProfile(raw)

    private fun findProfileName(node: Any?, expectedAccountId: String, allowUnscoped: Boolean = false): String? {
        if (node is JSONObject) {
            val keys = node.keys().asSequence().toList()
            keys.firstOrNull { it.lowercase(Locale.ROOT) in profileKeys }?.let { key ->
                val profile = node.optJSONObject(key)
                if (profile != null) {
                    val value = extractName(profile, expectedAccountId, allowUnscoped = true)
                    if (value != null) return value
                }
            }
            // Some authenticated course responses expose the learner directly
            // at the root (uid + realname), without a userInfo object.
            extractName(node, expectedAccountId, allowUnscoped, rootOnly = true)?.let { return it }
            for (key in keys) {
                val value = findProfileName(node.opt(key), expectedAccountId, allowUnscoped = false)
                if (value != null) return value
            }
            return null
        }
        if (node is JSONArray) {
            for (index in 0 until node.length()) {
                val value = findProfileName(node.opt(index), expectedAccountId, allowUnscoped = false)
                if (value != null) return value
            }
        }
        return null
    }

    private fun extractName(
        profile: JSONObject,
        expectedAccountId: String,
        allowUnscoped: Boolean,
        rootOnly: Boolean = false
    ): String? {
        val idKey = profile.keys().asSequence().firstOrNull { it.lowercase(Locale.ROOT) in idKeys }
        if (idKey != null && profile.optString(idKey) != expectedAccountId) return null
        if (idKey == null && !allowUnscoped) return null
        val allowedNames = if (rootOnly) nameKeys - "name" else nameKeys
        val nameKey = profile.keys().asSequence().firstOrNull { it.lowercase(Locale.ROOT) in allowedNames } ?: return null
        return profile.optString(nameKey).trim().takeIf(::isPlausibleName)
    }

    private fun isPlausibleName(value: String): Boolean =
        value.length in 2..40 && value.any { it.isLetter() } && value.none { it == '\n' || it == '\r' }
}
