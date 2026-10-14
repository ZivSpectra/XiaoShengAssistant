package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AcademicYearTest {
    private fun time(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    @Test
    fun septemberStartsNewAcademicYear() {
        val window = AcademicYear.current(time(2026, 9, 1))
        assertEquals(2026, window.startYear)
        assertEquals(2027, window.endYear)
        assertTrue(AcademicYear.matchesTerm("2026-2027-1", time(2026, 9, 2), window))
        assertTrue(!AcademicYear.matchesTerm("2025-2026-2", time(2026, 9, 2), window))
    }

    @Test
    fun julyBelongsToPreviousSeptemberWindow() {
        val window = AcademicYear.current(time(2027, 7, 31))
        assertEquals(2026, window.startYear)
        assertEquals(2027, window.endYear)
        assertTrue(AcademicYear.matchesTerm("2026-2027", time(2027, 7, 20), window))
    }
}
