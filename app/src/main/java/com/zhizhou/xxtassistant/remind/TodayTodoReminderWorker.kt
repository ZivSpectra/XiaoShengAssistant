package com.zhizhou.xxtassistant.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zhizhou.xxtassistant.R
import com.zhizhou.xxtassistant.TodayActivity
import com.zhizhou.xxtassistant.data.TodayTodo
import com.zhizhou.xxtassistant.data.TodayTodoStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Persistent, best-effort reminder for a scheduled item on the Today page. */
class TodayTodoReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT).orEmpty()
        val dayKey = inputData.getString(KEY_DAY).orEmpty()
        val todoId = inputData.getLong(KEY_ID, -1L)
        if (accountId.isBlank() || dayKey.isBlank() || todoId < 0L) return Result.failure()

        val todo = TodayTodoStore(applicationContext).find(accountId, dayKey, todoId)
            ?: return Result.success()
        if (todo.done) return Result.success()
        val scheduledAt = todo.scheduledAt ?: return Result.success()
        if (scheduledAt > System.currentTimeMillis()) {
            enqueue(applicationContext, accountId, dayKey, todo, scheduledAt)
            return Result.success()
        }

        ensureChannel()
        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            val openToday = PendingIntent.getActivity(
                applicationContext,
                todoId.toInt(),
                Intent(applicationContext, TodayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("今日待办")
                .setContentText(todo.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(todo.text))
                .setContentIntent(openToday)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationId(accountId, todoId), notification)
        }
        return Result.success()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "今日待办", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "按计划时间提醒今日待办"
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "today_todo_reminders"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_DAY = "day"
        private const val KEY_ID = "todo_id"

        fun schedule(context: Context, accountId: String, dayKey: String, todo: TodayTodo) {
            val at = todo.scheduledAt ?: return
            enqueue(context, accountId, dayKey, todo, at)
        }

        private fun enqueue(context: Context, accountId: String, dayKey: String, todo: TodayTodo, at: Long) {
            val data = Data.Builder()
                .putString(KEY_ACCOUNT, accountId)
                .putString(KEY_DAY, dayKey)
                .putLong(KEY_ID, todo.id)
                .build()
            val request = OneTimeWorkRequestBuilder<TodayTodoReminderWorker>()
                .setInputData(data)
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(accountId, todo.id),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun uniqueName(accountId: String, id: Long): String = "today-todo-${hash(accountId)}-$id"

        private fun notificationId(accountId: String, id: Long): Int =
            (hash(accountId).take(7) + id.toString()).hashCode()

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(10)
            .joinToString("") { "%02x".format(it) }
    }
}
