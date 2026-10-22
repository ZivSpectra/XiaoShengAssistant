package com.zhizhou.xxtassistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Extracts a learner name only from explicit account/profile objects, never course names. */
object AccountNameParser {
    private val profileKeys = setOf("userinfo", "user", "person", "logininfo", "studentinfo", "profile")
    private val nameKeys = setOf("realname", "name", "displayname", "nickname", "username")
    private val idKeys = setOf("uid", "userid", "personid", "studentid")

    @JvmStatic
    fun parse(raw: String, expectedAccountId: String): String? {
        // The course endpoint is JSON. Parse profile fields structurally instead
        // of regex-scanning the full response; malformed optional profile data
        // must never stop course or homework loading.
        runCatching { findProfileName(JSONObject(raw), expectedAccountId) }
            .getOrNull()
            ?.let { return it }

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

    private fun findProfileName(node: Any?, expectedAccountId: String): String? {
        if (node is JSONObject) {
            val keys = node.keys().asSequence().toList()
            keys.firstOrNull { it.lowercase(Locale.ROOT) in profileKeys }?.let { key ->
                val profile = node.optJSONObject(key)
                if (profile != null) {
                    val value = extractName(profile, expectedAccountId)
                    if (value != null) return value
                }
            }
            for (key in keys) {
                val value = findProfileName(node.opt(key), expectedAccountId)
                if (value != null) return value
            }
            return null
        }
        if (node is JSONArray) {
            for (index in 0 until node.length()) {
                val value = findProfileName(node.opt(index), expectedAccountId)
                if (value != null) return value
            }
        }
        return null
    }

    private fun extractName(profile: JSONObject, expectedAccountId: String): String? {
        val idKey = profile.keys().asSequence().firstOrNull { it.lowercase(Locale.ROOT) in idKeys }
        if (idKey != null && profile.optString(idKey) != expectedAccountId) return null
        val nameKey = profile.keys().asSequence().firstOrNull { it.lowercase(Locale.ROOT) in nameKeys } ?: return null
        return profile.optString(nameKey).trim().takeIf(::isPlausibleName)
    }

    private fun isPlausibleName(value: String): Boolean =
        value.length in 2..40 && value.any { it.isLetter() } && value.none { it == '\n' || it == '\r' }
}
