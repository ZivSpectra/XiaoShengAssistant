package com.zhizhou.xxtassistant.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.zhizhou.xxtassistant.data.FocusLimitPolicy
import com.zhizhou.xxtassistant.data.FocusRedirectGate
import com.zhizhou.xxtassistant.data.FocusSettings
import com.zhizhou.xxtassistant.data.FocusSettingsStore
import com.zhizhou.xxtassistant.util.DebugLog

/** Leaves a selected app after its local daily budget is spent. It never reads window content. */
class FocusAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: FocusSettingsStore
    private var settings = FocusSettings()
    private var foregroundPackage: String? = null
    private var lastTick = 0L
    private val redirectGate = FocusRedirectGate()
    private var pendingUsagePackage: String? = null
    private var pendingUsageMillis = 0L
    private val homePackage: String? by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("enabled", "entertainment", "study", "limit_minutes")) {
            settings = store.load()
            redirectGate.reset()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            settleUsage()
            enforceLimit()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = FocusSettingsStore(this)
        settings = store.load()
        store.registerSettingsListener(settingsListener)
        DebugLog.log(this, "[FOCUS] 无障碍服务已连接")
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 5_000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        settleUsage(flush = packageName != foregroundPackage)
        redirectGate.onForeground(packageName, isHome = packageName == homePackage)
        if (packageName == homePackage || packageName == settings.studyPackage) {
            foregroundPackage = null
            lastTick = SystemClock.elapsedRealtime()
            return
        }
        foregroundPackage = packageName.takeIf {
            settings.enabled && settings.configured && it in settings.entertainmentPackages
        }
        lastTick = SystemClock.elapsedRealtime()
        enforceLimit()
    }

    private fun settleUsage(flush: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastTick).coerceIn(0L, 10_000L)
        lastTick = now
        val packageName = foregroundPackage ?: return
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!interactive || !settings.enabled || packageName !in settings.entertainmentPackages) {
            flushUsage()
            foregroundPackage = null
            return
        }
        if (pendingUsagePackage != null && pendingUsagePackage != packageName) flushUsage()
        pendingUsagePackage = packageName
        pendingUsageMillis += elapsed
        if (flush || pendingUsageMillis >= USAGE_CHECKPOINT_MS) flushUsage()
    }

    private fun enforceLimit() {
        val packageName = foregroundPackage ?: return
        if (!settings.enabled || !settings.configured || packageName !in settings.entertainmentPackages) return
        val usedToday = store.usedToday(packageName) + pendingUsageMillis.takeIf { pendingUsagePackage == packageName }.orZero()
        if (!FocusLimitPolicy.reached(usedToday, settings.dailyLimitMinutes)) return
        val target = settings.studyPackage ?: return
        if (!redirectGate.begin(packageName, target)) return
        foregroundPackage = null
        val launchIntent = packageManager.getLaunchIntentForPackage(target) ?: run {
            DebugLog.log(this, "[FOCUS] 学习应用没有可启动入口")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(launchIntent) }
            .onFailure {
                DebugLog.log(this, "[FOCUS] 直接打开学习应用失败，改为先返回桌面再重试")
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    runCatching { startActivity(launchIntent) }
                        .onFailure { DebugLog.log(this, "[FOCUS] 学习应用跳转失败") }
                }, FALLBACK_LAUNCH_DELAY_MS)
            }
    }

    private fun flushUsage() {
        val packageName = pendingUsagePackage ?: return
        val elapsed = pendingUsageMillis
        pendingUsagePackage = null
        pendingUsageMillis = 0L
        if (elapsed > 0L) store.addUsage(packageName, elapsed)
    }

    override fun onInterrupt() {
        settleUsage(flush = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        settleUsage(flush = true)
        store.unregisterSettingsListener(settingsListener)
        super.onDestroy()
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private companion object {
        const val USAGE_CHECKPOINT_MS = 30_000L
        const val FALLBACK_LAUNCH_DELAY_MS = 200L
    }
}
