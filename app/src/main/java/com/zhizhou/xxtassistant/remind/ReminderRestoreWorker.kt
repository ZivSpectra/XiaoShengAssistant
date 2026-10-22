package com.zhizhou.xxtassistant.remind

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.DeadlineOverrideStore
import com.zhizhou.xxtassistant.data.HiddenStore
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.data.HomeworkCache
import com.zhizhou.xxtassistant.data.LocalCompletionStore
import com.zhizhou.xxtassistant.data.ManualHomeworkStore
import com.zhizhou.xxtassistant.data.CourseScheduleStore

/** Rebuilds alarms on WorkManager's background executor after system changes. */
class ReminderRestoreWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val expectedAccountId = inputData.getString(KEY_EXPECTED_ACCOUNT_ID)
        val accountId = AccountManager(context).getActiveUid()
        if (accountId.isNullOrBlank()) {
            ReminderScheduler.cancelAll(context)
            CourseReminderScheduler.cancelAll(context)
            return Result.success()
        }
        if (!ReminderRecoveryPolicy.shouldRestore(expectedAccountId, accountId)) {
            return Result.success()
        }
        val overrides = DeadlineOverrideStore(context)
        val completion = LocalCompletionStore(context)
        val hidden = HiddenStore(context)
        val homeworks = (HomeworkCache(context).load(accountId) + ManualHomeworkStore(context).list(accountId))
            .map(overrides::apply)
            .map { homework ->
                if (!homework.isManual() && completion.isCompleted(accountId, homework)) {
                    homework.copy(status = Homework.DONE)
                } else homework
            }
            .filterNot { hidden.isPermanentlyHidden(accountId, it) }
            .filter { it.isUnfinished() }
        ReminderScheduler.rescheduleForAccount(context, accountId, homeworks)
        if (AccountManager(context).getActiveUid() == accountId) {
            CourseReminderScheduler.reschedule(context, accountId, CourseScheduleStore(context).list(accountId))
        }
        return Result.success()
    }

    companion object {
        const val KEY_EXPECTED_ACCOUNT_ID = "expected_account_id"
    }
}
