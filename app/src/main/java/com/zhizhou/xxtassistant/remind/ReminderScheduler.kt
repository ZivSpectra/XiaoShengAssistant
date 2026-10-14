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
object ReminderScheduler {

    /** 默认提醒节点（分钟）：截止前 1 天、3 小时、30 分钟 */
    val DEFAULT_NODES_MIN = intArrayOf(24 * 60, 3 * 60, 30)

    private const val PREFS_KEY = "remind_nodes_min"
    private const val EXACT_KEY = "exact_alarm_mode"
    private const val ALARM_IDS_KEY = "alarm_ids"
    private const val ALARM_TOKENS_KEY = "alarm_tokens"
    private const val LEGACY_EXACT_IDS_KEY = "exact_alarm_ids"
    private const val WORK_PREFIX = "deadline_"

    /** 读取用户设置的提醒节点（分钟数数组）；没设置过就用默认 */
    fun loadNodes(context: Context): IntArray {
        val raw = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
            .getString(PREFS_KEY, null) ?: return DEFAULT_NODES_MIN
        if (raw.trim().isEmpty()) return intArrayOf()
        return try {
            raw.split(",").map { it.trim().toInt() }.toIntArray()
        } catch (e: Exception) {
            DEFAULT_NODES_MIN
        }
    }

    /** 保存用户设置的提醒节点 */
    fun saveNodes(context: Context, nodesMin: IntArray) {
        context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY, nodesMin.joinToString(","))
            .apply()
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
    fun reschedule(context: Context, homeworks: List<Homework>) {
        val workManager = WorkManager.getInstance(context)
        val nodes = loadNodes(context)
        val now = System.currentTimeMillis()

        // 1) 清掉旧任务（包括正在等待的）。闹钟和 WorkManager 都要清理，
        //    否则切换提醒节点后可能收到旧节点的重复通知。
        workManager.cancelAllWorkByTag(WORK_PREFIX)
        cancelAlarms(context)

        // 2) 为每条未完成作业建任务
        var scheduled = 0
        for (hw in homeworks) {
            if (!hw.isUnfinished()) continue          // 只提醒未完成的
            if (hw.endTime <= now) continue           // 已过期的跳过
            for (minutes in nodes) {
                val triggerAt = hw.endTime - minutes * 60_000L
                if (triggerAt <= now) continue        // 触发时刻已过去就跳过

                val data = Data.Builder()
                    .putString(ReminderWorker.KEY_TITLE, hw.title)
                    .putString(ReminderWorker.KEY_COURSE, hw.courseName)
                    .putLong(ReminderWorker.KEY_MINUTES_LEFT, minutes.toLong())
                    .putString(ReminderWorker.KEY_URL, hw.url)
                    .putString(ReminderWorker.KEY_ACCOUNT_ID, hw.accountId)
                    .build()

                val exactRequested = isExactMode(context)
                val exactAvailable = exactRequested && canScheduleExact(context)
                val alarmScheduled = scheduleAlarm(
                    context = context,
                    hw = hw,
                    minutes = minutes,
                    triggerAt = triggerAt,
                    exact = exactAvailable
                )
                if (!alarmScheduled) {
                    // 极少数 ROM 可能在权限状态切换的瞬间拒绝 AlarmManager；
                    // 保留 WorkManager 兜底，避免这条提醒完全丢失。
                    val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(triggerAt - now, TimeUnit.MILLISECONDS)
                        .addTag(WORK_PREFIX)
                        .setInputData(data)
                        .build()
                    workManager.enqueueUniqueWork(
                        "$WORK_PREFIX${hw.accountId}_${hw.courseKey}_${hw.id}_${minutes}m",
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                    DebugLog.log(context, "闹钟登记失败，已回退 WorkManager：${hw.title}")
                } else if (exactRequested && !exactAvailable) {
                    DebugLog.log(context, "精确闹钟权限未生效，使用省电可唤醒的非精确闹钟：${hw.title}")
                }
                scheduled++
            }
        }
        DebugLog.log(context, "提醒调度完成：共 $scheduled 个定时任务，精确模式=${isExactMode(context)}，权限=${canScheduleExact(context)}")
    }

    /** 清空所有提醒任务（比如退出登录/删除账号时） */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_PREFIX)
        cancelAlarms(context)
    }

    /** 单账号模式退出登录时暂停提醒；重新登录后由主页根据缓存重新安排。 */
    fun pauseAccount(context: Context, @Suppress("UNUSED_PARAMETER") accountId: String) {
        cancelAll(context)
    }

    /** 调度锁屏测试通知，不能依赖 Activity/Handler，因为用户可能马上划掉后台。 */
    /**
     * 安排测试通知。返回 true 表示使用了精确闹钟，false 表示系统未授权，
     * 使用了可在省电状态唤醒但可能延迟的非精确闹钟。
     */
    fun scheduleTestNotification(context: Context, accountId: String, delayMs: Long = 10_000L): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        val intent = Intent(context, ExactReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, "锁屏测试通知")
            putExtra(EXTRA_COURSE, "测试课程")
            putExtra(EXTRA_MINUTES, 15L)
            putExtra(EXTRA_URL, "")
            putExtra(EXTRA_ACCOUNT, accountId)
        }
        val pending = PendingIntent.getBroadcast(
            context, TEST_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + delayMs
        val exact = canScheduleExact(context)
        try {
            if (exact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            DebugLog.log(context, "测试通知已登记：精确=$exact，延迟=${delayMs}ms")
            return exact
        } catch (e: SecurityException) {
            DebugLog.log(context, "测试通知精确闹钟被系统拒绝：${e.javaClass.simpleName}")
            runCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
            return false
        }
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
            putExtra(EXTRA_URL, hw.url)
            putExtra(EXTRA_ACCOUNT, hw.accountId)
        }
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (exact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            return false
        }
        val prefs = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
        val tokens = prefs.getStringSet(ALARM_TOKENS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        tokens += token
        prefs.edit().putStringSet(ALARM_TOKENS_KEY, tokens).apply()
        return true
    }

    private fun cancelAlarms(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences("xxt_remind", Context.MODE_PRIVATE)
        // 新版按完整 token 取消，避免 hash 碰撞；保留旧版数字 ID 清理，保证升级后
        // 已登记的旧闹钟不会继续发出幽灵提醒。
        val tokens = prefs.getStringSet(ALARM_TOKENS_KEY, emptySet()) ?: emptySet()
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
            .apply()
    }

    const val EXTRA_TITLE = "title"
    const val EXTRA_COURSE = "course"
    const val EXTRA_MINUTES = "minutes_left"
    const val EXTRA_URL = "url"
    const val EXTRA_ACCOUNT = "account_id"

    private const val TEST_REQUEST_CODE = 0x585854
}
