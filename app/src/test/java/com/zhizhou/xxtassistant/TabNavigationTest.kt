package com.zhizhou.xxtassistant

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavigationTest {
    @Test
    fun rootTabTransitionsReturnToTodayWithoutGrowingStack() {
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            TAB_NAVIGATION_FLAGS
        )
        assertFalse(shouldNavigateToTab(String::class.java, String::class.java))
        assertTrue(shouldNavigateToTab(String::class.java, Int::class.java))
    }
}
