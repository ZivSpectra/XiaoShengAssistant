package com.zhizhou.xxtassistant.remind

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.util.DebugLog
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Keeps alarm rebuilding away from Activity/Compose callbacks. The existing
 * schedulers stay synchronous, but UI entry points always call them here.
 */
object ReminderDispatch {
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "xxt-reminder-scheduler").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = AccountScopedTaskQueue(
        worker = worker,
        callbackExecutor = Executor { command -> mainHandler.post(command) }
    )

    /** Enqueues a rebuild; [homeworks] is evaluated off the UI thread. */
    fun enqueueHomework(
        context: Context,
        accountId: String,
        homeworks: () -> List<Homework>
    ) {
        val appContext = context.applicationContext
        val recoveryRequest = ReminderRecoveryPolicy.homeworkChange(
            requestedAccountId = accountId,
            activeAccountId = AccountManager(appContext).getActiveUid()
        )
        if (recoveryRequest != null) {
            // The in-process queue below keeps normal UI updates responsive,
            // but it disappears when Android kills this process. Persist a
            // second, account-bound rebuild so a manual homework added just
            // before a recents swipe still gets AlarmManager alarms.
            val durableRequest = OneTimeWorkRequestBuilder<ReminderRestoreWorker>()
                // ManualHomeworkStore writes its SharedPreferences on the UI
                // thread. A short durable delay gives that apply() time to
                // reach disk if the process is killed immediately afterward;
                // the in-process rebuild below still happens without delay.
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setInputData(
                    Data.Builder()
                        .putString(ReminderRestoreWorker.KEY_EXPECTED_ACCOUNT_ID, recoveryRequest.expectedAccountId)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    recoveryRequest.uniqueWorkName,
                    ExistingWorkPolicy.REPLACE,
                    durableRequest
                )
            }.onFailure {
                DebugLog.log(
                    appContext,
                    "提醒重建后台任务登记失败：${it.javaClass.simpleName}"
                )
            }
        }
        queue.submit(
            key = "homework",
            isAccountActive = { AccountManager(appContext).getActiveUid() == accountId },
            task = { ReminderScheduler.rescheduleForAccount(appContext, accountId, homeworks()) }
        )
    }

    /** Enqueues a rebuild for the active account, resolving its data in background. */
    fun enqueueAllCourses(context: Context) {
        val appContext = context.applicationContext
        val accountId = AccountManager(appContext).getActiveUid() ?: return
        enqueueCourses(
            context = appContext,
            accountId = accountId,
            schedules = {
                com.zhizhou.xxtassistant.data.CourseScheduleStore(appContext).list(accountId)
            }
        )
    }

    /** Enqueues a course rebuild; both data loading and alarm registration run in background. */
    fun enqueueCourses(
        context: Context,
        accountId: String,
        schedules: () -> List<CourseSchedule>,
        onComplete: ((Result<CourseReminderScheduler.ScheduleResult>) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        queue.submit(
            key = "courses",
            isAccountActive = { AccountManager(appContext).getActiveUid() == accountId },
            task = { CourseReminderScheduler.reschedule(appContext, accountId, schedules()) },
            onComplete = onComplete
        )
    }

    /** Invalidates queued work and cancels alarms after any currently-running rebuild. */
    fun invalidateAndCancel(context: Context, accountId: String?) {
        queue.invalidateAll()
        val appContext = context.applicationContext
        worker.execute {
            // A new login may happen before this serialized cancellation runs.
            val activeAccount = AccountManager(appContext).getActiveUid()
            if (!accountId.isNullOrBlank() && !activeAccount.isNullOrBlank() && activeAccount != accountId) {
                return@execute
            }
            // Never cancel a new account's freshly persisted rebuild request.
            WorkManager.getInstance(appContext)
                .cancelUniqueWork(ReminderRecoveryPolicy.HOMEWORK_REBUILD_WORK_NAME)
            ReminderScheduler.cancelAll(appContext)
            CourseReminderScheduler.cancelAll(appContext, clearNotifications = false)
            if (!accountId.isNullOrBlank()) CourseNotification.clearForAccount(appContext, accountId)
        }
    }
}

/**
 * Serial queue with latest-request-wins semantics per schedule family. Account
 * state is checked on the worker immediately before work; a skipped stale task
 * can never cancel alarms that belong to a newly active account.
 */
internal class AccountScopedTaskQueue(
    private val worker: Executor,
    private val callbackExecutor: Executor
) {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun <T> submit(
        key: String,
        isAccountActive: () -> Boolean,
        task: () -> T,
        onComplete: ((Result<T>) -> Unit)? = null
    ) {
        val generation = generations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        worker.execute {
            val latest = generations[key]?.get() == generation
            if (!latest || !isAccountActive()) return@execute
            val result = runCatching(task)
            if (onComplete != null) {
                callbackExecutor.execute {
                    if (generations[key]?.get() == generation && isAccountActive()) {
                        onComplete(result)
                    }
                }
            }
        }
    }

    fun invalidateAll() {
        generations.values.forEach(AtomicLong::incrementAndGet)
    }
}
