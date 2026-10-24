package com.zhizhou.xxtassistant.remind

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import com.zhizhou.xxtassistant.util.DebugLog

/** Rebuilds course alarms after a course or bell-time change survives a process kill. */
class CourseReminderRestoreWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val accountId = AccountManager(context).getActiveUid()
        val expectedAccountId = inputData.getString(ReminderRestoreWorker.KEY_EXPECTED_ACCOUNT_ID)
        if (accountId.isNullOrBlank() || !ReminderRecoveryPolicy.shouldRestore(expectedAccountId, accountId)) {
            return Result.success()
        }
        return runCatching {
            CourseReminderScheduler.reschedule(context, accountId, CourseScheduleStore(context).list(accountId))
            Result.success()
        }.getOrElse {
            DebugLog.log(context, "课程提醒后台重建失败：${it.javaClass.simpleName}")
            Result.retry()
        }
    }
}
