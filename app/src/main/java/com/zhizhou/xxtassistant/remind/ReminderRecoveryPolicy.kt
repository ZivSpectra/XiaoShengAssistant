package com.zhizhou.xxtassistant.remind

/**
 * Decides whether an in-process reminder rebuild also needs a durable
 * WorkManager fallback.  Data changes such as adding a manual homework must
 * survive the user swiping the app from recents before the executor runs.
 */
internal object ReminderRecoveryPolicy {
    const val HOMEWORK_REBUILD_WORK_NAME = "rebuild_homework_reminders_after_change"

    data class Request(
        val uniqueWorkName: String,
        val expectedAccountId: String
    )

    fun homeworkChange(requestedAccountId: String, activeAccountId: String?): Request? {
        if (requestedAccountId.isBlank() || requestedAccountId != activeAccountId) return null
        return Request(HOMEWORK_REBUILD_WORK_NAME, requestedAccountId)
    }

    fun shouldRestore(expectedAccountId: String?, activeAccountId: String?): Boolean =
        expectedAccountId.isNullOrBlank() || expectedAccountId == activeAccountId
}
