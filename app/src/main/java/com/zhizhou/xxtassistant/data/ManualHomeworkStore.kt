package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手动作业存储：保存你自己添加的作业（老师线下布置、不在学习通里的那种）。
 *
 * 【安全设计】同样只存手机本地私有存储，绝不联网上传。
 * 存储结构：JSON 数组  [{id, accountId, title, courseName, endTime, status}, ...]
 */
class ManualHomeworkStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_manual", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LIST = "manual_homeworks"
    }

    /** 添加一条手动作业，返回它的 id */
    fun add(accountId: String, title: String, courseName: String, endTime: Long, courseKey: String = ""): String {
        val id = "manual_${System.currentTimeMillis()}"
        val arr = read()
        val obj = JSONObject()
            .put("id", id)
            .put("accountId", accountId)
            .put("title", title)
            .put("courseName", courseName)
            .put("endTime", endTime)
            .put("courseKey", courseKey)
            .put("status", Homework.PENDING)
        arr.put(obj)
        write(arr)
        return id
    }

    /** 取出某账号的所有手动作业。旧数据没有 status 时按未完成兼容。 */
    fun list(accountId: String): List<Homework> {
        val result = mutableListOf<Homework>()
        val arr = read()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("accountId") != accountId) continue
            result.add(
                Homework(
                    id = obj.optString("id"),
                    accountId = accountId,
                    courseName = obj.optString("courseName"),
                    title = obj.optString("title"),
                    endTime = obj.optLong("endTime"),
                    status = obj.optInt("status", Homework.PENDING),
                    url = "",                 // 手动作业没有学习通链接
                    source = Homework.MANUAL,
                    courseKey = obj.optString("courseKey")
                )
            )
        }
        return result
    }

    /** 删除一条手动作业 */
    fun remove(accountId: String, id: String) {
        val arr = read()
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") == id && obj.optString("accountId") == accountId) continue
            newArr.put(obj)
        }
        write(newArr)
    }

    /**
     * Restore a previously hidden manual homework without changing its stable
     * id or local completion state. Older builds removed the row when it was
     * permanently hidden, so this also recreates that row when necessary.
     */
    fun restore(
        accountId: String,
        id: String,
        title: String,
        courseName: String,
        endTime: Long,
        courseKey: String = "",
        status: Int = Homework.PENDING
    ) {
        if (accountId.isBlank() || id.isBlank()) return
        val arr = read()
        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("accountId") == accountId && obj.optString("id") == id) {
                found = true
                // Keep the user's current local completion state if the row
                // already exists; hidden metadata is only a fallback.
                if (!obj.has("status")) obj.put("status", status)
                if (!obj.has("courseKey") && courseKey.isNotBlank()) obj.put("courseKey", courseKey)
                break
            }
        }
        if (!found) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("accountId", accountId)
                    .put("title", title)
                    .put("courseName", courseName)
                    .put("endTime", endTime)
                    .put("courseKey", courseKey)
                    .put("status", status)
            )
        }
        write(arr)
    }

    /** 本地完成/取消完成，不代表学习通服务器已提交。 */
    fun setCompleted(accountId: String, id: String, completed: Boolean) {
        val arr = read()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") == id && obj.optString("accountId") == accountId) {
                obj.put("status", if (completed) Homework.DONE else Homework.PENDING)
            }
        }
        write(arr)
    }

    private fun read(): JSONArray {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun write(arr: JSONArray) {
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
