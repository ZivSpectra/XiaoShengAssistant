package com.zhizhou.xxtassistant.data

import org.json.JSONObject
import java.util.Locale

/**
 * 判断课程是否明确结束。
 *
 * 课程接口中的通用 endTime 可能表示最近一次活动或其它时间，不能直接当成
 * 课程结束时间。只有明确的结束状态/布尔字段，或命名明确的课程结束字段才参与判断。
 */
object CourseStatus {

    @JvmStatic
    fun isEnded(now: Long, vararg objects: JSONObject): Boolean {
        return objects.any { obj ->
            val values = linkedMapOf<String, String>()
            listOf(
                "state", "status", "courseState", "isState", "ended", "isEnded", "isEnd",
                "courseEndTime", "courseEndDate", "classEndTime", "classEndDate",
                "clazzEndTime", "clazzEndDate", "endDate"
            ).forEach { key ->
                if (obj.has(key)) values[key] = obj.optString(key, "")
            }
            isEnded(now, values)
        }
    }

    @JvmStatic
    fun isEnded(now: Long, values: Map<String, String>): Boolean {
        val state = firstNonBlank(
            values["state"].orEmpty(),
            values["status"].orEmpty(),
            values["courseState"].orEmpty(),
            values["isState"].orEmpty()
        ).lowercase(Locale.ROOT)
        if (state.contains("结束") || state == "ended" || state == "finish" || state == "finished") {
            return true
        }
        if (values["ended"].isTruthy() || values["isEnded"].isTruthy() || values["isEnd"].isTruthy()) {
            return true
        }

        val endRaw = firstNonBlank(
            values["courseEndTime"].orEmpty(),
            values["courseEndDate"].orEmpty(),
            values["classEndTime"].orEmpty(),
            values["classEndDate"].orEmpty(),
            values["clazzEndTime"].orEmpty(),
            values["clazzEndDate"].orEmpty(),
            values["endDate"].orEmpty()
        )
        val end = DeadlineParser.parseDateText(endRaw)
            ?: endRaw.toLongOrNull()?.let { raw -> if (endRaw.length == 10) raw * 1000L else raw }
        return end != null && end > 0L && end < now
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    private fun String?.isTruthy(): Boolean =
        this.equals("true", ignoreCase = true) || this == "1" || this == "是"
}
