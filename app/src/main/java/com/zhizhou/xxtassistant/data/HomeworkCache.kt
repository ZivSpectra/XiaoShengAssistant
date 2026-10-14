package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 作业本地缓存：每次抓取成功后把自动作业存下来。
 *
 * 作用：下次进应用 / 切换账号 / 刷新时，先把上次抓的作业立刻显示出来，
 * 再在后台静默更新——即使这次网络慢或抓取失败，列表也永远有东西看。
 *
 * 【安全】只存在应用私有存储里，绝不外传。
 */
class HomeworkCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_cache", Context.MODE_PRIVATE)

    /** 保存某账号的自动作业缓存（只存自动作业，手动作业在 ManualHomeworkStore 里） */
    fun save(uid: String, homeworks: List<Homework>) {
        val root = JSONObject()
        homeworks.filter { !it.isManual() }.forEach { hw ->
            val key = cacheKey(hw)
            val existing = readRoot(uid).optJSONObject(key)?.let(::fromJson)
            root.put(key, toJson(HomeworkCachePolicy.merge(existing, hw)))
        }
        prefs.edit()
            .putString(uid, root.toString())
            .putLong(savedAtKey(uid), System.currentTimeMillis())
            .apply()
    }

    /** 更新缓存中的单条自动作业，不改变原有缓存时间。 */
    fun upsert(uid: String, homework: Homework): Homework {
        if (homework.isManual()) return homework
        val root = readRoot(uid)
        val key = cacheKey(homework)
        val existing = root.optJSONObject(key)?.let(::fromJson)
        val merged = HomeworkCachePolicy.merge(existing, homework)
        root.put(key, toJson(merged))
        prefs.edit().putString(uid, root.toString()).apply()
        return merged
    }

    fun removeCourse(uid: String, courseKey: String) {
        val root = try {
            JSONObject(prefs.getString(uid, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val removeKeys = mutableListOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (root.optJSONObject(key)?.optString("courseKey") == courseKey) removeKeys.add(key)
        }
        removeKeys.forEach(root::remove)
        prefs.edit().putString(uid, root.toString()).apply()
    }

    /** 读取某账号上次缓存的自动作业（没有则返回空列表） */
    fun load(uid: String): List<Homework> {
        val raw = prefs.getString(uid, null) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val list = mutableListOf<Homework>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                list.add(fromJson(root.getJSONObject(key)))
            }
            list
        } catch (e: Exception) {
            emptyList()   // 缓存损坏就当没有，不影响使用
        }
    }

    /** 没有时间戳的旧缓存视为过期，首次升级时会自动刷新一次。 */
    fun isStale(uid: String, maxAgeMs: Long): Boolean {
        val savedAt = prefs.getLong(savedAtKey(uid), 0L)
        return savedAt <= 0L || System.currentTimeMillis() - savedAt >= maxAgeMs
    }

    /** 记录最近一次自动抓取尝试，避免验证码/网络失败时每次回到页面都重复请求。 */
    fun markFetchAttempt(uid: String) {
        prefs.edit().putLong(fetchAttemptKey(uid), System.currentTimeMillis()).apply()
    }

    fun isFetchAttemptCoolingDown(uid: String, cooldownMs: Long): Boolean {
        val attemptedAt = prefs.getLong(fetchAttemptKey(uid), 0L)
        return attemptedAt > 0L && System.currentTimeMillis() - attemptedAt < cooldownMs
    }

    private fun savedAtKey(uid: String): String = "${uid}_saved_at"

    private fun fetchAttemptKey(uid: String): String = "${uid}_fetch_attempt"

    private fun cacheKey(homework: Homework): String =
        "${homework.courseKey.ifBlank { "_" }}:${homework.id}"

    private fun readRoot(uid: String): JSONObject = try {
        JSONObject(prefs.getString(uid, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    // ---------- 序列化 ----------

    private fun toJson(hw: Homework) = JSONObject().apply {
        put("id", hw.id)
        put("accountId", hw.accountId)
        put("courseName", hw.courseName)
        put("title", hw.title)
        put("endTime", hw.endTime)
        put("status", hw.status)
        put("url", hw.url)
        put("source", hw.source)
        put("courseKey", hw.courseKey)
        put("officialEndTime", hw.officialEndTime ?: 0L)
        put("deadlineOverridden", hw.deadlineOverridden)
    }

    private fun fromJson(o: JSONObject) = Homework(
        id = o.getString("id"),
        accountId = o.getString("accountId"),
        courseName = o.getString("courseName"),
        title = o.getString("title"),
        endTime = o.getLong("endTime"),
        status = o.getInt("status"),
        url = o.optString("url", ""),
        source = o.optInt("source", Homework.AUTO),
        courseKey = o.optString("courseKey", ""),
        officialEndTime = o.optLong("officialEndTime", 0L).takeIf { it > 0L },
        deadlineOverridden = o.optBoolean("deadlineOverridden", false)
    )
}
