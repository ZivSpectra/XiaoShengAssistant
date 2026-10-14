package com.zhizhou.xxtassistant.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.DeadlineOverrideStore
import com.zhizhou.xxtassistant.data.HiddenStore
import com.zhizhou.xxtassistant.data.HomeworkCache
import com.zhizhou.xxtassistant.data.ManualHomeworkStore
import com.zhizhou.xxtassistant.data.LocalCompletionStore

/** 设备重启后恢复后台提醒。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_DATE_CHANGED
        ) return
        val pending = goAsync()
        Thread {
            try {
                val accounts = AccountManager(context)
                val cache = HomeworkCache(context)
                val manual = ManualHomeworkStore(context)
                val overrides = DeadlineOverrideStore(context)
                val hidden = HiddenStore(context)
                val completed = LocalCompletionStore(context)
                val allHomeworks = accounts.getAccounts().keys.flatMap { uid ->
                    val all = (cache.load(uid) + manual.list(uid)).map(overrides::apply)
                        .map { hw ->
                            if (!hw.isManual() && completed.isCompleted(uid, hw)) hw.copy(status = com.zhizhou.xxtassistant.data.Homework.DONE)
                            else hw
                        }
                        .filterNot { hidden.isHidden(uid, it, "all") }
                        .filter { it.isUnfinished() }
                    all
                }
                ReminderScheduler.reschedule(context, allHomeworks)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
