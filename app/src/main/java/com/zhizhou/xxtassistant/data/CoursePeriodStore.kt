package com.zhizhou.xxtassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学校作息表。不同学校的第 1 节课并不一定从 08:00 开始，
 * 因此课程提醒不能把节次直接写死在调度器里。
 */
data class CoursePeriod(
    val period: Int,
    val startMinute: Int,
    val endMinute: Int
) {
    fun isValid(): Boolean = period >= 1 && startMinute in 0 until 24 * 60 &&
        endMinute in (startMinute + 1)..(24 * 60 - 1)
}

class CoursePeriodStore(context: Context) {
    private val prefs = context.getSharedPreferences("xxt_course_periods", Context.MODE_PRIVATE)

    /** Whether usable bell times exist for every requested period. */
    fun hasCustom(accountId: String): Boolean = prefs.contains(key(accountId))

    /**
     * The first-run schedule is a deliberate Changsha-compatible fallback,
     * not an unconfigured state.  Custom values still take precedence when a
     * user saves them, while imported schedules can use the fallback directly.
     */
    fun hasConfiguredPeriods(accountId: String, periods: Set<Int>): Boolean {
        if (periods.isEmpty()) return false
        return periods.all { period -> list(accountId).any { it.period == period && it.isValid() } }
    }

    fun list(accountId: String): List<CoursePeriod> {
        val raw = prefs.getString(key(accountId), null)
        if (raw.isNullOrBlank()) return defaultPeriods
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val period = CoursePeriod(
                        period = item.optInt("period"),
                        startMinute = item.optInt("start"),
                        endMinute = item.optInt("end")
                    )
                    if (period.isValid()) add(period)
                }
            }.distinctBy { it.period }.sortedBy { it.period }
        }.getOrDefault(defaultPeriods)
            .ifEmpty { defaultPeriods }
    }

    fun save(accountId: String, periods: List<CoursePeriod>) {
        val clean = periods.filter { it.isValid() }.distinctBy { it.period }.sortedBy { it.period }
        val array = JSONArray()
        clean.forEach { period ->
            array.put(JSONObject()
                .put("period", period.period)
                .put("start", period.startMinute)
                .put("end", period.endMinute))
        }
        prefs.edit().putString(key(accountId), array.toString()).apply()
    }

    fun reset(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: String): String = "periods:${accountId.take(80)}"

    companion object {
        /**
         * 首次使用的长沙学院兼容作息：每小节 45 分钟，普通课间 10
         * 分钟，两节后 20 分钟；11:40 午休，14:00 下午开始，17:40
         * 下午结束，19:00-20:40 晚间课程。用户仍可在课程表设置中修改。
         */
        val defaultPeriods: List<CoursePeriod> = listOf(
            CoursePeriod(1, 8 * 60, 8 * 60 + 45),
            CoursePeriod(2, 8 * 60 + 55, 9 * 60 + 40),
            CoursePeriod(3, 10 * 60, 10 * 60 + 45),
            CoursePeriod(4, 10 * 60 + 55, 11 * 60 + 40),
            CoursePeriod(5, 14 * 60, 14 * 60 + 45),
            CoursePeriod(6, 14 * 60 + 55, 15 * 60 + 40),
            CoursePeriod(7, 16 * 60, 16 * 60 + 45),
            CoursePeriod(8, 16 * 60 + 55, 17 * 60 + 40),
            CoursePeriod(9, 19 * 60, 19 * 60 + 45),
            CoursePeriod(10, 19 * 60 + 55, 20 * 60 + 40)
        )
    }
}
