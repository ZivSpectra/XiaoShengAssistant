package com.zhizhou.xxtassistant.remind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRecoveryPolicyTest {
    @Test
    fun manualHomeworkChangeCreatesStableDurableRestoreRequestForActiveAccount() {
        val first = ReminderRecoveryPolicy.homeworkChange("account-1", "account-1")
        val afterProcessRestart = ReminderRecoveryPolicy.homeworkChange("account-1", "account-1")

        assertEquals(
            ReminderRecoveryPolicy.Request(
                ReminderRecoveryPolicy.HOMEWORK_REBUILD_WORK_NAME,
                "account-1"
            ),
            first
        )
        assertEquals(first, afterProcessRestart)
        assertTrue(ReminderRecoveryPolicy.shouldRestore(first?.expectedAccountId, "account-1"))
    }

    @Test
    fun staleOrBlankAccountDoesNotCreateOrRunAnotherAccountsRebuild() {
        assertEquals(null, ReminderRecoveryPolicy.homeworkChange("account-1", "account-2"))
        assertEquals(null, ReminderRecoveryPolicy.homeworkChange("", "account-1"))
        assertEquals(null, ReminderRecoveryPolicy.homeworkChange("account-1", null))
        assertFalse(ReminderRecoveryPolicy.shouldRestore("account-1", "account-2"))
        assertTrue(ReminderRecoveryPolicy.shouldRestore(null, "account-2"))
    }
}
