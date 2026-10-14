package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 隐藏作业记录：自动作业滑动"隐藏"后，在这里记住它（含标题/课程/截止时间/隐藏时间）。
 *
 * 用途：
 *  - 刷新抓取时把隐藏的作业过滤掉（学习通上的数据不受影响）
 *  - 设置里列出全部已隐藏作业，按【隐藏时间从新到旧】排序，可单独恢复或批量恢复
 * 数据只存手机本地。
 */
class HiddenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_hidden", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MAP = "hidden_map"

        /** 隐藏来源：自动作业 / 手动添加的作业 */
        const val SOURCE_AUTO = "auto"
        const val SOURCE_MANUAL = "manual"
    }

    /** 一条被隐藏的作业（source 标记来源：恢复手动作业时写回手动列表） */
    data class HiddenItem(
        val id: String,
        val title: String,
        val course: String,
        val courseKey: String = "",
        val endTime: Long,
        val hiddenAt: Long,
        val source: String = SOURCE_AUTO,
        val permanent: Boolean = true,
        val scopeKey: String = ""
    )

    private fun readMap(): Map<String, MutableList<HiddenItem>> {
        val raw = prefs.getString(KEY_MAP, "{}") ?: "{}"
        val map = mutableMapOf<String, MutableList<HiddenItem>>()
        try {
            val json = JSONObject(raw)
            val it = json.keys()
            while (it.hasNext()) {
                val k = it.next()
                val arr = json.optJSONArray(k) ?: continue
                val list = mutableListOf<HiddenItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(
                        HiddenItem(
                            id = obj.optString("id"),
                            title = obj.optString("title"),
                            course = obj.optString("course"),
                            courseKey = obj.optString("courseKey"),
                            endTime = obj.optLong("endTime"),
                            hiddenAt = obj.optLong("hiddenAt"),
                            source = obj.optString("source", SOURCE_AUTO),
                            permanent = obj.optBoolean("permanent", true),
                            scopeKey = obj.optString("scopeKey")
                        )
                    )
                }
                map[k] = list
            }
        } catch (e: Exception) { /* 数据损坏就当空 */ }
        return map
    }

    private fun writeMap(map: Map<String, MutableList<HiddenItem>>) {
        val json = JSONObject()
        map.forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("course", item.course)
                        .put("courseKey", item.courseKey)
                        .put("endTime", item.endTime)
                        .put("hiddenAt", item.hiddenAt)
                        .put("source", item.source)
                        .put("permanent", item.permanent)
                        .put("scopeKey", item.scopeKey)
                )
            }
            json.put(k, arr)
        }
        prefs.edit().putString(KEY_MAP, json.toString()).apply()
    }

    fun hide(
        accountId: String,
        id: String,
        title: String,
        course: String,
        endTime: Long,
        courseKey: String = "",
        source: String = SOURCE_AUTO,
        permanent: Boolean = true,
        scopeKey: String = ""
    ) {
        val map = readMap().toMutableMap()
        val list = map.getOrPut(accountId) { mutableListOf() }
        // 重复隐藏同一条时去重
        list.removeAll { it.id == id && (courseKey.isBlank() || it.courseKey == courseKey) }
        list.add(HiddenItem(id, title, course, courseKey, endTime, System.currentTimeMillis(), source, permanent, scopeKey))
        writeMap(map)
    }

    /** 该账号全部已隐藏作业，按隐藏时间【从新到旧】排序 */
    fun list(accountId: String): List<HiddenItem> =
        (readMap()[accountId] ?: emptyList()).sortedByDescending { it.hiddenAt }

    /** 单独恢复一条 */
    fun restore(accountId: String, id: String, courseKey: String = "") {
        val map = readMap().toMutableMap()
        map[accountId]?.removeAll {
            it.id == id && (courseKey.isBlank() || it.courseKey == courseKey)
        }
        writeMap(map)
    }

    /** 批量恢复多条 */
    fun restoreAll(accountId: String, ids: Collection<String>) {
        val map = readMap().toMutableMap()
        map[accountId]?.removeAll { it.id in ids }
        writeMap(map)
    }

    /** 已隐藏的 id 集合（抓取时过滤用） */
    fun hiddenIds(accountId: String, scopeKey: String = ""): Set<String> =
        (readMap()[accountId] ?: emptyList())
            .filter { it.permanent || it.scopeKey == scopeKey }
            .map { it.id }
            .toSet()

    fun isHidden(accountId: String, homework: Homework, scopeKey: String = ""): Boolean =
        (readMap()[accountId] ?: emptyList()).any {
            it.id == homework.id &&
                (it.courseKey.isBlank() || it.courseKey == homework.courseKey) &&
                (it.permanent || it.scopeKey == scopeKey)
        }
}
