package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.app.NotificationManagerCompat
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.AccountProfileStore
import com.zhizhou.xxtassistant.remind.CourseNotification
import com.zhizhou.xxtassistant.data.GithubReleaseChecker
import com.zhizhou.xxtassistant.remind.ReminderNotifier
import com.zhizhou.xxtassistant.remind.ReminderChannelDiagnostics
import com.zhizhou.xxtassistant.remind.ReminderScheduler
import com.zhizhou.xxtassistant.util.DebugLog

class MyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = AccountManager(this).getActiveUid()
        if (uid.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContent { MyScreen(uid) }
    }
}

private data class PermissionItem(
    val title: String,
    val detail: String,
    val ready: Boolean,
    val iconRes: Int,
    val optional: Boolean = false,
    val stateLabel: String? = null,
    val open: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun MyScreen(accountId: String) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var updateText by remember { mutableStateOf("正在检查版本…") }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    fun copyDiagnostics() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val logs = DebugLog.readForCopy(context)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("小笙助手诊断日志", logs))
        android.widget.Toast.makeText(
            context,
            if (logs.isBlank()) "当前没有诊断日志" else "已复制脱敏诊断日志",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    var skipGuide by remember {
        mutableStateOf(context.getSharedPreferences("xxt_reminder_onboarding", Context.MODE_PRIVATE)
            .getBoolean("skip_permission_guide", false))
    }
    DisposableEffect(Unit) {
        var disposed = false
        fun checkUpdate(force: Boolean) {
            checkingUpdate = true
            GithubReleaseChecker.checkAsync(context, force) { result ->
                if (disposed) return@checkAsync
                checkingUpdate = false
                updateUrl = result.release?.htmlUrl
                updateText = when {
                    result.isNewer && result.release != null -> "发现新版本 ${result.release.tag}，点击查看"
                    result.coolingDown -> "最近检查过，暂不重复请求"
                    result.error != null && result.release == null -> "暂时无法连接 GitHub，点击重试"
                    result.error != null -> "上次版本检查失败，显示已缓存结果"
                    else -> "当前已是最新版本"
                }
            }
        }
        checkUpdate(force = false)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        (context as? ComponentActivity)?.lifecycle?.addObserver(observer)
        onDispose {
            disposed = true
            (context as? ComponentActivity)?.lifecycle?.removeObserver(observer)
        }
    }
    val permissionItems = remember(context, refresh) { permissionItems(context) }
    val displayName = remember(context, accountId, refresh) {
        AccountProfileStore(context).displayName(accountId) ?: "姓名暂未同步"
    }
    androidx.activity.compose.BackHandler {
        returnToTodayOrExit(context as ComponentActivity)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("我的", "设置", "高级功能", "关于")[page]) },
                navigationIcon = {
                    if (page != 0) TextButton(onClick = { page = 0 }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                }
            )
        },
        bottomBar = {
            if (page == 0) {
                XxtBottomNavigation(selectedTab = 3) { tab ->
                    when (tab) {
                        0 -> context.navigateToTab(TodayActivity::class.java)
                        1 -> context.navigateToTab(CourseTableActivity::class.java)
                        2 -> context.navigateToTab(MainActivity::class.java)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            when (page) {
                0 -> {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painterResource(R.mipmap.ic_launcher_foreground),
                                "小笙助手图标，双击复制诊断日志",
                                Modifier.height(52.dp).padding(end = 14.dp).combinedClickable(
                                    onClick = {}, onDoubleClick = { copyDiagnostics() }
                                )
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("小笙助手", style = MaterialTheme.typography.titleLarge)
                                Text("学习通账号：$displayName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { SettingsSectionTitle("账户与应用") }
                    item { SettingsRow("设置", "通知权限、后台运行和退出登录", "", R.drawable.ic_settings_gear) { page = 1 } }
                    if (EditionFeatures.available) {
                        item { SettingsRow("高级功能", "桌宠、自律模式", "", R.drawable.ic_settings_sparkle) { page = 2 } }
                    }
                    item { SettingsRow("关于", "版本更新与学习交流群", "", R.drawable.ic_settings_info) { page = 3 } }
                }
                1 -> {
                    item { SettingsSectionTitle("基础权限") }
                    permissionItems.forEach { permission ->
                        item {
                            SettingsRow(
                                permission.title, permission.detail,
                                permission.stateLabel ?: when {
                                    permission.ready -> "已开启"
                                    permission.optional -> "未开启（可选）"
                                    else -> "未开启"
                                },
                                permission.iconRes,
                                statusWarning = !permission.ready && !permission.optional,
                                onClick = permission.open
                            )
                        }
                    }
                    item { SettingsRow("重新检查权限", "从系统重新读取当前授权状态", "", R.drawable.ic_settings_refresh) { refresh++ } }
                    item { SettingsSectionTitle("提醒方式") }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("首次权限引导", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = !skipGuide, onCheckedChange = { enabled ->
                                skipGuide = !enabled
                                context.getSharedPreferences("xxt_reminder_onboarding", Context.MODE_PRIVATE)
                                    .edit().putBoolean("skip_permission_guide", !enabled).apply()
                            })
                        }
                        HorizontalDivider(Modifier.padding(start = 62.dp))
                    }
                    item { SettingsSectionTitle("账户") }
                    item {
                        SettingsRow("退出登录", "保留本地课程和作业，仅清除登录凭证", "", R.drawable.ic_settings_logout) {
                            androidx.appcompat.app.AlertDialog.Builder(context)
                                .setTitle("退出登录")
                                .setMessage("只清除登录凭证，当前账号的课程和作业数据会保留。")
                                .setPositiveButton("退出") { _, _ ->
                                    AccountManager(context).logout()
                                    ReminderScheduler.cancelAll(context)
                                    android.webkit.CookieManager.getInstance().removeAllCookies {
                                        android.webkit.CookieManager.getInstance().flush()
                                        context.startActivity(Intent(context, LoginActivity::class.java))
                                        (context as? android.app.Activity)?.finish()
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
                2 -> {
                    item { EditionFeatures.Screen(context) }
                }
                3 -> {
                    item { SettingsSectionTitle("应用信息") }
                    item {
                        SettingsRow("GitHub 版本检查", updateText, if (checkingUpdate) "检查中" else "检查", R.drawable.ic_settings_refresh) {
                            if (updateUrl != null && updateText.startsWith("发现新版本")) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) }
                            } else if (!checkingUpdate) {
                                checkingUpdate = true
                                GithubReleaseChecker.checkAsync(context, force = true) { result ->
                                    checkingUpdate = false
                                    updateUrl = result.release?.htmlUrl
                                    updateText = when {
                                        result.isNewer && result.release != null -> "发现新版本 ${result.release.tag}，点击查看"
                                        result.coolingDown -> "最近检查过，暂不重复请求"
                                        result.error != null && result.release == null -> "暂时无法连接 GitHub，点击重试"
                                        result.error != null -> "上次版本检查失败，显示已缓存结果"
                                        else -> "当前已是最新版本"
                                    }
                                }
                            }
                        }
                    }
                    item { SettingsRow("学习交流群", "群号 243863924", "复制", R.drawable.ic_settings_share) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("学习交流群", "243863924"))
                        android.widget.Toast.makeText(context, "群号已复制", android.widget.Toast.LENGTH_SHORT).show()
                    } }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@androidx.compose.runtime.Composable
private fun SettingsRow(
    title: String,
    detail: String,
    action: String,
    iconRes: Int,
    enabled: Boolean = true,
    statusWarning: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 68.dp).padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(iconRes), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action.isNotBlank()) Text(
            action,
            style = MaterialTheme.typography.bodyMedium,
            color = if (statusWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (enabled) Text("›", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

private fun channelDetail(label: String, state: ReminderChannelDiagnostics): String {
    return when {
        !state.exists -> "$label 通知频道尚未建立"
        !state.notificationEnabled -> "通知已关闭，点击进入频道设置"
        !state.lockscreenEnabled -> "锁屏显示已关闭，点击进入频道设置"
        else -> "通知与锁屏显示可用；声音${if (state.soundEnabled) "已开启" else "未开启（可选）"}"
    }
}

private fun floatingDetail(homework: ReminderChannelDiagnostics, course: ReminderChannelDiagnostics): String {
    val missing = listOfNotNull(
        "作业".takeIf { !homework.floatingEnabled },
        "课程".takeIf { !course.floatingEnabled }
    )
    return if (missing.isEmpty()) "两类通知均为高优先级；是否弹出由系统控制"
    else "${missing.joinToString("、")}通知未设高优先级；可按需开启"
}

private fun notificationPermissionDetail(context: Context): String {
    val appSwitch = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val runtimePermission = Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            "android.permission.POST_NOTIFICATIONS"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return when {
        !runtimePermission -> "Android 通知权限未开启，请点击去开启"
        !appSwitch -> "应用通知总开关未开启，请在应用通知页面打开"
        else -> "系统允许发送通知"
    }
}

private fun permissionItems(context: Context): List<PermissionItem> {
    val homeworkChannel = ReminderNotifier.homeworkChannelDiagnostics(context)
    val courseChannel = CourseNotification.channelDiagnostics(context)
    return listOf(
        PermissionItem(
            "通知总开关",
            notificationPermissionDetail(context),
            ReminderNotifier.canNotify(context),
            R.drawable.ic_settings_bell
        ) { openAppNotificationSettings(context) },
        PermissionItem(
            "作业通知权限",
            channelDetail("作业提醒", homeworkChannel),
            homeworkChannel.requiredReady,
            R.drawable.ic_nav_homework
        ) { openChannelSettings(context, ReminderNotifier.CHANNEL_ID) },
        PermissionItem(
            "课程通知权限",
            channelDetail("课程提醒", courseChannel),
            courseChannel.requiredReady,
            R.drawable.ic_nav_schedule
        ) { openChannelSettings(context, CourseNotification.CHANNEL_ID) },
        PermissionItem(
            "弹出/悬浮通知（可选）",
            floatingDetail(homeworkChannel, courseChannel),
            homeworkChannel.floatingEnabled && courseChannel.floatingEnabled,
            R.drawable.ic_settings_bell,
            optional = true
        ) { openAppNotificationSettings(context) },
        PermissionItem(
            if (ReminderScheduler.isExactMode(context)) "精确闹钟" else "精确闹钟（可选）",
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "当前 Android 版本无需单独授权"
                ReminderScheduler.canScheduleExact(context) -> "系统已允许精确闹钟"
                !ReminderScheduler.isExactMode(context) -> "普通提醒模式可继续使用；启用精确模式时再授权即可"
                else -> "精确模式已选，但系统尚未授权，提醒时间可能延迟"
            },
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ReminderScheduler.canScheduleExact(context),
            R.drawable.ic_settings_alarm,
            optional = !ReminderScheduler.isExactMode(context) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
            stateLabel = when {
                !ReminderScheduler.isExactMode(context) -> "普通模式"
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "无需授权"
                ReminderScheduler.canScheduleExact(context) -> "已授权"
                else -> "未授权"
            }
        ) { openExactAlarmSettings(context) },
        PermissionItem(
            "后台运行（可选）",
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations(context)) "电池优化已豁免" else "省电限制可能延迟提醒，可按需解除",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations(context),
            R.drawable.ic_settings_battery,
            optional = true
        ) { openBatterySettings(context) },
    )
}

private fun openAppNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

private fun openChannelSettings(context: Context, channel: String) {
    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    })
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}")))
}

@SuppressLint("BatteryLife")
private fun openBatterySettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}")))
    }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:${context.packageName}")))
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
