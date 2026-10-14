package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 置顶记录：右滑置顶的作业会排到列表最前面（按账号区分，只存手机本地）。
 * 存储：JSON 数组  [{accountId, ids:["workId", ...]}, ...]
 */
class PinnedStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_pinned", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MAP = "pinned_map"
    }

    private fun readMap(): Map<String, MutableSet<String>> {
        val raw = prefs.getString(KEY_MAP, "{}") ?: "{}"
        val map = mutableMapOf<String, MutableSet<String>>()
        try {
            val json = org.json.JSONObject(raw)
            val it = json.keys()
            while (it.hasNext()) {
                val k = it.next()
                val arr = json.optJSONArray(k) ?: continue
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                map[k] = set
            }
        } catch (e: Exception) { /* 数据损坏就当作空 */ }
        return map
    }

    private fun writeMap(map: Map<String, MutableSet<String>>) {
        val json = org.json.JSONObject()
        map.forEach { (k, v) -> json.put(k, org.json.JSONArray(v.toList())) }
        prefs.edit().putString(KEY_MAP, json.toString()).apply()
    }

    fun isPinned(accountId: String, id: String, courseKey: String = ""): Boolean {
        val set = readMap()[accountId] ?: return false
        return set.contains(storageKey(id, courseKey)) || (courseKey.isNotBlank() && set.contains(id))
    }

    /** 切换置顶状态，返回切换后的状态（true=已置顶） */
    fun toggle(accountId: String, id: String, courseKey: String = ""): Boolean {
        val map = readMap().toMutableMap()
        val set = map.getOrPut(accountId) { mutableSetOf() }
        val stored = storageKey(id, courseKey)
        val legacy = id in set && courseKey.isNotBlank()
        val newState = if (stored in set || legacy) {
            set.remove(stored)
            if (legacy) set.remove(id)
            false
        } else {
            set.add(stored)
            true
        }
        writeMap(map)
        return newState
    }

    fun pinnedIds(accountId: String): Set<String> =
        readMap()[accountId] ?: emptySet()

    private fun storageKey(id: String, courseKey: String): String =
        if (courseKey.isBlank()) id else "$courseKey:$id"
}
