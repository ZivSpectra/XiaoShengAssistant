package com.zhizhou.xxtassistant.remind

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CoursePeriod
import com.zhizhou.xxtassistant.data.CoursePeriodStore
import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import com.zhizhou.xxtassistant.data.CourseTableSettingsStore
import com.zhizhou.xxtassistant.util.DebugLog
import java.util.Calendar

/** 课程表提醒：课前 30 分钟显示，用户确认前持续保留。 */
@SuppressLint("ApplySharedPref")
object CourseReminderScheduler {
    const val ACTION_SHOW = "com.zhizhou.xxtassistant.course.SHOW"
    const val ACTION_CANCEL = "com.zhizhou.xxtassistant.course.CANCEL"
    const val ACTION_DISMISS = "com.zhizhou.xxtassistant.course.DISMISS"
    const val ACTION_PREP = "com.zhizhou.xxtassistant.course.PREP"
    const val ACTION_MAINTAIN = "com.zhizhou.xxtassistant.course.MAINTAIN"
    const val EXTRA_ACCOUNT = "accountId"
    const val EXTRA_COURSE_ID = "courseId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ROOM = "room"
    const val EXTRA_START = "startAt"
    const val EXTRA_END = "endAt"
    const val EXTRA_TARGET_DAY = "targetDay"
    const val EXTRA_NOTIFICATION_ID = "notificationId"

    private const val PREFS = "xxt_course_reminder"
    private const val TOKENS = "tokens"
    private const val DAYS_AHEAD = 14
    internal const val WORK_TAG = "course_reminder"
    /**
     * AlarmManager state and the token snapshot must be changed as one unit.
     * Receivers can arrive while an Activity is rebuilding the rolling window;
     * without this lock one rebuild can cancel another rebuild's alarms.
     */
    private val scheduleLock = Any()
    data class ScheduleResult(
        val scheduled: Int,
        val failed: Int,
        val windowEndAt: Long,
        /** Entries queued through WorkManager after AlarmManager rejected them. */
        val deferred: Int = 0
    )

    private data class AlarmRegistration(
        val alarmScheduled: Boolean,
        val fallbackScheduled: Boolean
    )

    /** Rebuilds a rolling window. The receiver calls this after each event. */
    fun reschedule(context: Context, accountId: String, schedules: List<CourseSchedule>): ScheduleResult {
        synchronized(scheduleLock) {
            // A delayed receiver from an old account must never rebuild the
            // active account's alarms. The final check below covers an account
            // switch that happens while this rebuild is in progress.
            if (AccountManager(context).getActiveUid() != accountId) {
                return ScheduleResult(0, 0, System.currentTimeMillis())
            }
            cancelAlarmsLocked(context)
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            val now = System.currentTimeMillis()
            val periods = CoursePeriodStore(context).list(accountId)
            val periodStore = CoursePeriodStore(context)
            val tableSettings = CourseTableSettingsStore(context)
            data class AlarmSpec(
                val action: String,
                val token: String,
                val triggerAt: Long,
                val fill: (Intent) -> Unit
            )
            val specs = mutableListOf<AlarmSpec>()
            for (offset in 0..DAYS_AHEAD) {
            val day = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val weekday = ((day.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            schedules.filter {
                    it.accountId == accountId && it.weekday == weekday &&
                        hasReliableWeekStart(it, tableSettings) &&
                        periodStore.hasConfiguredPeriods(accountId, setOf(it.startPeriod, it.endPeriod)) &&
                        occursOnDate(it, day.timeInMillis, tableSettings)
            }
                .forEach { schedule ->
                    val startAt = atPeriodStart(day, schedule.startPeriod, periods)
                    val endAt = atPeriodEnd(day, schedule.endPeriod, periods)
                    val showAt = startAt - 30 * 60_000L
                    if (showAt > now) {
                        val token = "show:${accountId}:${schedule.id}:$offset"
                        specs += AlarmSpec(ACTION_SHOW, token, showAt) { intent ->
                            intent.putExtra(EXTRA_ACCOUNT, accountId)
                                .putExtra(EXTRA_COURSE_ID, schedule.id)
                                .putExtra(EXTRA_TITLE, schedule.name)
                                .putExtra(EXTRA_ROOM, schedule.room)
                                .putExtra(EXTRA_START, startAt)
                                .putExtra(EXTRA_END, endAt)
                        }
                    }
                }
                val target = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                val nextDayCourses = schedules.filter {
                    it.accountId == accountId &&
                        it.weekday == (((target.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1) &&
                        hasReliableWeekStart(it, tableSettings) &&
                        occursOnDate(it, target.timeInMillis, tableSettings)
                }
                if (nextDayCourses.isNotEmpty()) {
                    val prepAt = (day.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, tableSettings.preparationHour(accountId))
                        set(Calendar.MINUTE, tableSettings.preparationMinute(accountId))
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    if (prepAt > now) {
                        val token = "prep:${accountId}:${day.timeInMillis}"
                        specs += AlarmSpec(ACTION_PREP, token, prepAt) { intent ->
                            intent.putExtra(EXTRA_ACCOUNT, accountId).putExtra(EXTRA_TARGET_DAY, target.timeInMillis)
                        }
                    }
                }
            }
            // If the next two weeks are a holiday, no SHOW/PREP alarm may
            // fire to extend the window. A lightweight maintenance alarm closes
            // that gap without waking the process every day.
            val maintenanceAt = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, DAYS_AHEAD)
            }.timeInMillis
            if (maintenanceAt > now && specs.none { it.action == ACTION_MAINTAIN }) {
                val token = "maintain:${accountId}:${maintenanceAt}"
                specs += AlarmSpec(ACTION_MAINTAIN, token, maintenanceAt) { intent ->
                    intent.putExtra(EXTRA_ACCOUNT, accountId)
                }
            }

            // Persist the complete candidate set before touching AlarmManager.
            // If the process is killed halfway through registration, the next
            // rebuild can still cancel every PendingIntent it may have made.
            val tokens = specs.mapTo(mutableSetOf()) { it.token }
            persistTokens(context, tokens)
            var scheduledCount = 0
            var deferredCount = 0
            var failedCount = 0
            specs.forEach { spec ->
                when (scheduleAlarm(context, spec.action, spec.token, spec.triggerAt, spec.fill)) {
                    AlarmRegistration(true, false) -> scheduledCount++
                    AlarmRegistration(false, true) -> deferredCount++
                    else -> failedCount++
                }
            }

            // Account changes are serialized by the public cancel/reschedule
            // entry points. If a change still happened through another path,
            // remove this stale rebuild before it can publish its token set.
            if (AccountManager(context).getActiveUid() != accountId) {
                cancelAlarmsLocked(context, tokens)
                return ScheduleResult(0, failedCount, now, deferredCount)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(TOKENS, tokens).commit()
            if (failedCount > 0 || deferredCount > 0) {
                DebugLog.log(
                    context,
                    "课程提醒登记：AlarmManager=$scheduledCount，后台兜底=$deferredCount，彻底失败=$failedCount"
                )
            }
            return ScheduleResult(
                scheduled = scheduledCount,
                failed = failedCount,
                windowEndAt = now + (DAYS_AHEAD + 1L) * 24 * 60 * 60_000L,
                deferred = deferredCount
            )
        }
    }

    fun rescheduleAll(context: Context) {
        val accountId = AccountManager(context).getActiveUid()
        if (accountId.isNullOrBlank()) {
            cancelAll(context, clearNotifications = true)
            return
        }
        reschedule(context, accountId, CourseScheduleStore(context).list(accountId))
    }

    internal fun coursesForDate(
        context: Context,
        accountId: String,
        dayMillis: Long,
        schedules: List<CourseSchedule> = CourseScheduleStore(context).list(accountId)
    ): List<CourseSchedule> {
        val day = Calendar.getInstance().apply {
            timeInMillis = dayMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val weekday = ((day.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val settings = CourseTableSettingsStore(context)
        return schedules.filter { schedule ->
            schedule.accountId == accountId && schedule.weekday == weekday &&
                hasReliableWeekStart(schedule, settings) && occursOnDate(schedule, day.timeInMillis, settings)
        }
    }

    internal fun isOccurrenceCurrent(context: Context, accountId: String, courseId: String, startAt: Long): Boolean {
        if (AccountManager(context).getActiveUid() != accountId) return false
        val periodStore = CoursePeriodStore(context)
        val schedule = CourseScheduleStore(context).list(accountId).firstOrNull { it.id == courseId } ?: return false
        val day = Calendar.getInstance().apply {
            timeInMillis = startAt
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val settings = CourseTableSettingsStore(context)
        val weekday = ((day.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        return schedule.weekday == weekday && hasReliableWeekStart(schedule, settings) &&
            periodStore.hasConfiguredPeriods(accountId, setOf(schedule.startPeriod, schedule.endPeriod)) &&
            occursOnDate(schedule, day.timeInMillis, settings) &&
            atPeriodStart(day, schedule.startPeriod, periodStore.list(accountId)) == startAt
    }

    /** Cancel alarms and, for logout/account removal, only this app's course notifications. */
    fun cancelAll(context: Context, clearNotifications: Boolean = true) {
        synchronized(scheduleLock) {
            cancelAlarmsLocked(context)
            if (clearNotifications) {
                CourseNotification.clearForAccount(context, AccountManager(context).getActiveUid())
            }
        }
    }

    private fun cancelAlarmsLocked(context: Context) {
        val tokens = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(TOKENS, emptySet()).orEmpty().toSet()
        cancelAlarmsLocked(context, tokens)
    }

    private fun cancelAlarmsLocked(context: Context, tokens: Set<String>) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (alarm != null) {
            tokens.forEach { token ->
                listOf(ACTION_SHOW, ACTION_CANCEL, ACTION_PREP, ACTION_MAINTAIN).forEach { action ->
                    val intent = Intent(context, CourseReminderReceiver::class.java).apply {
                        this.action = action
                        data = Uri.parse("xxt://course-reminder/${Uri.encode(token)}")
                    }
                    val pending = PendingIntent.getBroadcast(
                        context, 0, intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pending != null) {
                        alarm.cancel(pending)
                        pending.cancel()
                    }
                }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKENS).commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }

    private fun persistTokens(context: Context, tokens: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(TOKENS, tokens.toSet())
            .commit()
    }

    /** 手动课程没有学期文字时按全年有效；导入课程按其教学周范围过滤。 */
    private fun occursOnDate(
        schedule: CourseSchedule,
        day: Long,
        tableSettings: CourseTableSettingsStore
    ): Boolean {
        if (schedule.term.isBlank() || !Regex("20\\d{2}").containsMatchIn(schedule.term)) return true
        val week = tableSettings.teachingWeek(schedule.accountId, schedule.term, day)
        return week != null && schedule.occursInWeek(week)
    }

    private fun hasReliableWeekStart(schedule: CourseSchedule, settings: CourseTableSettingsStore): Boolean {
        val hasAcademicYear = Regex("20\\d{2}").containsMatchIn(schedule.term)
        return !hasAcademicYear || settings.hasTermStartDate(schedule.accountId, schedule.term)
    }

    private fun atPeriodStart(day: Calendar, period: Int, periods: List<CoursePeriod>): Long {
        val minutes = periods.firstOrNull { it.period == period }?.startMinute
            ?: fallbackPeriodStart(period)
        return (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun atPeriodEnd(day: Calendar, period: Int, periods: List<CoursePeriod>): Long {
        val minutes = periods.firstOrNull { it.period == period }?.endMinute
            ?: (fallbackPeriodStart(period) + 45)
        return (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Defensive fallback for old callers; scheduling filters missing periods out. */
    private fun fallbackPeriodStart(period: Int): Int {
        val safePeriod = period.coerceAtLeast(1)
        val base = if (safePeriod <= 4) 8 * 60 else if (safePeriod <= 8) 14 * 60 else 19 * 60
        return base + ((safePeriod - 1) % 4) * 55
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleAlarm(
        context: Context,
        action: String,
        token: String,
        triggerAt: Long,
        fill: (Intent) -> Unit
    ): AlarmRegistration {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: run {
            val fallback = scheduleWorkFallback(context, action, token, triggerAt, Intent().apply { fill(this) })
            return AlarmRegistration(alarmScheduled = false, fallbackScheduled = fallback)
        }
        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("xxt://course-reminder/${Uri.encode(token)}")
        }
        fill(intent)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            if (ReminderAlarmPolicy.useAlarmClock(
                    exactModeEnabled = ReminderScheduler.isExactMode(context),
                    exactPermissionGranted = ReminderScheduler.canScheduleExact(context)
                )) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    ("show-course:" + token).hashCode(),
                    Intent(context, com.zhizhou.xxtassistant.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarm.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                    pending
                )
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            AlarmRegistration(alarmScheduled = true, fallbackScheduled = false)
        }.getOrElse {
            DebugLog.log(context, "课程提醒登记失败：${it.javaClass.simpleName}")
            AlarmRegistration(
                alarmScheduled = false,
                fallbackScheduled = scheduleWorkFallback(context, action, token, triggerAt, intent)
            )
        }
    }

    private fun scheduleWorkFallback(
        context: Context,
        action: String,
        token: String,
        triggerAt: Long,
        source: Intent
    ): Boolean {
        val data = Data.Builder()
            .putString(CourseReminderWorker.KEY_ACTION, action)
            .putString(CourseReminderWorker.KEY_TOKEN, token)
            .putString(EXTRA_ACCOUNT, source.getStringExtra(EXTRA_ACCOUNT).orEmpty())
            .putString(EXTRA_COURSE_ID, source.getStringExtra(EXTRA_COURSE_ID).orEmpty())
            .putString(EXTRA_TITLE, source.getStringExtra(EXTRA_TITLE).orEmpty())
            .putString(EXTRA_ROOM, source.getStringExtra(EXTRA_ROOM).orEmpty())
            .putLong(EXTRA_START, source.getLongExtra(EXTRA_START, 0L))
            .putLong(EXTRA_END, source.getLongExtra(EXTRA_END, 0L))
            .putLong(EXTRA_TARGET_DAY, source.getLongExtra(EXTRA_TARGET_DAY, 0L))
            .build()
        val request = OneTimeWorkRequestBuilder<CourseReminderWorker>()
            .setInitialDelay((triggerAt - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .setInputData(data)
            .build()
        return runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_TAG:$token",
                ExistingWorkPolicy.REPLACE,
                request
            )
            true
        }.getOrElse {
            DebugLog.log(context, "课程提醒后台兜底登记失败：${it.javaClass.simpleName}")
            false
        }
    }
}
