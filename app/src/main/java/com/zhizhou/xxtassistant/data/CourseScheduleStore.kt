package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 账号隔离的本地课程表存储。 */
class CourseScheduleStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_course_schedule", Context.MODE_PRIVATE)

    fun list(accountId: String, term: String? = null): List<CourseSchedule> = read()
        .filter { it.accountId == accountId && (term.isNullOrBlank() || it.term == term) }
        .sortedWith(compareBy<CourseSchedule> { it.weekday }.thenBy { it.startPeriod }.thenBy { it.name })

    fun terms(accountId: String): List<String> = list(accountId)
        .map { it.term.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun upsert(schedule: CourseSchedule) {
        val all = read().filterNot { it.accountId == schedule.accountId && it.id == schedule.id }
        write(all + schedule)
    }

    fun replaceImported(accountId: String, term: String, schedules: List<CourseSchedule>, source: Int) {
        val imported = schedules
            .filter { it.accountId == accountId && it.term == term && it.isValid() }
            .map { it.copy(source = source) }
        // Never erase a previously imported table because a parser returned an
        // empty/foreign result. Callers still show parse errors to the user.
        if (imported.isEmpty()) return
        val retained = read().filterNot {
            // A fresh import is authoritative for imported rows in this term,
            // regardless of whether the previous import came from CSV or the
            // academic WebView. Keep only manually entered courses.
            it.accountId == accountId && it.term == term && it.source != CourseSchedule.SOURCE_MANUAL
        }
        write(retained + imported)
    }

    fun remove(accountId: String, id: String) {
        write(read().filterNot { it.accountId == accountId && it.id == id })
    }

    fun clearAccount(accountId: String) {
        write(read().filterNot { it.accountId == accountId })
    }

    private fun read(): List<CourseSchedule> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val item = CourseSchedule(
                        id = o.optString("id"), accountId = o.optString("accountId"),
                        term = o.optString("term"), name = o.optString("name"),
                        teacher = o.optString("teacher"), room = o.optString("room"),
                        weekday = o.optInt("weekday"), startPeriod = o.optInt("startPeriod"),
                        endPeriod = o.optInt("endPeriod"), startWeek = o.optInt("startWeek", 1),
                        endWeek = o.optInt("endWeek", 20), source = o.optInt("source", CourseSchedule.SOURCE_MANUAL),
                        weeks = o.optJSONArray("weeks")?.let { array ->
                            buildList {
                                for (index in 0 until array.length()) {
                                    array.optInt(index, -1).takeIf { it > 0 }?.let(::add)
                                }
                            }
                        }.orEmpty(),
                        weekParity = o.optInt("weekParity", 0),
                        note = o.optString("note"), createdAt = o.optLong("createdAt", 0L)
                    )
                    if (item.id.isNotBlank() && item.accountId.isNotBlank() && item.isValid()) add(item)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<CourseSchedule>) {
        val array = JSONArray()
        items.distinctBy { "${it.accountId}:${it.id}" }.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("accountId", item.accountId)
                .put("term", item.term)
                .put("name", item.name)
                .put("teacher", item.teacher)
                .put("room", item.room)
                .put("weekday", item.weekday)
                .put("startPeriod", item.startPeriod)
                .put("endPeriod", item.endPeriod)
                .put("startWeek", item.startWeek)
                .put("endWeek", item.endWeek)
                .put("weeks", JSONArray(item.weeks.distinct().sorted()))
                .put("weekParity", item.weekParity)
                .put("source", item.source)
                .put("note", item.note)
                .put("createdAt", item.createdAt))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
