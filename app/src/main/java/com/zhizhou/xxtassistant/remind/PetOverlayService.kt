package com.zhizhou.xxtassistant.remind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import com.zhizhou.xxtassistant.MainActivity
import com.zhizhou.xxtassistant.R
import com.zhizhou.xxtassistant.data.AcademicYear
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CoursePeriodStore
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import com.zhizhou.xxtassistant.data.CourseTableSettingsStore
import com.zhizhou.xxtassistant.data.PetSettingsStore
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 桌宠首版：一个可关闭的边缘小笙，不读取屏幕内容，也不参与作业抓取。
 * 只有用户在设置中主动开启并授予悬浮窗权限后才会运行。
 */
class PetOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var petView: PetView? = null
    /** Distance along the four-edge perimeter, in pixels. */
    private var perimeterPosition = 0f
    private var screenWidth = 1
    private var screenHeight = 1
    private var stoppingByUser = false
    private var waypoint = Float.NaN
    private var direction = 1f
    private var pausedUntil = 0L
    private var nextArtworkAt = SystemClock.uptimeMillis() + 10_000L
    private val random = Random.Default
    private val earlyClassToday by lazy(LazyThreadSafetyMode.NONE) { hasEarlyClassToday() }

    // Keep the edge animation above app bottom bars and the system gesture
    // area. Without this reserved strip the pet can intercept the last tab's
    // taps while the user is switching pages.
    private val bottomSafeArea: Int
        get() = 112.dp

    private val moveTask = object : Runnable {
        override fun run() {
            val view = petView ?: return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            val edge = 8.dp
            val width = (view.width.takeIf { it > 0 } ?: params.width)
            val height = (view.height.takeIf { it > 0 } ?: params.height)
            val usableWidth = (screenWidth - width - edge * 2).coerceAtLeast(1)
            val usableHeight = (screenHeight - height - edge * 2 - bottomSafeArea).coerceAtLeast(1)
            val perimeter = 2f * (usableWidth + usableHeight)
            val now = SystemClock.uptimeMillis()
            if (now < pausedUntil) {
                handler.postDelayed(this, 100L)
                return
            }
            if (!waypoint.isFinite()) chooseWaypoint(perimeter)
            val remaining = if (direction > 0f) {
                (waypoint - perimeterPosition + perimeter) % perimeter
            } else {
                (perimeterPosition - waypoint + perimeter) % perimeter
            }
            val speed = random.nextDouble(0.7, 1.7).toFloat()
            if (remaining <= speed) {
                perimeterPosition = waypoint
                waypoint = Float.NaN
                direction = if (random.nextBoolean()) 1f else -1f
                pausedUntil = now + random.nextLong(250L, 900L)
            } else {
                perimeterPosition = (perimeterPosition + direction * speed + perimeter) % perimeter
            }

            // Walk clockwise around the complete screen edge: bottom -> right
            // -> top -> left. The margin keeps the overlay out of the system
            // gesture boundary while still making the edge movement visible.
            val p = perimeterPosition
            when {
                p < usableWidth -> {
                    params.x = edge + p.roundToInt()
                    params.y = edge + usableHeight
                }
                p < usableWidth + usableHeight -> {
                    params.x = edge + usableWidth
                    params.y = edge + usableHeight - (p - usableWidth).roundToInt()
                }
                p < 2f * usableWidth + usableHeight -> {
                    params.x = edge + usableWidth - (p - usableWidth - usableHeight).roundToInt()
                    params.y = edge
                }
                else -> {
                    params.x = edge
                    params.y = edge + (p - 2f * usableWidth - usableHeight).roundToInt()
                }
            }
            runCatching { windowManager?.updateViewLayout(view, params) }
            if (now >= nextArtworkAt) {
                view.setImageResource(randomArtwork())
                nextArtworkAt = now + random.nextLong(7_000L, 16_000L)
            }
            handler.postDelayed(this, random.nextLong(70L, 120L))
        }
    }

    private fun chooseWaypoint(perimeter: Float) {
        direction = if (random.nextBoolean()) 1f else -1f
        val distance = random.nextDouble((perimeter * 0.04f).toDouble(), (perimeter * 0.28f).toDouble()).toFloat()
        waypoint = (perimeterPosition + direction * distance + perimeter) % perimeter
    }

    private fun hasEarlyClassToday(): Boolean {
        val accountId = AccountManager(this).getActiveUid() ?: return false
        val now = System.currentTimeMillis()
        val term = AcademicYear.currentTerm(now)
        val settings = CourseTableSettingsStore(this)
        val week = settings.teachingWeek(accountId, term, now) ?: return false
        val weekday = java.util.Calendar.getInstance().apply { timeInMillis = now }
            .get(java.util.Calendar.DAY_OF_WEEK).let { if (it == java.util.Calendar.SUNDAY) 7 else it - 1 }
        val periods = CoursePeriodStore(this).list(accountId).associateBy { it.period }
        return CourseScheduleStore(this).list(accountId, term).any { course ->
            course.weekday == weekday && course.occursInWeek(week) &&
                (periods[course.startPeriod]?.startMinute ?: if (course.startPeriod <= 2) 8 * 60 else Int.MAX_VALUE) < 9 * 60
        }
    }

    private fun randomArtwork(): Int = if (earlyClassToday) {
        listOf(R.drawable.pet_good_morning, R.drawable.pet_got_it, R.drawable.pet_encourage, R.drawable.pet_comfort).random(random)
    } else {
        listOf(R.drawable.pet_celebrate, R.drawable.pet_got_it, R.drawable.pet_encourage).random(random)
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            PetSettingsStore(this).setEnabled(false)
            stopSelf()
            return
        }
        createChannel()
        // Android 14 requires the runtime foreground-service type to match the
        // manifest declaration. Older releases use the legacy overload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        windowManager = getSystemService(WindowManager::class.java)
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        petView = PetView(this).also { view ->
            val params = WindowManager.LayoutParams(
                56.dp,
                56.dp,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 8.dp
                y = (screenHeight - 56.dp - 8.dp - bottomSafeArea).coerceAtLeast(8.dp)
            }
            view.layoutParams = params
            runCatching { windowManager?.addView(view, params) }
                .onFailure {
                    Toast.makeText(this, "桌宠暂时无法显示，请检查悬浮窗权限", Toast.LENGTH_LONG).show()
                    PetSettingsStore(this).setEnabled(false)
                    stopSelf()
                }
        }
        handler.post(moveTask)
    }

    /**
     * 桌宠是用户主动开启的常驻前台服务。应用任务被划掉或进程被系统回收后，
     * 允许系统在资源恢复时重新创建服务；显式关闭桌宠时仍由 stopSelf 终止。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉应用任务不应等同于关闭桌宠。部分厂商会同时停止服务，
        // 这里在服务仍被用户开启且权限有效时请求一次前台服务重启。
        if (!stoppingByUser && PetSettingsStore(this).isEnabled() && Settings.canDrawOverlays(this)) {
            runCatching {
                val restart = Intent(this, PetOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restart)
                } else {
                    startService(restart)
                }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        petView?.let { view -> runCatching { windowManager?.removeView(view) } }
        petView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            8401,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("小笙桌宠已在身边")
            .setContentText("轻点桌宠打开小笙，长按可关闭桌宠")
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "桌宠运行所需的常驻状态通知"
                setShowBadge(false)
            }
        )
    }

    // The overlay service is created with the platform Service context, not an
    // AppCompat activity theme. Using the platform ImageView avoids AppCompat's
    // ThemeUtils error while keeping the same drawable/touch behavior.
    @SuppressLint("AppCompatCustomView")
    private inner class PetView(context: Context) : android.widget.ImageView(context) {
        private val hasEarlyClass = earlyClassToday
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var longPressTriggered = false
        private var touchCancelled = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPressTask = Runnable {
            if (isPressed && !longPressTriggered) {
                longPressTriggered = true
                performLongClick()
            }
        }

        init {
            isClickable = true
            // Use the actual Xiaosheng artwork instead of drawing a placeholder
            // shape. The launcher foreground keeps the transparent outer area
            // so the overlay does not become an opaque square on screen.
            setImageResource(if (hasEarlyClass) R.drawable.pet_comfort else R.drawable.pet_celebrate)
            scaleType = ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            contentDescription = "小笙桌宠"
            setOnClickListener {
                val mood = if (hasEarlyClass) random.nextInt(2) else random.nextInt(2, 4)
                setImageResource(if (hasEarlyClass) R.drawable.pet_good_morning else R.drawable.pet_celebrate)
                Toast.makeText(this@PetOverlayService, moodText(mood), Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                stoppingByUser = true
                PetSettingsStore(this@PetOverlayService).setEnabled(false)
                stopSelf()
                Toast.makeText(this@PetOverlayService, "桌宠先休息一下，需要时可在设置中重新开启", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
                longPressTriggered = false
                touchCancelled = false
                isPressed = true
                handler.postDelayed(longPressTask, ViewConfiguration.getLongPressTimeout().toLong())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop
                ) {
                    handler.removeCallbacks(longPressTask)
                    touchCancelled = true
                    isPressed = false
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressTask)
                val wasLongPress = longPressTriggered
                isPressed = false
                if (!wasLongPress && !touchCancelled &&
                    SystemClock.uptimeMillis() - downAt < ViewConfiguration.getLongPressTimeout()) {
                    performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressTask)
                touchCancelled = true
                isPressed = false
                true
            }
            else -> true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun moodText(mood: Int): String = when (mood) {
        0 -> "早八辛苦啦，我陪你慢慢清醒，记得带上课本。"
        1 -> "今天有早课也别慌，我会在旁边给你加油。"
        2 -> "好耶，今天没有早八！先睡饱一点再出发。"
        else -> "今天的早晨宽裕些，慢慢来，准备好再出门。"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        const val CHANNEL_ID = "pet_service"
        const val NOTIFICATION_ID = 8402

        private fun startService(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        private fun stopService(context: Context) {
            context.stopService(Intent(context, PetOverlayService::class.java))
        }
        fun startIfPermitted(context: Context) = startService(context)
        fun stop(context: Context) = stopService(context)
    }
}
