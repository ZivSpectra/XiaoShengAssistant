package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLimitPolicyTest {
    @Test fun limitIsInclusiveAtExactlyThirtyMinutes() {
        assertFalse(FocusLimitPolicy.reached(29 * 60_000L + 59_999L, 30))
        assertTrue(FocusLimitPolicy.reached(30 * 60_000L, 30))
    }

    @Test fun invalidLimitDoesNotTriggerBlock() {
        assertFalse(FocusLimitPolicy.reached(Long.MAX_VALUE, 0))
        assertFalse(FocusLimitPolicy.reached(Long.MAX_VALUE, -1))
    }

    @Test fun learningAppCannotAlsoBeRestricted() {
        assertFalse(FocusSettings(entertainmentPackages = setOf("example.video"), studyPackage = "example.video").configured)
        assertTrue(FocusSettings(entertainmentPackages = setOf("example.video"), studyPackage = "example.study").configured)
    }

    @Test fun redirectIsSuppressedUntilTheStudyAppActuallyAppears() {
        val gate = FocusRedirectGate()
        assertTrue(gate.begin("example.video", "example.study"))
        gate.onForeground("example.video")
        gate.onForeground("com.android.launcher", isHome = true)
        assertFalse(gate.begin("example.video", "example.study"))
        gate.onForeground("example.study")
        assertFalse(gate.isPending)
        assertTrue(gate.begin("example.video", "example.study"))
    }

    @Test fun returningToTheRestrictedAppAfterLeavingAllowsANewRedirect() {
        val gate = FocusRedirectGate()
        assertTrue(gate.begin("example.video", "example.study"))
        gate.onForeground("com.android.settings")
        gate.onForeground("example.video")
        assertFalse(gate.isPending)
        assertTrue(gate.begin("example.video", "example.study"))
    }
}
