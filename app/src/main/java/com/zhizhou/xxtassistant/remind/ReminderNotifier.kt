package com.zhizhou.xxtassistant.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zhizhou.xxtassistant.HomeworkDetailActivity
import com.zhizhou.xxtassistant.MainActivity
import com.zhizhou.xxtassistant.R
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.HomeworkCache
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.data.DeadlineOverrideStore
import com.zhizhou.xxtassistant.data.HiddenStore
import com.zhizhou.xxtassistant.data.LocalCompletionStore
import com.zhizhou.xxtassistant.data.ManualHomeworkStore
import com.zhizhou.xxtassistant.util.DebugLog

/** 统一构造提醒通知，供 WorkManager 和精确闹钟广播共同使用。 */
object ReminderNotifier {
    // 频道属性一旦由系统创建就不能可靠地修改。v4 用于迁移旧版本可能已被
    // 用户静音或关闭锁屏显示的频道；创建新频道不能替用户改系统设置，但能
    // 避免把旧频道的历史配置误认为当前版本的默认配置。
    const val CHANNEL_ID = "homework_deadline_v4"

    fun notifyHomework(
        context: Context,
        course: String,
        title: String,
        minutesLeft: Long,
        url: String,
        accountId: String,
        reminderToken: String = "",
        endTime: Long = 0L
    ) {
        // A stale WorkManager job or alarm can race with logout/account switch.
        // Never surface a previous account's course data after that switch.
        if (accountId.isBlank() || AccountManager(context).getActiveUid() != accountId) {
            DebugLog.log(context, "忽略非当前账号的提醒任务")
            return
        }
        val currentHomework = if (reminderToken.isNotBlank()) {
            findReminderHomework(context, accountId, reminderToken)
        } else null
        if (reminderToken.isNotBlank() && currentHomework == null) {
            DebugLog.log(context, "忽略已完成或已过期的提醒任务")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(manager)
        if (!canNotify(context) || homeworkChannelNeedsUserAttention(context)) {
            DebugLog.log(context, "通知被系统权限拦截")
            return
        }

        val accounts = AccountManager(context)
        val targetUrl = if (reminderToken.isNotBlank()) currentHomework?.url.orEmpty() else url
        val targetTitle = currentHomework?.title ?: title
        val targetCourse = currentHomework?.courseName ?: course
        val targetEndTime = currentHomework?.endTime?.takeIf { it > 0L } ?: endTime
        val canOpenHomework = targetUrl.isNotEmpty() && accountId.isNotBlank() &&
            accounts.getActiveUid() == accountId && accounts.getCookie(accountId) != null
        val intent = if (canOpenHomework) {
            Intent(context, HomeworkDetailActivity::class.java)
                .putExtra("url", targetUrl).putExtra("accountId", accountId).putExtra("title", targetTitle)
        } else Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, (accountId + ":" + targetUrl + ":" + targetTitle).hashCode(),
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val effectiveMinutes = if (targetEndTime > 0L) {
            remainingReminderMinutes(targetEndTime, System.currentTimeMillis())
        } else minutesLeft
        val text = if (targetEndTime > 0L && targetEndTime <= System.currentTimeMillis()) {
            "「$targetCourse」的作业「$targetTitle」已到截止时间！"
        } else if (effectiveMinutes > 0) {
            "「$targetCourse」的作业「$targetTitle」将在 ${formatMinutes(effectiveMinutes)} 后截止！"
        } else "「$targetCourse」的作业「$targetTitle」即将截止！"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("作业即将截止")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            // 同一作业的同一提醒节点如果因重建/重试再次到达，只更新原通知，
            // 不重复制造声音；不同节点使用不同稳定 ID，仍会分别提醒。
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        // Recheck after local state resolution so a logout racing this dispatch
        // cannot publish the previous account's data.
        if (accounts.getActiveUid() != accountId) return
        val deliveryKey = reminderToken.ifBlank {
            listOf(accountId, targetCourse, targetTitle, minutesLeft, targetUrl).joinToString("\u0000")
        }
        if (!ReminderDeliveryStore.claim(context, deliveryKey, targetEndTime)) return
        manager.notify(notificationId(accountId, targetCourse, targetTitle, minutesLeft, targetUrl, reminderToken), notification)
        DebugLog.log(context, "通知已提交系统")
    }

    private fun findReminderHomework(context: Context, accountId: String, token: String) : Homework? {
        val reminderMinutes = reminderNodeMinutes(token) ?: return null
        val hidden = HiddenStore(context)
        val completion = LocalCompletionStore(context)
        return (HomeworkCache(context).load(accountId) + ManualHomeworkStore(context).list(accountId))
            .map { DeadlineOverrideStore(context).apply(it) }
            .map { homework ->
                if (!homework.isManual() && completion.isCompleted(accountId, homework)) {
                    homework.copy(status = Homework.DONE)
                } else homework
            }
            .firstOrNull { homework ->
                isCurrentReminderEligible(
                    homework = homework,
                    accountId = accountId,
                    reminderToken = token,
                    minutes = reminderMinutes,
                    now = System.currentTimeMillis(),
                    permanentlyHidden = hidden.isPermanentlyHidden(accountId, homework)
                )
            }
    }


    /**
     * Stable notification identity for one account/homework/reminder node.
     * A timestamp-based ID creates a new notification every time a receiver is
     * retried or a process is recreated, which makes duplicate alerts likely.
     */
    internal fun notificationId(
        accountId: String,
        course: String,
        title: String,
        minutesLeft: Long,
        url: String,
        reminderToken: String = ""
    ): Int {
        // The scheduler token contains the stable homework/node identity. It
        // prevents two same-named assignments in one course from replacing
        // each other's notification. Keep the old field-based key for
        // callers created by older WorkManager records.
        val key = reminderToken.takeIf { it.isNotBlank() }
            ?: listOf(accountId, course, title, minutesLeft.toString(), url).joinToString("\u0000")
        var hash = 0x811C9DC5.toInt() // FNV-1a offset basis
        key.forEach { character ->
            hash = (hash xor character.code) * 0x01000193
        }
        // NotificationManager accepts any int, but avoiding zero and MIN_VALUE
        // makes dumpsys output and diagnostics easier to read.
        return (hash and Int.MAX_VALUE).coerceAtLeast(1)
    }

    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, "android.permission.POST_NOTIFICATIONS"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Channel settings are user-owned after creation. Only a disabled channel
     * or a channel hidden from the lock screen blocks ordinary reminders;
     * pop-up and sound settings are reported separately as optional choices.
     */
    fun channelNeedsUserAttention(context: Context): Boolean {
        return homeworkChannelNeedsUserAttention(context) ||
            CourseNotification.channelNeedsUserAttention(context)
    }

    /**
     * Detailed settings for the homework channel. The app can read channel
     * importance, sound and lock-screen visibility, but it cannot read every
     * vendor-specific "floating notification" switch separately.
     */
    fun homeworkChannelDiagnostics(context: Context): ReminderChannelDiagnostics {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ReminderChannelDiagnostics(
                exists = true,
                notificationEnabled = true,
                lockscreenEnabled = true,
                floatingEnabled = true,
                soundEnabled = true
            )
        }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return ReminderPermissionPolicy.diagnoseChannel(
                ReminderChannelState(
                    exists = false,
                    importance = ReminderPermissionPolicy.IMPORTANCE_NONE,
                    soundEnabled = false,
                    lockscreenVisibility = ReminderPermissionPolicy.VISIBILITY_SECRET
                )
            )
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: run {
            createChannel(manager)
            manager.getNotificationChannel(CHANNEL_ID)
        }
        return ReminderPermissionPolicy.diagnoseChannel(
            ReminderChannelState(
                exists = channel != null,
                importance = channel?.importance ?: ReminderPermissionPolicy.IMPORTANCE_NONE,
                soundEnabled = channel?.sound != null,
                // PRIVATE and NO_OVERRIDE still leave a notification visible;
                // only SECRET hides it entirely from the lock screen.
                lockscreenVisibility = channel?.lockscreenVisibility
                    ?: ReminderPermissionPolicy.VISIBILITY_SECRET
            )
        )
    }

    /** Checks only the homework channel so the settings flow can deep-link to it. */
    fun homeworkChannelNeedsUserAttention(context: Context): Boolean =
        !homeworkChannelDiagnostics(context).requiredReady

    fun createChannel(manager: NotificationManager) {
        val channel = NotificationChannel(CHANNEL_ID, "作业截止提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "学习通作业截止前的提醒通知"
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(sound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 350L, 180L, 350L)
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatMinutes(m: Long): String = when {
        m >= 60 * 24 -> "${m / (60 * 24)}天"
        m >= 60 -> "${m / 60}小时${if (m % 60 != 0L) "${m % 60}分钟" else ""}"
        else -> "${m}分钟"
    }
}

internal fun reminderNodeMinutes(token: String): Int? =
    token.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..7 * 24 * 60 }

internal fun isCurrentReminderEligible(
    homework: Homework,
    accountId: String,
    reminderToken: String,
    minutes: Int,
    now: Long,
    permanentlyHidden: Boolean
): Boolean = homework.accountId == accountId &&
    homework.isUnfinished() && homework.endTime > now &&
    !permanentlyHidden && homework.reminderToken(minutes) == reminderToken

internal fun remainingReminderMinutes(endTime: Long, now: Long): Long {
    val remainingMillis = (endTime - now).coerceAtLeast(0L)
    if (remainingMillis == 0L) return 0L
    return (remainingMillis + 59_999L) / 60_000L
}
