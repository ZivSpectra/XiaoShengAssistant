package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.Transliterator
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale

/**
 * 课程库：课程独立于作业保存。这样即使学习通某门课暂时没有作业，
 * 手动添加作业时仍然可以从课程选择器中复用它。
 */
class CourseStore(context: Context) {

    data class CourseItem(
        val key: String,
        val accountId: String,
        val name: String,
        val className: String = "",
        val term: String = "",
        val courseId: String = "",
        val classId: String = "",
        val cpi: String = "",
        val manual: Boolean = false,
        val lastUsedAt: Long = 0L,
        /** 最近一次从学习通同步到本地的时间，用于没有学期字段的课程归档。 */
        val lastSeenAt: Long = 0L
    ) {
        val displayName: String
            get() {
                val suffix = listOf(className, term)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                return if (suffix.isEmpty()) name else "$name · ${suffix.joinToString(" / ")}"
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_courses", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LIST = "courses"
    }

    fun upsertAutomatic(accountId: String, course: CourseItem) {
        val all = read().filterNot { it.accountId == accountId && it.key == course.key }.toMutableList()
        val previous = read().firstOrNull { it.accountId == accountId && it.key == course.key }
        all += course.copy(
            accountId = accountId,
            manual = false,
            lastUsedAt = previous?.lastUsedAt ?: 0L,
            lastSeenAt = System.currentTimeMillis()
        )
        write(all)
    }

    /** 全量同步成功后移除已经不在学习通课程列表中的旧自动课程，手动课程始终保留。 */
    fun removeStaleAutomatic(accountId: String, activeKeys: Set<String>) {
        write(read().filterNot {
            it.accountId == accountId && !it.manual && it.key !in activeKeys
        })
    }

    /** 添加或复用账号内的手动课程。相同名称只保留一个快捷选项。 */
    fun addManual(accountId: String, name: String): CourseItem? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val existing = list(accountId).firstOrNull { it.manual && it.name == clean }
        if (existing != null) return existing
        val item = CourseItem(
            key = "manual_${UUID.randomUUID()}",
            accountId = accountId,
            name = clean,
            manual = true,
            lastUsedAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis()
        )
        write(read() + item)
        return item
    }

    /**
     * 课程选择器排序：最近使用优先，或按拼音首字母。数字及非字母课程归入 #。
     */
    fun list(accountId: String, recentFirst: Boolean = false): List<CourseItem> {
        val source = read().filter { it.accountId == accountId }
        if (recentFirst) {
            return source.sortedWith(compareByDescending<CourseItem> { it.lastUsedAt }
                .thenBy { it.name }.thenBy { it.className }.thenBy { it.term })
        }
        fun sortKey(item: CourseItem): String {
            val latin = toLatin(item.name).lowercase(Locale.ROOT)
            val first = latin.firstOrNull()
            return if (first != null && first in 'a'..'z') "0$first$latin" else "1#$latin"
        }
        return source.sortedWith(compareBy<CourseItem> { sortKey(it) }
            .thenBy { it.name }.thenBy { it.className }.thenBy { it.term })
    }

    /** 手动添加作业的课程列表：手动课程始终保留，自动课程只显示当前学年。 */
    fun listForManualSelection(accountId: String, recentFirst: Boolean = false): List<CourseItem> {
        val window = AcademicYear.current()
        val source = read().filter { item ->
            item.accountId == accountId &&
                (item.manual || AcademicYear.matchesTerm(item.term, item.lastSeenAt, window))
        }
        return sort(source, recentFirst)
    }

    /** 课程选择器使用的可见索引：A-Z，数字和特殊字符统一归入 #。 */
    fun indexLetter(name: String): String {
        val latin = toLatin(name).uppercase(Locale.ROOT)
        val first = latin.firstOrNull()
        return if (first != null && first in 'A'..'Z') first.toString() else "#"
    }

    private fun toLatin(name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("NewApi")
            return runCatching {
                Transliterator.getInstance("Han-Latin; Latin-ASCII").transliterate(name.trim())
            }.getOrDefault(name.trim())
        }
        // Android 26-28 没有可用的公开 ICU 转写 API，至少保持 ASCII 课程可稳定排序。
        return name.trim()
    }

    fun markUsed(accountId: String, key: String) {
        val all = read().map {
            if (it.accountId == accountId && it.key == key) it.copy(lastUsedAt = System.currentTimeMillis()) else it
        }
        write(all)
    }

    fun find(accountId: String, key: String): CourseItem? =
        list(accountId).firstOrNull { it.key == key }

    fun removeManual(accountId: String, key: String) {
        write(read().filterNot { it.accountId == accountId && it.key == key && it.manual })
    }

    private fun read(): List<CourseItem> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key")
                    val accountId = o.optString("accountId")
                    val name = o.optString("name")
                    if (key.isNotBlank() && accountId.isNotBlank() && name.isNotBlank()) {
                        add(CourseItem(
                            key = key,
                            accountId = accountId,
                            name = name,
                            className = o.optString("className"),
                            term = o.optString("term"),
                            courseId = o.optString("courseId"),
                            classId = o.optString("classId"),
                            cpi = o.optString("cpi"),
                            manual = o.optBoolean("manual", false),
                            lastUsedAt = o.optLong("lastUsedAt", 0L),
                            lastSeenAt = o.optLong("lastSeenAt", 0L)
                        ))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(items: List<CourseItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject()
                .put("key", item.key)
                .put("accountId", item.accountId)
                .put("name", item.name)
                .put("className", item.className)
                .put("term", item.term)
                .put("courseId", item.courseId)
                .put("classId", item.classId)
                .put("cpi", item.cpi)
                .put("manual", item.manual)
                .put("lastUsedAt", item.lastUsedAt)
                .put("lastSeenAt", item.lastSeenAt))
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun sort(source: List<CourseItem>, recentFirst: Boolean): List<CourseItem> {
        if (recentFirst) {
            return source.sortedWith(compareByDescending<CourseItem> { maxOf(it.lastUsedAt, it.lastSeenAt) }
                .thenBy { it.name }.thenBy { it.className }.thenBy { it.term })
        }
        fun sortKey(item: CourseItem): String {
            val latin = toLatin(item.name).lowercase(Locale.ROOT)
            val first = latin.firstOrNull()
            return if (first != null && first in 'a'..'z') "0$first$latin" else "1#$latin"
        }
        return source.sortedWith(compareBy<CourseItem> { sortKey(it) }
            .thenBy { it.name }.thenBy { it.className }.thenBy { it.term })
    }
}
