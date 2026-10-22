package com.zhizhou.xxtassistant.remind

/** The permission page that the user most recently opened. */
enum class ReminderPermissionStep {
    NOTIFICATIONS,
    EXACT_ALARMS,
    BATTERY_OPTIMIZATION
}

/** Notification scope used for the return confirmation after system settings. */
enum class ReminderNotificationTarget {
    HOMEWORK_CHANNEL,
    COURSE_CHANNEL,
    ALL_NOTIFICATIONS
}

data class ReminderPermissionState(
    val notificationsAllowed: Boolean,
    val homeworkChannelNeedsAttention: Boolean,
    val courseChannelNeedsAttention: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean
)

/**
 * Snapshot of the settings that Android exposes for one notification channel.
 *
 * Android does not expose a separate "floating" permission. On Android 8+
 * the pop-up behaviour is derived from the channel importance, while lock
 * screen visibility is controlled by the channel visibility plus the device's
 * global lock-screen policy. Keeping these values separate prevents the UI
 * from reporting a perfectly usable lock-screen notification as broken just
 * because the user chose not to enable pop-ups or sound.
 */
data class ReminderChannelState(
    val exists: Boolean,
    val importance: Int,
    val soundEnabled: Boolean,
    val lockscreenVisibility: Int
)

/** Human-readable, testable result for one channel's permission diagnostics. */
data class ReminderChannelDiagnostics(
    val exists: Boolean,
    val notificationEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val floatingEnabled: Boolean,
    val soundEnabled: Boolean
) {
    /** The settings required for an ordinary visible reminder. */
    val requiredReady: Boolean
        get() = exists && notificationEnabled && lockscreenEnabled
}

/**
 * Pure decision logic for the permission onboarding confirmation.
 *
 * The target matters for notifications: returning from the homework channel
 * must not fail merely because the course channel still needs configuration.
 */
object ReminderPermissionPolicy {
    // Values mirror NotificationManager/NotificationChannel constants. Keeping
    // them here avoids Android framework types in the pure policy tests.
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3
    const val IMPORTANCE_HIGH = 4
    const val IMPORTANCE_NONE = 0
    const val VISIBILITY_SECRET = -1

    /**
     * Derive independently visible channel states. Sound and pop-up alerts
     * are intentionally optional: a user may want a quiet lock-screen reminder.
     */
    fun diagnoseChannel(state: ReminderChannelState): ReminderChannelDiagnostics {
        val notificationEnabled = state.exists && state.importance >= IMPORTANCE_LOW
        val lockscreenEnabled = state.exists &&
            state.lockscreenVisibility != VISIBILITY_SECRET
        val floatingEnabled = state.exists && state.importance >= IMPORTANCE_HIGH
        return ReminderChannelDiagnostics(
            exists = state.exists,
            notificationEnabled = notificationEnabled,
            lockscreenEnabled = lockscreenEnabled,
            floatingEnabled = floatingEnabled,
            soundEnabled = state.exists && state.soundEnabled
        )
    }

    fun isReady(
        step: ReminderPermissionStep,
        state: ReminderPermissionState,
        target: ReminderNotificationTarget = ReminderNotificationTarget.ALL_NOTIFICATIONS
    ): Boolean = when (step) {
        ReminderPermissionStep.NOTIFICATIONS ->
            state.notificationsAllowed && when (target) {
                ReminderNotificationTarget.HOMEWORK_CHANNEL ->
                    !state.homeworkChannelNeedsAttention
                ReminderNotificationTarget.COURSE_CHANNEL ->
                    !state.courseChannelNeedsAttention
                ReminderNotificationTarget.ALL_NOTIFICATIONS ->
                    !state.homeworkChannelNeedsAttention &&
                        !state.courseChannelNeedsAttention
            }

        ReminderPermissionStep.EXACT_ALARMS -> state.exactAlarmsAllowed
        ReminderPermissionStep.BATTERY_OPTIMIZATION -> state.batteryOptimizationIgnored
    }
}
