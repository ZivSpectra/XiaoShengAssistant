package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDeadlinePromptPolicyTest {
    @Test
    fun noRecordPromptsImmediately() {
        assertTrue(ManualDeadlinePromptPolicy.shouldPrompt(null, 100L, 200L))
    }

    @Test
    fun snoozeSuppressesUntilTwentyFourHours() {
        val record = ManualDeadlinePromptPolicy.snoozed(100L, 1_000L)
        assertFalse(ManualDeadlinePromptPolicy.shouldPrompt(record, 100L, 1_000L))
        assertFalse(ManualDeadlinePromptPolicy.shouldPrompt(record, 100L, 1_000L + ManualDeadlinePromptPolicy.DAY_MS - 1L))
        assertTrue(ManualDeadlinePromptPolicy.shouldPrompt(record, 100L, 1_000L + ManualDeadlinePromptPolicy.DAY_MS))
    }

    @Test
    fun changingDeadlineInvalidatesOldSnooze() {
        val record = ManualDeadlinePromptPolicy.snoozed(100L, 1_000L)
        assertTrue(ManualDeadlinePromptPolicy.shouldPrompt(record, 200L, 1_001L))
    }
}
