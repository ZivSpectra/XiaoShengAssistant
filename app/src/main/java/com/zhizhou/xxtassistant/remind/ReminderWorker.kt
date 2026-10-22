package com.zhizhou.xxtassistant.remind

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zhizhou.xxtassistant.util.DebugLog

/**
 * 提醒通知的"执行者"：到了预定时刻，由 WorkManager 唤醒它发通知。
 *
 * 通知内容只包含课程名、作业名、还有多久截止——不包含任何账号信息。
 */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: "作业"
        val course = inputData.getString(KEY_COURSE) ?: ""
        val minutesLeft = inputData.getLong(KEY_MINUTES_LEFT, 0L)
        val endTime = inputData.getLong(KEY_END_TIME, 0L)
        val url = inputData.getString(KEY_URL) ?: ""
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: ""
        val reminderToken = inputData.getString(KEY_TOKEN) ?: ""
        DebugLog.log(applicationContext, "WorkManager 提醒任务已触发")
        ReminderNotifier.notifyHomework(applicationContext, course, title, minutesLeft, url, accountId, reminderToken, endTime)
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_COURSE = "course"
        const val KEY_MINUTES_LEFT = "minutes_left"
        const val KEY_END_TIME = "end_time"
        const val KEY_URL = "url"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_TOKEN = "reminder_token"
    }
}
