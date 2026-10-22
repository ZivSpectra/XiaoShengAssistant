package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TeachingWeekPickerTest {
    @Test
    fun providesTwentyFiveWeeksInFiveRowsAndFiveColumns() {
        assertEquals(5, TeachingWeekPicker.rows.size)
        assertEquals(listOf(1, 2, 3, 4, 5), TeachingWeekPicker.rows.first())
        assertEquals(listOf(21, 22, 23, 24, 25), TeachingWeekPicker.rows.last())
        assertEquals((1..25).toList(), TeachingWeekPicker.rows.flatten())
    }

    @Test
    fun clampsCurrentWeekAndNavigationToPickerRange() {
        assertEquals(1, TeachingWeekPicker.clamp(0))
        assertEquals(25, TeachingWeekPicker.clamp(60))
        assertEquals(25, TeachingWeekPicker.next(25))
        assertEquals(1, TeachingWeekPicker.previous(1))
    }

    @Test
    fun mapsHorizontalSwipesToAdjacentWeekWithoutCrossingBounds() {
        assertEquals(6, TeachingWeekPicker.afterHorizontalSwipe(5, -100f))
        assertEquals(4, TeachingWeekPicker.afterHorizontalSwipe(5, 100f))
        assertEquals(5, TeachingWeekPicker.afterHorizontalSwipe(5, 20f))
        assertEquals(25, TeachingWeekPicker.afterHorizontalSwipe(25, -100f))
        assertEquals(1, TeachingWeekPicker.afterHorizontalSwipe(1, 100f))
    }
}
