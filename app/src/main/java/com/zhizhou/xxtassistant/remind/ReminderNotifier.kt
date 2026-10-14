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
import com.zhizhou.xxtassistant.util.DebugLog

/** 统一构造提醒通知，供 WorkManager 和精确闹钟广播共同使用。 */
object ReminderNotifier {
    // 新频道确保升级旧版本后也能应用锁屏可见和高优先级配置。
    // 频道属性一旦由系统创建就不能可靠地修改，v3 用于升级旧版静音/不显示锁屏的频道。
    const val CHANNEL_ID = "homework_deadline_v3"

    fun notifyHomework(context: Context, course: String, title: String, minutesLeft: Long, url: String, accountId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(manager)
        if (!canNotify(context)) {
            DebugLog.log(context, "通知被系统权限拦截：$title")
            return
        }

        val accounts = AccountManager(context)
        val canOpenHomework = url.isNotEmpty() && accountId.isNotBlank() &&
            accounts.getActiveUid() == accountId && accounts.getCookie(accountId) != null
        val intent = if (canOpenHomework) {
            Intent(context, HomeworkDetailActivity::class.java)
                .putExtra("url", url).putExtra("accountId", accountId).putExtra("title", title)
        } else Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, (accountId + ":" + url + ":" + title).hashCode(),
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (minutesLeft > 0) {
            "「$course」的作业「$title」将在 ${formatMinutes(minutesLeft)} 后截止！"
        } else "「$course」的作业「$title」已到截止时间！"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("作业即将截止")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        manager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        DebugLog.log(context, "通知已提交系统：$title")
    }

    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, "android.permission.POST_NOTIFICATIONS"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Channel settings are user-owned after creation. A null sound or NONE
     * importance explains a silent test even when POST_NOTIFICATIONS is granted.
     */
    fun channelNeedsUserAttention(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null
    }

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
