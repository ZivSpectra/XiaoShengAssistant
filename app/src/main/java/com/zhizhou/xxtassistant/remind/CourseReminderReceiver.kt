package com.zhizhou.xxtassistant.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountId = intent.getStringExtra(CourseReminderScheduler.EXTRA_ACCOUNT).orEmpty()
        if (accountId.isBlank() || AccountManager(context).getActiveUid() != accountId) return
        // Keep notification delivery short; rolling-window rebuilding happens
        // on the scheduler queue after the notification is submitted.
        when (intent.action) {
                    CourseReminderScheduler.ACTION_SHOW -> CourseNotification.notifyCourse(
                        context,
                        accountId = accountId,
                        id = courseNotificationId(
                            accountId,
                            intent.getStringExtra(CourseReminderScheduler.EXTRA_COURSE_ID).orEmpty(),
                            intent.getLongExtra(CourseReminderScheduler.EXTRA_START, 0L)
                        ),
                        courseId = intent.getStringExtra(CourseReminderScheduler.EXTRA_COURSE_ID).orEmpty(),
                        title = intent.getStringExtra(CourseReminderScheduler.EXTRA_TITLE).orEmpty(),
                        room = intent.getStringExtra(CourseReminderScheduler.EXTRA_ROOM).orEmpty(),
                        startAt = intent.getLongExtra(CourseReminderScheduler.EXTRA_START, 0L)
                    )
                    CourseReminderScheduler.ACTION_CANCEL -> CourseNotification.cancelCourse(
                        context,
                        accountId,
                        courseNotificationId(
                            accountId,
                            intent.getStringExtra(CourseReminderScheduler.EXTRA_COURSE_ID).orEmpty(),
                            intent.getLongExtra(CourseReminderScheduler.EXTRA_START, 0L)
                        )
                    )
                    CourseReminderScheduler.ACTION_DISMISS -> CourseNotification.cancelCourse(
                        context,
                        accountId,
                        intent.getIntExtra(CourseReminderScheduler.EXTRA_NOTIFICATION_ID, 0)
                    )
                    CourseReminderScheduler.ACTION_PREP -> {
                        val targetDay = intent.getLongExtra(CourseReminderScheduler.EXTRA_TARGET_DAY, 0L)
                        CourseNotification.notifyPreparation(context, accountId, targetDay)
                    }
        }
        // Extending the rolling window is cache I/O plus many AlarmManager
        // calls; never hold the receiver's short delivery window for it.
        ReminderDispatch.enqueueCourses(
            context = context,
            accountId = accountId,
            schedules = { CourseScheduleStore(context).list(accountId) }
        )
    }

}

/** Stable identity for one account/course occurrence, shared by SHOW and CANCEL. */
internal fun courseNotificationId(accountId: String, courseId: String, startAt: Long): Int =
    ("$accountId:$courseId:$startAt").hashCode().and(0x7fffffff)

object CourseNotification {
    const val CHANNEL_ID = "course_schedule_v1"
    private const val PREFS = "xxt_course_notifications"
    private const val IDS = "ids"
    private const val SEPARATOR = "\u0000"
    private val notificationLock = Any()

    /** Detailed state used by the permission page to identify the exact gap. */
    fun channelDiagnostics(context: Context): ReminderChannelDiagnostics {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            return ReminderChannelDiagnostics(
                exists = true,
                notificationEnabled = true,
                lockscreenEnabled = true,
                floatingEnabled = true,
                soundEnabled = true
            )
        }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return ReminderPermissionPolicy.diagnoseChannel(
                ReminderChannelState(
                    exists = false,
                    importance = ReminderPermissionPolicy.IMPORTANCE_NONE,
                    soundEnabled = false,
                    lockscreenVisibility = ReminderPermissionPolicy.VISIBILITY_SECRET
                )
            )
        // Channel creation is lazy. Create it during diagnostics so a fresh
        // install is not reported as broken only because no class has fired.
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: run {
            createChannel(manager)
            manager.getNotificationChannel(CHANNEL_ID)
        }
        return ReminderPermissionPolicy.diagnoseChannel(
            ReminderChannelState(
                exists = channel != null,
                importance = channel?.importance ?: ReminderPermissionPolicy.IMPORTANCE_NONE,
                soundEnabled = channel?.sound != null,
                lockscreenVisibility = channel?.lockscreenVisibility
                    ?: ReminderPermissionPolicy.VISIBILITY_SECRET
            )
        )
    }

    fun channelNeedsUserAttention(context: Context): Boolean =
        !channelDiagnostics(context).requiredReady

    fun notifyCourse(context: Context, accountId: String, id: Int, courseId: String, title: String, room: String, startAt: Long) {
        if (accountId.isBlank() || AccountManager(context).getActiveUid() != accountId) return
        if (!CourseReminderScheduler.isOccurrenceCurrent(context, accountId, courseId, startAt)) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        createChannel(manager)
        if (!ReminderNotifier.canNotify(context)) return
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(startAt))
        val roomText = room.ifBlank { "教室待确认" }
        val dismissIntent = Intent(context, CourseReminderReceiver::class.java).apply {
            action = CourseReminderScheduler.ACTION_DISMISS
            data = android.net.Uri.parse("xxt://course-notification/dismiss/$accountId/$id")
            putExtra(CourseReminderScheduler.EXTRA_ACCOUNT, accountId)
            putExtra(CourseReminderScheduler.EXTRA_NOTIFICATION_ID, id)
        }
        val dismiss = android.app.PendingIntent.getBroadcast(
            context,
            id,
            dismissIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = android.app.PendingIntent.getActivity(
            context,
            id,
            Intent(context, com.zhizhou.xxtassistant.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val lifetime = CourseReminderPolicy.untilAcknowledged
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.zhizhou.xxtassistant.R.drawable.ic_launcher_foreground)
            .setContentTitle("${time} 即将上课")
            .setContentText("$title · $roomText")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("课程：$title\n教室：$roomText\n开始时间：$time"))
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp)
            .setOngoing(lifetime.ongoing)
            .setAutoCancel(lifetime.autoCancel)
            .addAction(com.zhizhou.xxtassistant.R.drawable.ic_launcher_foreground, "知道了", dismiss)
            // A duplicated broadcast or a rolling-window rebuild updates the
            // same occurrence; it must not ring/vibrate a second time.
            .setOnlyAlertOnce(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            // The channel controls sound on Android O+; defaults keep the
            // same audible/vibration behavior on the app's minSdk (26 and
            // older compatible notification implementations).
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .build()
        if (AccountManager(context).getActiveUid() != accountId) return
        manager.notify(id, notification)
        rememberNotificationId(context, accountId, id)
    }

    fun cancelCourse(context: Context, accountId: String, id: Int) {
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(id)
        forgetNotificationId(context, accountId, id)
    }

    fun notifyPreparation(
        context: Context,
        accountId: String,
        targetDay: Long
    ) {
        if (accountId.isBlank() || AccountManager(context).getActiveUid() != accountId) return
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (targetDay <= today) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        createChannel(manager)
        if (!ReminderNotifier.canNotify(context)) return
        val currentCourses = CourseReminderScheduler.coursesForDate(
            context, accountId, targetDay, CourseScheduleStore(context).list(accountId)
        )
        if (currentCourses.isEmpty()) return
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(targetDay))
        val text = preparationSummary(currentCourses)
        manager.notify(preparationNotificationId(accountId, targetDay), androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.zhizhou.xxtassistant.R.drawable.ic_launcher_foreground)
            .setContentTitle("明日课程准备 · $date")
            .setContentText(currentCourses.joinToString("、") { it.name })
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .build())
        if (AccountManager(context).getActiveUid() != accountId) return
        rememberNotificationId(context, accountId, preparationNotificationId(accountId, targetDay))
    }

    /**
     * Track only course-channel notification IDs so logout does not delete
     * homework or pet notifications from the system shade.
     */
    fun clearForAccount(context: Context, accountId: String?) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(notificationLock) {
            val stored = prefs.getStringSet(IDS, emptySet()).orEmpty().toMutableSet()
            val prefix = accountId?.takeIf { it.isNotBlank() }?.plus(SEPARATOR)
            val targets = if (prefix == null) stored.toSet() else stored.filter { it.startsWith(prefix) }.toSet()
            targets.forEach { entry ->
                val id = entry.substringAfterLast(SEPARATOR).toIntOrNull() ?: return@forEach
                manager?.cancel(id)
                stored.remove(entry)
            }
            prefs.edit().putStringSet(IDS, stored).commit()
        }
    }

    private fun rememberNotificationId(context: Context, accountId: String, id: Int) {
        if (accountId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(notificationLock) {
            val ids = prefs.getStringSet(IDS, emptySet()).orEmpty().toMutableSet()
            ids += "$accountId$SEPARATOR$id"
            prefs.edit().putStringSet(IDS, ids).commit()
        }
    }

    private fun forgetNotificationId(context: Context, accountId: String, id: Int) {
        if (accountId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(notificationLock) {
            val ids = prefs.getStringSet(IDS, emptySet()).orEmpty().toMutableSet()
            ids.remove("$accountId$SEPARATOR$id")
            prefs.edit().putStringSet(IDS, ids).commit()
        }
    }

    private fun createChannel(manager: android.app.NotificationManager) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val channel = android.app.NotificationChannel(CHANNEL_ID, "课程提醒", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
            description = "课程开始前和前一天的课程准备提醒"
            setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }
}

/**
 * Builds the compact evening preparation list. Room/teacher data is deliberately
 * omitted: this reminder is only for deciding which books to prepare.
 */
internal fun preparationSummary(courses: List<CourseSchedule>): String =
    courses.sortedBy { it.startPeriod }
        .joinToString("\n") { "${it.periodText()}  ${it.name}" }

/** Stable identity for the next-day preparation reminder, isolated per account. */
internal fun preparationNotificationId(accountId: String, targetDay: Long): Int =
    ("prep:$accountId:$targetDay").hashCode().and(0x7fffffff)
