package com.zhizhou.xxtassistant.data

import java.util.Calendar
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    /**
     * Returns the local term key used by course imports and manual courses.
     * August remains semester 2 of the previous academic year; the next
     * academic year starts on September 1.
     */
    @JvmStatic
    fun currentTerm(now: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = now }
        val month = calendar.get(Calendar.MONTH) + 1
        val window = current(now)
        val semester = if (month in 2..8) 2 else 1
        return "${window.startYear}-${window.endYear}-$semester"
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

    /**
     * 将学期文字和日期换算成教学周。课表提醒不能把所有日期都当成第 1 周，
     * 否则导入了“第 8-16 周”的课程后，后续每周都会错误提醒。学期文字缺失时
     * 返回 null，由调用方按手动课程的宽松规则处理。
     */
    @JvmStatic
    fun teachingWeek(term: String, day: Long): Int? {
        val years = Regex("20\\d{2}").findAll(term).map { it.value.toInt() }.toList()
        if (years.isEmpty()) return null
        val startYear = years.first()
        val semester = Regex("(?:^|[-_/])([12])(?:$|[-_/])").find(term)?.groupValues?.get(1)?.toIntOrNull()
        val start = if (semester == 2) {
            LocalDate.of(startYear + 1, 2, 1)
        } else {
            LocalDate.of(startYear, 9, 1)
        }
        val end = if (semester == 2) {
            LocalDate.of(startYear + 1, 7, 31)
        } else {
            LocalDate.of(startYear + 1, 1, 31)
        }
        val target = Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate()
        if (target.isBefore(start) || target.isAfter(end)) return null
        return (ChronoUnit.DAYS.between(start, target) / 7L).toInt() + 1
    }
}
