package com.zhizhou.xxtassistant.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** 设备重启后恢复后台提醒。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_DATE_CHANGED &&
            action != "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        ) return
        // Receivers have a short execution window. Queue the cache scan and
        // AlarmManager rebuild on WorkManager's executor instead of racing a
        // hand-managed thread against Android's broadcast timeout.
        val request = OneTimeWorkRequestBuilder<ReminderRestoreWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "restore_reminders_after_system_change",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
