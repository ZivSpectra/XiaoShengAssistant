package com.zhizhou.xxtassistant.remind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPermissionPolicyTest {
    @Test
    fun channelCanBeReadyWithoutOptionalPopupOrSound() {
        val diagnostics = ReminderPermissionPolicy.diagnoseChannel(
            ReminderChannelState(
                exists = true,
                importance = ReminderPermissionPolicy.IMPORTANCE_DEFAULT,
                soundEnabled = false,
                lockscreenVisibility = 1
            )
        )

        assertTrue(diagnostics.requiredReady)
        assertFalse(diagnostics.floatingEnabled)
        assertFalse(diagnostics.soundEnabled)
    }

    @Test
    fun channelReportsOnlyTheRequiredFailures() {
        val diagnostics = ReminderPermissionPolicy.diagnoseChannel(
            ReminderChannelState(
                exists = true,
                importance = ReminderPermissionPolicy.IMPORTANCE_NONE,
                soundEnabled = true,
                lockscreenVisibility = ReminderPermissionPolicy.VISIBILITY_SECRET
            )
        )

        assertFalse(diagnostics.requiredReady)
        assertFalse(diagnostics.notificationEnabled)
        assertFalse(diagnostics.lockscreenEnabled)
        // Sound is independently reported and must not make a broken channel
        // appear fixed by itself.
        assertTrue(diagnostics.soundEnabled)
    }

    @Test
    fun privateOrDefaultLockscreenVisibilityStillCountsAsVisible() {
        val privateVisibility = ReminderPermissionPolicy.diagnoseChannel(
            ReminderChannelState(
                exists = true,
                importance = ReminderPermissionPolicy.IMPORTANCE_LOW,
                soundEnabled = true,
                lockscreenVisibility = 0
            )
        )
        val systemDefaultVisibility = ReminderPermissionPolicy.diagnoseChannel(
            ReminderChannelState(
                exists = true,
                importance = ReminderPermissionPolicy.IMPORTANCE_LOW,
                soundEnabled = true,
                lockscreenVisibility = -1000
            )
        )

        assertTrue(privateVisibility.requiredReady)
        assertTrue(systemDefaultVisibility.requiredReady)
    }

    @Test
    fun notificationConfirmationChecksOnlyTheChannelOpenedThisTime() {
        val state = ReminderPermissionState(
            notificationsAllowed = true,
            homeworkChannelNeedsAttention = false,
            courseChannelNeedsAttention = true,
            exactAlarmsAllowed = false,
            batteryOptimizationIgnored = false
        )

        assertTrue(
            ReminderPermissionPolicy.isReady(
                ReminderPermissionStep.NOTIFICATIONS,
                state,
                ReminderNotificationTarget.HOMEWORK_CHANNEL
            )
        )
        assertFalse(
            ReminderPermissionPolicy.isReady(
                ReminderPermissionStep.NOTIFICATIONS,
                state,
                ReminderNotificationTarget.ALL_NOTIFICATIONS
            )
        )

        val reverseState = state.copy(
            homeworkChannelNeedsAttention = true,
            courseChannelNeedsAttention = false
        )
        assertTrue(
            ReminderPermissionPolicy.isReady(
                ReminderPermissionStep.NOTIFICATIONS,
                reverseState,
                ReminderNotificationTarget.COURSE_CHANNEL
            )
        )
    }

    @Test
    fun notificationRuntimePermissionIsRequiredForEveryNotificationTarget() {
        val state = ReminderPermissionState(
            notificationsAllowed = false,
            homeworkChannelNeedsAttention = false,
            courseChannelNeedsAttention = false,
            exactAlarmsAllowed = true,
            batteryOptimizationIgnored = true
        )

        assertFalse(
            ReminderPermissionPolicy.isReady(
                ReminderPermissionStep.NOTIFICATIONS,
                state,
                ReminderNotificationTarget.HOMEWORK_CHANNEL
            )
        )
    }

    @Test
    fun exactAlarmAndBatteryConfirmationUseOnlyTheirOwnPermission() {
        val state = ReminderPermissionState(
            notificationsAllowed = false,
            homeworkChannelNeedsAttention = true,
            courseChannelNeedsAttention = true,
            exactAlarmsAllowed = true,
            batteryOptimizationIgnored = false
        )

        assertTrue(
            ReminderPermissionPolicy.isReady(
                ReminderPermissionStep.EXACT_ALARMS,
                state
            )
        )
        assertFalse(
            ReminderPermissionPolicy.isReady(
                ReminderPermissionStep.BATTERY_OPTIMIZATION,
                state
            )
        )
    }
}
