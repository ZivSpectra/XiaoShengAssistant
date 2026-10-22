package com.zhizhou.xxtassistant.remind

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseScheduleStore

/** Inexact fallback for course alarms rejected by the device/permission state. */
class CourseReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val accountId = inputData.getString(CourseReminderScheduler.EXTRA_ACCOUNT).orEmpty()
        if (accountId.isBlank() || AccountManager(context).getActiveUid() != accountId) return Result.success()

        when (inputData.getString(KEY_ACTION)) {
            CourseReminderScheduler.ACTION_SHOW -> CourseNotification.notifyCourse(
                context = context,
                accountId = accountId,
                id = courseNotificationId(
                    accountId,
                    inputData.getString(CourseReminderScheduler.EXTRA_COURSE_ID).orEmpty(),
                    inputData.getLong(CourseReminderScheduler.EXTRA_START, 0L)
                ),
                courseId = inputData.getString(CourseReminderScheduler.EXTRA_COURSE_ID).orEmpty(),
                title = inputData.getString(CourseReminderScheduler.EXTRA_TITLE).orEmpty(),
                room = inputData.getString(CourseReminderScheduler.EXTRA_ROOM).orEmpty(),
                startAt = inputData.getLong(CourseReminderScheduler.EXTRA_START, 0L)
            )
            CourseReminderScheduler.ACTION_PREP -> {
                val targetDay = inputData.getLong(CourseReminderScheduler.EXTRA_TARGET_DAY, 0L)
                CourseNotification.notifyPreparation(context, accountId, targetDay)
            }
        }

        if (AccountManager(context).getActiveUid() == accountId) {
            CourseReminderScheduler.reschedule(context, accountId, CourseScheduleStore(context).list(accountId))
        }
        return Result.success()
    }

    companion object {
        const val KEY_ACTION = "course_action"
        const val KEY_TOKEN = "course_token"
    }
}
