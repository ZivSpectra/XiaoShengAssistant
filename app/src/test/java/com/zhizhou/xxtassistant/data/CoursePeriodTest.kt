package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePeriodTest {
    @Test
    fun defaultPeriodsAreOrderedAndValid() {
        assertTrue(CoursePeriodStore.defaultPeriods.all { it.isValid() })
        assertTrue(CoursePeriodStore.defaultPeriods.zipWithNext().all { it.first.period < it.second.period })
    }

    @Test
    fun invalidPeriodCannotBeSavedAsAValidTime() {
        assertFalse(CoursePeriod(1, 9 * 60, 9 * 60).isValid())
        assertFalse(CoursePeriod(1, -1, 9 * 60).isValid())
    }
}
