package com.zhizhou.xxtassistant.data

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 课程表账号级设置，避免把学校作息和作业提醒设置混在一起。 */
class CourseTableSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xxt_course_table_settings", Context.MODE_PRIVATE)

    fun preparationHour(accountId: String): Int = prefs.getInt(key(accountId, "prep_hour"), 20)

    fun preparationMinute(accountId: String): Int = prefs.getInt(key(accountId, "prep_minute"), 0)

    fun savePreparationTime(accountId: String, hour: Int, minute: Int) {
        if (hour !in 0..23 || minute !in 0..59) return
        prefs.edit()
            .putInt(key(accountId, "prep_hour"), hour)
            .putInt(key(accountId, "prep_minute"), minute)
            .apply()
    }

    fun termStartDate(accountId: String, term: String): Long? =
        prefs.getLong(key(accountId, "term_start:$term"), -1L).takeIf { it > 0L }

    fun hasTermStartDate(accountId: String, term: String): Boolean = termStartDate(accountId, term) != null

    fun saveTermStartDate(accountId: String, term: String, day: Long?) {
        val editor = prefs.edit()
        if (day == null || day <= 0L) editor.remove(key(accountId, "term_start:$term"))
        else editor.putLong(key(accountId, "term_start:$term"), startOfDay(day))
        editor.apply()
    }

    /** 有真实开学日时按用户设置计算教学周，否则回退到学期文字推断。 */
    fun teachingWeek(accountId: String, term: String, day: Long): Int? {
        val configured = termStartDate(accountId, term) ?: return AcademicYear.teachingWeek(term, day)
        return teachingWeekFromStartDate(configured, day)
    }

    private fun startOfDay(value: Long): Long = Calendar.getInstance().apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun key(accountId: String, name: String): String = "$name:${accountId.take(80)}"
}

internal fun teachingWeekFromStartDate(startDate: Long, day: Long): Int? {
    fun localDate(value: Long): LocalDate {
        val calendar = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = value }
        return LocalDate.of(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    val start = localDate(startDate)
    val target = localDate(day)
    if (target.isBefore(start)) return null
    return (ChronoUnit.DAYS.between(start, target) / 7L).toInt() + 1
}
