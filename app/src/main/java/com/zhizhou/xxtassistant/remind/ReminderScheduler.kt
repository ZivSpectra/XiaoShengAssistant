package com.zhizhou.xxtassistant.remind

import android.annotation.SuppressLint
import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.util.DebugLog
import java.util.concurrent.TimeUnit

/**
 * 提醒调度器：把每条未完成作业的每个提醒节点，登记为可唤醒的 AlarmManager 闹钟，
 * 在系统拒绝登记时再回退到 WorkManager。
 *
 * 原理：
 * 假设作业截止时间是 T，提醒节点是"提前 30 分钟"，
 * 那么就在 (T - 30分钟) 这个时刻安排一个一次性任务，到点发通知。
 * 每次抓取数据后重新调度（先清掉旧的再建新的），保证不过期、不重复。
 */
@SuppressLint("ApplySharedPref")
object ReminderScheduler {

    /** 默认提醒节点（分钟）：截止前 1 天、3 小时、30 分钟 */
    val DEFAULT_NODES_MIN = intArrayOf(24 * 60, 3 * 60, 30)

    private const val PREFS_KEY = "remind_nodes_min"
    private const val EXACT_KEY = "exact_alarm_mode"
    private const val ALARM_IDS_KEY = "alarm_ids"
    private const val ALARM_TOKENS_KEY = "alarm_tokens"
    private const val LEGACY_EXACT_IDS_KEY = "exact_alarm_ids"
    private const val WORK_PREFIX = "deadline_"
    /** Serializes cancel-and-rebuild so late callbacks cannot erase a newer schedule. */
    private val scheduleLock = Any()

    /** 读取用户设置的提醒节点（分钟数数组）；没设置过就用默认 */
    fun loadNodes(context: Context): IntArray {
        val raw = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
            .getString(PREFS_KEY, null) ?: return DEFAULT_NODES_MIN
        if (raw.trim().isEmpty()) return intArrayOf()
        val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..(7 * 24 * 60) }
            .distinct()
            .sorted()
        return if (parsed.isEmpty()) DEFAULT_NODES_MIN else parsed.toIntArray()
    }

    /** 保存用户设置的提醒节点 */
    fun saveNodes(context: Context, nodesMin: IntArray) {
        context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY, nodesMin.joinToString(","))
            // Reminder nodes must survive an immediate task swipe or reboot;
            // this one small preference write is intentionally synchronous.
            .commit()
    }

    fun isExactMode(context: Context): Boolean = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
        .getBoolean(EXACT_KEY, false)

    @SuppressLint("ApplySharedPref")
    fun saveExactMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE).edit()
            // 这是一个会影响下一次后台调度的开关。同步提交，确保用户刚切换后
            // 立即划掉最近任务或进入系统设置时，状态不会还停留在内存队列里。
            .putBoolean(EXACT_KEY, enabled).commit()
    }

    /** 重新调度：清掉旧任务，为所有未完成作业的每个节点建新任务 */
    fun reschedule(context: Context, homeworks: List<Homework>) =
        rescheduleInternal(context, expectedAccountId = null, homeworks = homeworks)

    /** Account-bound variant for delayed UI work; a stale snapshot cannot clear another account's alarms. */
    fun rescheduleForAccount(context: Context, accountId: String, homeworks: List<Homework>) {
        if (accountId.isBlank()) return
        rescheduleInternal(context, expectedAccountId = accountId, homeworks = homeworks)
    }

    private fun rescheduleInternal(context: Context, expectedAccountId: String?, homeworks: List<Homework>) {
        synchronized(scheduleLock) {
            val workManager = WorkManager.getInstance(context)
            val nodes = loadNodes(context)
            val now = System.currentTimeMillis()
            val activeUid = AccountManager(context).getActiveUid()
            if (expectedAccountId != null && activeUid != expectedAccountId) return
            if (activeUid.isNullOrBlank()) {
                workManager.cancelAllWorkByTag(WORK_PREFIX)
                cancelAlarmsLocked(context)
                return
            }
            val scopedHomeworks = homeworks.filter { it.accountId == activeUid }

            // 1) 清掉旧任务（包括正在等待的）。闹钟和 WorkManager 都要清理，
            //    否则切换提醒节点后可能收到旧节点的重复通知。
            workManager.cancelAllWorkByTag(WORK_PREFIX)
            cancelAlarmsLocked(context)

            // 2) 先计算整批候选令牌并同步保存，再逐条登记闹钟。这样
            //    中途被系统杀死时仍能清理已尝试登记的 PendingIntent，
            //    同时避免在主线程循环中反复 commit 造成卡顿。
            val alarmTokens = mutableSetOf<String>()
            scopedHomeworks.forEach homeworkLoop@{ hw ->
                if (!hw.isUnfinished() || hw.endTime <= now) return@homeworkLoop
                nodes.forEach nodeLoop@{ minutes ->
                    if (hw.endTime - minutes * 60_000L <= now) return@nodeLoop
                    alarmTokens += hw.reminderToken(minutes)
                }
            }
            persistAlarmTokens(context, alarmTokens)
            var scheduled = 0
            for (hw in scopedHomeworks) {
                if (!hw.isUnfinished()) continue          // 只提醒未完成的
                if (hw.endTime <= now) continue           // 已过期的跳过
                for (minutes in nodes) {
                    val triggerAt = hw.endTime - minutes * 60_000L
                    if (triggerAt <= now) continue        // 触发时刻已过去就跳过
                    val token = hw.reminderToken(minutes)

                    val data = Data.Builder()
                        .putString(ReminderWorker.KEY_TITLE, hw.title)
                        .putString(ReminderWorker.KEY_COURSE, hw.courseName)
                        .putLong(ReminderWorker.KEY_MINUTES_LEFT, minutes.toLong())
                        .putLong(ReminderWorker.KEY_END_TIME, hw.endTime)
                        // URLs may contain session-like query values. Resolve
                        // the URL from the local cache only when the reminder
                        // fires instead of persisting it in WorkManager's DB.
                        .putString(ReminderWorker.KEY_URL, "")
                        .putString(ReminderWorker.KEY_ACCOUNT_ID, hw.accountId)
                        .putString(ReminderWorker.KEY_TOKEN, token)
                        .build()

                    val exactRequested = isExactMode(context)
                    val exactAvailable = ReminderAlarmPolicy.useAlarmClock(
                        exactModeEnabled = exactRequested,
                        exactPermissionGranted = canScheduleExact(context)
                    )
                    val alarmScheduled = scheduleAlarm(
                        context = context,
                        hw = hw,
                        minutes = minutes,
                        triggerAt = triggerAt,
                        exact = exactAvailable
                    )
                    // Keep a durable backup even when AlarmManager accepts the
                    // request. Some vendor ROMs report success but drop the
                    // alarm after the app is swiped from recents. The delivery
                    // store in ReminderNotifier suppresses duplicate sound.
                    val backupQueued = enqueueWorkBackup(
                        context = context,
                        workManager = workManager,
                        hw = hw,
                        minutes = minutes,
                        triggerAt = triggerAt,
                        data = data
                    )
                    if (!alarmScheduled && backupQueued) {
                        DebugLog.log(context, "[REMINDER] 闹钟登记失败，已登记 WorkManager 兜底")
                    } else if (!alarmScheduled && !backupQueued) {
                        DebugLog.log(context, "[REMINDER] 闹钟和 WorkManager 兜底均登记失败")
                    } else if (exactRequested && !exactAvailable) {
                        DebugLog.log(context, "[REMINDER] 精确闹钟权限未生效，使用非精确闹钟")
                    }
                    scheduled++
                }
            }
            if (AccountManager(context).getActiveUid() != activeUid ||
                (expectedAccountId != null && AccountManager(context).getActiveUid() != expectedAccountId)
            ) {
                // Logout/account switch raced this rebuild. Do not publish a
                // stale token set or leave old-account alarms behind.
                workManager.cancelAllWorkByTag(WORK_PREFIX)
                cancelAlarmsLocked(context, alarmTokens)
                return
            }
            context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE).edit()
                .putStringSet(ALARM_TOKENS_KEY, alarmTokens).commit()
            DebugLog.log(context, "[REMINDER] 调度完成：共 $scheduled 个定时任务，精确模式=${isExactMode(context)}，权限=${canScheduleExact(context)}")
        }
    }

    /** 清空所有提醒任务（比如退出登录/删除账号时） */
    fun cancelAll(context: Context) {
        synchronized(scheduleLock) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_PREFIX)
            cancelAlarmsLocked(context)
        }
    }

    /** 单账号模式退出登录时暂停提醒；重新登录后由主页根据缓存重新安排。 */
    fun pauseAccount(context: Context, @Suppress("UNUSED_PARAMETER") accountId: String) {
        cancelAll(context)
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(AlarmManager::class.java)
        return alarm?.canScheduleExactAlarms() == true
    }

    private fun scheduleAlarm(
        context: Context,
        hw: Homework,
        minutes: Int,
        triggerAt: Long,
        exact: Boolean
    ): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        // 不把 32 位 hash 当作 PendingIntent 的唯一身份：不同作业理论上可能碰撞，
        // 碰撞会让 Android 认为是同一个闹钟并覆盖前一条。数据 URI 参与 PendingIntent
        // 身份比较，完整保留作业键，requestCode 固定为 0 即可。
        val token = "${hw.accountId}:${hw.courseKey}:${hw.id}:$minutes"
        val intent = Intent(context, ExactReminderReceiver::class.java).apply {
            data = Uri.parse("xxt://reminder/${Uri.encode(token)}")
            putExtra(EXTRA_TITLE, hw.title)
            putExtra(EXTRA_COURSE, hw.courseName)
            putExtra(EXTRA_MINUTES, minutes.toLong())
            putExtra(EXTRA_END_TIME, hw.endTime)
            putExtra(EXTRA_URL, "")
            putExtra(EXTRA_ACCOUNT, hw.accountId)
            putExtra(EXTRA_TOKEN, token)
        }
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (exact) {
                // AlarmClock alarms are the most resilient local mechanism
                // for a user-visible reminder: they can wake through Doze and
                // survive the app process being removed from recents better
                // than a regular exact alarm. The reminder broadcast remains
                // the delivery target; showIntent only provides the system's
                // alarm affordance when the user taps the alarm entry.
                val showIntent = PendingIntent.getActivity(
                    context,
                    ("show:" + token).hashCode(),
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
        } catch (_: SecurityException) {
            return false
        }
        return true
    }

    private fun enqueueWorkBackup(
        context: Context,
        workManager: WorkManager,
        hw: Homework,
        minutes: Int,
        triggerAt: Long,
        data: Data
    ): Boolean {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay((triggerAt - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(WORK_PREFIX)
            .setInputData(data)
            .build()
        return runCatching {
            workManager.enqueueUniqueWork(
                "$WORK_PREFIX${hw.accountId}_${hw.courseKey}_${hw.id}_${minutes}m_backup",
                ExistingWorkPolicy.REPLACE,
                request
            )
            true
        }.getOrElse {
            DebugLog.log(context, "[REMINDER] WorkManager 兜底登记失败：${it.javaClass.simpleName}")
            false
        }
    }

    private fun cancelAlarmsLocked(context: Context) {
        val prefs = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
        // 新版按完整 token 取消，避免 hash 碰撞；保留旧版数字 ID 清理，保证升级后
        // 已登记的旧闹钟不会继续发出幽灵提醒。
        val tokens = prefs.getStringSet(ALARM_TOKENS_KEY, emptySet()) ?: emptySet()
        cancelAlarmsLocked(context, tokens)
    }

    private fun cancelAlarmsLocked(context: Context, tokens: Set<String>) {
        val prefs = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        tokens.forEach { token ->
            val intent = Intent(context, ExactReminderReceiver::class.java).apply {
                data = Uri.parse("xxt://reminder/${Uri.encode(token)}")
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                alarm.cancel(pending)
                pending.cancel()
            }
        }
        val ids = (prefs.getStringSet(ALARM_IDS_KEY, emptySet()) ?: emptySet()) +
            (prefs.getStringSet(LEGACY_EXACT_IDS_KEY, emptySet()) ?: emptySet())
        ids.mapNotNull { it.toIntOrNull() }.forEach { id ->
            val intent = Intent(context, ExactReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                alarm.cancel(pending)
                pending.cancel()
            }
        }
        prefs.edit()
            .remove(ALARM_TOKENS_KEY)
            .remove(ALARM_IDS_KEY)
            .remove(LEGACY_EXACT_IDS_KEY)
            .commit()
    }

    private fun persistAlarmTokens(context: Context, tokens: Set<String>) {
        context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
            .edit()
            .putStringSet(ALARM_TOKENS_KEY, tokens.toSet())
            .commit()
    }

    const val EXTRA_TITLE = "title"
    const val EXTRA_COURSE = "course"
    const val EXTRA_MINUTES = "minutes_left"
    const val EXTRA_END_TIME = "end_time"
    const val EXTRA_URL = "url"
    const val EXTRA_ACCOUNT = "account_id"
    const val EXTRA_TOKEN = "reminder_token"

}
