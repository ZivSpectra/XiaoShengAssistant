package com.zhizhou.xxtassistant.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhizhou.xxtassistant.util.DebugLog

/** AlarmManager 精确模式的接收器；系统锁屏时也可以唤醒应用发送通知。 */
class ExactReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.log(context, "提醒广播已触发")
        ReminderNotifier.notifyHomework(
            context,
            intent.getStringExtra(ReminderScheduler.EXTRA_COURSE) ?: "",
            intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "作业",
            intent.getLongExtra(ReminderScheduler.EXTRA_MINUTES, 0L),
            intent.getStringExtra(ReminderScheduler.EXTRA_URL) ?: "",
            intent.getStringExtra(ReminderScheduler.EXTRA_ACCOUNT) ?: "",
            intent.getStringExtra(ReminderScheduler.EXTRA_TOKEN) ?: "",
            intent.getLongExtra(ReminderScheduler.EXTRA_END_TIME, 0L)
        )
    }
}
