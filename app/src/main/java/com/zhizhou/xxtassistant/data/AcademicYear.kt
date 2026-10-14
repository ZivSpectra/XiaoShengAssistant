package com.zhizhou.xxtassistant.data

import java.util.Calendar
import java.util.Locale

/** 学年按 9 月 1 日至次年 7 月 31 日计算。 */
object AcademicYear {

    data class Window(val start: Long, val end: Long, val startYear: Int, val endYear: Int)

    @JvmStatic
    fun current(now: Long = System.currentTimeMillis()): Window {
        val calendar = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = now }
        val year = calendar.get(Calendar.YEAR)
        val startYear = if (calendar.get(Calendar.MONTH) + 1 >= 9) year else year - 1
        val start = Calendar.getInstance(Locale.CHINA).apply {
            clear()
            set(startYear, Calendar.SEPTEMBER, 1, 0, 0, 0)
        }.timeInMillis
        val end = Calendar.getInstance(Locale.CHINA).apply {
            clear()
            set(startYear + 1, Calendar.JULY, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return Window(start, end, startYear, startYear + 1)
    }

    /** 课程学期文字包含学年任一年份时，按当前学年归档。无学期文字时回退到最近同步时间。 */
    @JvmStatic
    fun matchesTerm(term: String, seenAt: Long, window: Window = current()): Boolean {
        val years = Regex("20\\d{2}").findAll(term).map { it.value.toInt() }.toList()
        if (years.isNotEmpty()) {
            return if (years.size >= 2) {
                years[0] == window.startYear && years[1] == window.endYear
            } else {
                years[0] == window.startYear || years[0] == window.endYear
            }
        }
        return seenAt in window.start..window.end
    }
}
