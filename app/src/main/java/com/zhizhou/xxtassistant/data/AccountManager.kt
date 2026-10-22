package com.zhizhou.xxtassistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import com.zhizhou.xxtassistant.util.CookieIdentity
import com.zhizhou.xxtassistant.util.CredentialVault

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
/** Synchronous commits below are intentional while deleting/migrating credentials. */
@SuppressLint("ApplySharedPref")
class AccountManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("xxt_accounts", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_DOMAIN_COOKIES = "domain_cookies_json"
        private const val KEY_ACTIVE_UID = "active_uid"
    }

    init {
        migrateLegacyCookiesIfNeeded()
    }

    /** 登录成功后保存当前账号；同一个账号重复登录会覆盖旧 Cookie。 */
    fun addAccount(cookie: String): String? {
        val uid = CookieIdentity.singleUid(listOf(cookie)) ?: return null
        val json = getAccountsJson()
        json.put(uid, cookie)
        val stored = encryptValues(json) ?: return null
        prefs.edit()
            .putString(KEY_ACCOUNTS, stored.toString())
            .putString(KEY_ACTIVE_UID, uid)
            .apply()
        return uid
    }

    /**
     * Saves a login session without flattening cookies from unrelated hosts.
     * The encrypted flat value remains for account identity and compatibility;
     * HTTP requests use only the exact host-specific cookie jar.
     */
    fun addAccountByDomain(cookiesByDomain: Map<String, String>): String? {
        val allowedCookies = cookiesByDomain.mapNotNull { (domain, cookie) ->
            val host = CookieIdentity.normalizeAllowedHost(domain) ?: return@mapNotNull null
            host to cookie
        }.toMap()
        val uid = CookieIdentity.singleUid(allowedCookies.values) ?: return null
        val merged = allowedCookies.values.joinToString("; ")
        val accounts = getAccountsJson()
        val oldCookie = accounts.optString(uid, "")
            .takeIf { it.isNotBlank() && CookieIdentity.singleUid(listOf(it)) == uid }.orEmpty()
        accounts.put(uid, mergeCookies(oldCookie, merged))
        val domains = getDomainCookiesJson()
        val entry = JSONObject()
        allowedCookies.forEach { (domain, cookie) ->
            if (cookie.isNotBlank()) entry.put(domain, cookie)
        }
        domains.put(uid, entry)
        val storedAccounts = encryptValues(accounts) ?: return null
        val storedDomains = encryptNestedValues(domains) ?: return null
        prefs.edit()
            .putString(KEY_ACCOUNTS, storedAccounts.toString())
            .putString(KEY_DOMAIN_COOKIES, storedDomains.toString())
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

    /** Returns cookies that a request to [url] is allowed to send. */
    fun getCookieForUrl(uid: String, url: String): String? {
        val host = CookieIdentity.normalizeAllowedHost(url) ?: return ""
        val entry = getDomainCookiesJson().optJSONObject(uid) ?: return ""
        // Only the exact target-host jar is eligible. Never fall back to the
        // flattened all-host cookie value, which can leak route/session state.
        return entry.optString(host, "")
            .takeIf { it.isNotBlank() && CookieIdentity.belongsToUidOrHasNoUid(listOf(it), uid) }
            ?: ""
    }

    fun getCookiesByDomain(uid: String): Map<String, String> {
        val entry = getDomainCookiesJson().optJSONObject(uid) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        entry.keys().forEach { domain ->
            val host = CookieIdentity.normalizeAllowedHost(domain) ?: return@forEach
            entry.optString(domain).takeIf { it.isNotBlank() }?.let { result[host] = it }
        }
        return result
    }

    /**
     * 将验证页/WebView 最新拿到的 Cookie 合并回已有账号。
     * 验证页不一定会重新下发 _uid，因此不能调用 addAccount 直接替换账号。
     */
    fun updateCookie(uid: String, cookie: String): Boolean {
        if (uid.isBlank() || cookie.isBlank()) return false
        // Validate the incoming value before merge/deduplication. Otherwise a
        // mixed cookie string could be reduced to the requested UID by taking
        // the last _uid entry and silently overwrite the session.
        if (!CookieIdentity.matchesUid(listOf(cookie), uid)) return false
        val json = getAccountsJson()
        val old = json.optString(uid, "")
        if (old.isNotBlank() && CookieIdentity.singleUid(listOf(old)) != uid) return false
        val merged = mergeCookies(old, cookie)
        // 验证页不能把另一个账号的 Cookie 写入当前账号，也不能用没有 uid 的
        // 空 Cookie 伪装成“验证完成”。
        if (merged.isBlank() || CookieIdentity.singleUid(listOf(merged)) != uid) return false
        json.put(uid, merged)
        val stored = encryptValues(json) ?: return false
        prefs.edit().putString(KEY_ACCOUNTS, stored.toString()).putString(KEY_ACTIVE_UID, uid).apply()
        return true
    }

    /** Merges fresh WebView cookies into their original domains. */
    fun updateCookiesByDomain(uid: String, fresh: Map<String, String>): Boolean {
        if (uid.isBlank() || fresh.isEmpty()) return false
        val allowedFresh = fresh.mapNotNull { (domain, cookie) ->
            val host = CookieIdentity.normalizeAllowedHost(domain) ?: return@mapNotNull null
            host to cookie
        }.toMap()
        if (!CookieIdentity.matchesUid(allowedFresh.values, uid)) return false
        val domains = getDomainCookiesJson()
        val entry = domains.optJSONObject(uid) ?: JSONObject()
        // Drop any legacy/unknown domain keys before persisting the merged jar.
        entry.keys().asSequence().toList().forEach { domain ->
            if (CookieIdentity.normalizeAllowedHost(domain) == null) entry.remove(domain)
        }
        allowedFresh.forEach { (domain, cookie) ->
            if (cookie.isNotBlank()) entry.put(domain, cookie)
        }
        val merged = entry.keys().asSequence().map { entry.optString(it) }.joinToString("; ")
        if (CookieIdentity.singleUid(entry.keys().asSequence().map { entry.optString(it) }.asIterable()) != uid) return false
        domains.put(uid, entry)
        val accounts = getAccountsJson()
        val oldAccountCookie = accounts.optString(uid, "")
        if (oldAccountCookie.isNotBlank() && CookieIdentity.singleUid(listOf(oldAccountCookie)) != uid) return false
        accounts.put(uid, mergeCookies(oldAccountCookie, merged))
        val storedAccounts = encryptValues(accounts) ?: return false
        val storedDomains = encryptNestedValues(domains) ?: return false
        prefs.edit().putString(KEY_ACCOUNTS, storedAccounts.toString())
            .putString(KEY_DOMAIN_COOKIES, storedDomains.toString())
            .putString(KEY_ACTIVE_UID, uid).apply()
        return true
    }

    /** 当前登录账号。退出登录后为空，但历史本地作业数据不受影响。 */
    fun getActiveUid(): String? = prefs.getString(KEY_ACTIVE_UID, null)

    fun hasActiveSession(): Boolean = getActiveUid()?.let { uid ->
        getCookie(uid)?.let { CookieIdentity.singleUid(listOf(it)) == uid } == true
    } == true

    /** 退出当前账号：清除所有保存的登录凭证，不触碰按 uid 保存的本地业务数据。 */
    fun logout() {
        prefs.edit()
            .remove(KEY_ACCOUNTS)
            .remove(KEY_DOMAIN_COOKIES)
            .remove(KEY_ACTIVE_UID)
            .commit()
        appContext.getSharedPreferences("xxt_session", Context.MODE_PRIVATE).edit().remove("cookie").commit()
    }

    /** 删除某个账号 */
    fun removeAccount(uid: String) {
        val json = getAccountsJson()
        json.remove(uid)
        val encryptedAccounts = encryptValues(json) ?: return
        val edit = prefs.edit().putString(KEY_ACCOUNTS, encryptedAccounts.toString())
        val domains = getDomainCookiesJson().apply { remove(uid) }
        val encryptedDomains = encryptNestedValues(domains) ?: return
        edit.putString(KEY_DOMAIN_COOKIES, encryptedDomains.toString())
        if (getActiveUid() == uid) edit.remove(KEY_ACTIVE_UID)
        edit.apply()
    }

    /** 是否一个账号都没有 */
    fun isEmpty(): Boolean = !hasActiveSession()

    private fun getAccountsJson(): JSONObject {
        val raw = prefs.getString(KEY_ACCOUNTS, "{}") ?: "{}"
        return try {
            JSONObject(raw).let { encrypted ->
                val plain = JSONObject()
                encrypted.keys().forEach { uid ->
                    CredentialVault.decrypt(encrypted.optString(uid))?.let { plain.put(uid, it) }
                }
                plain
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun getDomainCookiesJson(): JSONObject {
        val raw = prefs.getString(KEY_DOMAIN_COOKIES, "{}") ?: "{}"
        return runCatching {
            val encrypted = JSONObject(raw)
            val plain = JSONObject()
            encrypted.keys().forEach { uid ->
                val source = encrypted.optJSONObject(uid) ?: return@forEach
                val entry = JSONObject()
                source.keys().forEach domainLoop@{ domain ->
                    val host = CookieIdentity.normalizeAllowedHost(domain) ?: return@domainLoop
                    CredentialVault.decrypt(source.optString(domain))?.let { entry.put(host, it) }
                }
                plain.put(uid, entry)
            }
            plain
        }.getOrElse { JSONObject() }
    }

    /**
     * Migrate legacy flat Cookie jars to the domain-aware store once.
     * Old builds persisted one host's route/JSESSIONID alongside unrelated
     * hosts. Reusing that flattened value can make a valid account look logged
     * out after upgrade; keep it as an encrypted account aggregate and seed
     * each allowed Chaoxing host independently for exact-domain requests.
     */
    private fun migrateLegacyCookiesIfNeeded() {
        val domainsMigrated = prefs.getBoolean("domain_cookie_migration_v1", false)
        val encryptionMigrated = prefs.getBoolean("credential_encryption_v1", false)
        val legacyPrefs = appContext.getSharedPreferences("xxt_session", Context.MODE_PRIVATE)
        val legacyCookie = legacyPrefs.getString("cookie", "").orEmpty()
        if (domainsMigrated && encryptionMigrated) {
            // A crash could have happened after the completion flags were
            // written but before the legacy plaintext was removed. Repeat this
            // cleanup on every startup; it is idempotent.
            val legacyUid = CookieIdentity.singleUid(listOf(legacyCookie))
            if (isCurrentStoreEncrypted() && (legacyUid == null || hasStoredAccount(legacyUid))) {
                legacyPrefs.edit().remove("cookie").commit()
                return
            }
        }
        val accounts = readAccountsForMigration() ?: return
        val domains = readDomainCookiesForMigration() ?: return
        // Versions before AccountManager kept one flat session in SessionManager.
        // Migrate that session before marking the new store ready; otherwise an
        // upgrade would look logged out even though the old session is valid.
        CookieIdentity.singleUid(listOf(legacyCookie))?.let { uid ->
            if (!accounts.has(uid)) {
                accounts.put(uid, legacyCookie)
            }
        }
        val knownHosts = listOf(
            "passport2.chaoxing.com", "passport.chaoxing.com", "i.chaoxing.com",
            "mooc1.chaoxing.com", "mooc2.chaoxing.com", "mooc2-ans.chaoxing.com",
            "mooc1-api.chaoxing.com"
        )
        val accountIds = accounts.keys().asSequence().toList()
        accountIds.forEach { uid ->
            if (CookieIdentity.singleUid(listOf(accounts.optString(uid, ""))) != uid) accounts.remove(uid)
        }
        val validAccountIds = accounts.keys().asSequence().toList()
        // A stale/orphan domain entry can outlive its account after a partial
        // write. It is not recoverable and must not survive as a credential.
        domains.keys().asSequence().toList().forEach { uid ->
            if (uid !in validAccountIds) domains.remove(uid)
        }
        validAccountIds.forEach { uid ->
            val entry = domains.optJSONObject(uid) ?: return@forEach
            entry.keys().asSequence().toList().forEach { domain ->
                val cookie = entry.optString(domain, "")
                if (CookieIdentity.normalizeAllowedHost(domain) == null ||
                    !CookieIdentity.belongsToUidOrHasNoUid(listOf(cookie), uid)
                ) entry.remove(domain)
            }
        }
        validAccountIds.forEach { uid ->
            if (domains.optJSONObject(uid)?.length()?.let { it > 0 } == true) return@forEach
            val legacy = accounts.optString(uid, "")
            if (legacy.isBlank() || CookieIdentity.singleUid(listOf(legacy)) != uid) return@forEach
            val entry = JSONObject()
            knownHosts.forEach { host -> entry.put(host, legacy) }
            domains.put(uid, entry)
        }
        val encryptedAccounts = encryptValues(accounts) ?: return
        val encryptedDomains = encryptNestedValues(domains) ?: return
        val edit = prefs.edit()
            .putString(KEY_ACCOUNTS, encryptedAccounts.toString())
            .putString(KEY_DOMAIN_COOKIES, encryptedDomains.toString())
        val currentActiveUid = prefs.getString(KEY_ACTIVE_UID, null)
        val legacyUid = CookieIdentity.singleUid(listOf(legacyCookie))
        val nextActiveUid = currentActiveUid?.takeIf { it in validAccountIds }
            ?: legacyUid?.takeIf { it in validAccountIds }
        if (nextActiveUid == null) edit.remove(KEY_ACTIVE_UID) else edit.putString(KEY_ACTIVE_UID, nextActiveUid)
        if (!edit.commit()) return
        // The new account store is authoritative. Do not leave a second flat
        // credential behind for old code paths or future backups to discover.
        if (!legacyPrefs.edit().remove("cookie").commit()) return
        // Only after both encrypted values and legacy cleanup are durable is
        // the migration considered complete.
        prefs.edit()
            .putBoolean("domain_cookie_migration_v1", true)
            .putBoolean("credential_encryption_v1", true)
            .commit()
    }

    private fun readAccountsForMigration(): JSONObject? = runCatching {
        val raw = prefs.getString(KEY_ACCOUNTS, "{}") ?: "{}"
        val encrypted = JSONObject(raw)
        JSONObject().also { plain ->
            encrypted.keys().forEach { uid ->
                val value = CredentialVault.decryptOrLegacy(encrypted.optString(uid)) ?: error("credential decrypt failed")
                plain.put(uid, value)
            }
        }
    }.getOrNull()

    private fun readDomainCookiesForMigration(): JSONObject? = runCatching {
        val raw = prefs.getString(KEY_DOMAIN_COOKIES, "{}") ?: "{}"
        val stored = JSONObject(raw)
        JSONObject().also { plain ->
            stored.keys().forEach { uid ->
                val source = stored.optJSONObject(uid) ?: return@forEach
                val entry = JSONObject()
                source.keys().forEach domainLoop@{ domain ->
                    val host = CookieIdentity.normalizeAllowedHost(domain) ?: return@domainLoop
                    val value = CredentialVault.decryptOrLegacy(source.optString(domain))
                        ?: error("domain credential decrypt failed")
                    entry.put(host, value)
                }
                plain.put(uid, entry)
            }
        }
    }.getOrNull()

    private fun isCurrentStoreEncrypted(): Boolean = runCatching {
        val accounts = JSONObject(prefs.getString(KEY_ACCOUNTS, "{}") ?: "{}")
        accounts.keys().forEach { uid ->
            val raw = accounts.optString(uid)
            val plain = CredentialVault.decrypt(raw) ?: return false
            if (CookieIdentity.singleUid(listOf(plain)) != uid) return false
        }
        val domains = JSONObject(prefs.getString(KEY_DOMAIN_COOKIES, "{}") ?: "{}")
        // Every active account must have at least one host-scoped jar. A
        // flat-only account is no longer considered a complete migration,
        // because callers are forbidden from falling back to that jar.
        accounts.keys().forEach { uid ->
            val entry = domains.optJSONObject(uid) ?: return false
            if (entry.length() == 0) return false
        }
        domains.keys().forEach { uid ->
            if (!accounts.has(uid)) return false
            val entry = domains.optJSONObject(uid) ?: return false
            entry.keys().forEach { domain ->
                if (CookieIdentity.normalizeAllowedHost(domain) == null) return false
                val plain = CredentialVault.decrypt(entry.optString(domain)) ?: return false
                if (!CookieIdentity.belongsToUidOrHasNoUid(listOf(plain), uid)) return false
            }
        }
        true
    }.getOrDefault(false)

    private fun hasStoredAccount(uid: String): Boolean = runCatching {
        JSONObject(prefs.getString(KEY_ACCOUNTS, "{}") ?: "{}").has(uid)
    }.getOrDefault(false)

    private fun encryptValues(source: JSONObject): JSONObject? = runCatching {
        JSONObject().also { target ->
            source.keys().forEach { key -> target.put(key, CredentialVault.encrypt(source.optString(key)) ?: error("credential encryption failed")) }
        }
    }.getOrNull()

    private fun encryptNestedValues(source: JSONObject): JSONObject? = runCatching {
        JSONObject().also { target ->
            source.keys().forEach { uid ->
                val nested = source.optJSONObject(uid) ?: return@forEach
                val encrypted = JSONObject()
                nested.keys().forEach { domain ->
                    encrypted.put(domain, CredentialVault.encrypt(nested.optString(domain)) ?: error("credential encryption failed"))
                }
                target.put(uid, encrypted)
            }
        }
    }.getOrNull()

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
