package com.zhizhou.xxtassistant.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseTableSettingsTest {
    @Test
    fun configuredWeekUsesLocalCalendarDaysAcrossDaylightSavingTime() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val start = date(2026, Calendar.MARCH, 2)
            val nextWeek = date(2026, Calendar.MARCH, 9)

            assertEquals(2, teachingWeekFromStartDate(start, nextWeek))
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun configuredWeekIsOneOnStartDateAndUnknownBeforeIt() {
        val start = date(2026, Calendar.SEPTEMBER, 7)
        val sameDayLater = start + 18 * 60 * 60 * 1000L
        val dayBefore = date(2026, Calendar.SEPTEMBER, 6)

        assertEquals(1, teachingWeekFromStartDate(start, sameDayLater))
        assertNull(teachingWeekFromStartDate(start, dayBefore))
    }

    private fun date(year: Int, month: Int, day: Int): Long = Calendar.getInstance(Locale.CHINA).apply {
        clear()
        set(year, month, day, 0, 0, 0)
    }.timeInMillis
}
