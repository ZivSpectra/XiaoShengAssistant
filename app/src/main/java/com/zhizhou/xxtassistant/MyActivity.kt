package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.zhizhou.xxtassistant.data.PetSettingsStore
import com.zhizhou.xxtassistant.data.AccountProfileStore
import com.zhizhou.xxtassistant.remind.PetOverlayService
import com.zhizhou.xxtassistant.data.ViewSettings
import com.zhizhou.xxtassistant.remind.CourseNotification
import com.zhizhou.xxtassistant.data.HomeworkCache
import com.zhizhou.xxtassistant.data.GithubReleaseChecker
import com.zhizhou.xxtassistant.data.ManualHomeworkStore
import com.zhizhou.xxtassistant.remind.ReminderDispatch
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
    val optional: Boolean = false,
    val open: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun MyScreen(accountId: String) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var displayDialog by remember { mutableStateOf(false) }
    var reminderDialog by remember { mutableStateOf(false) }
    var otherDialog by remember { mutableStateOf(false) }
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
    val missingNotificationCount = permissionItems.take(3).count { !it.ready }
    val missingEnhancementCount = permissionItems.drop(3).count { !it.ready && it.optional }
    val missingRequiredCount = permissionItems.count { !it.ready && !it.optional }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) },
        bottomBar = {
            XxtBottomNavigation(selectedTab = 3) { tab ->
                when (tab) {
                    0 -> context.navigateToTab(TodayActivity::class.java)
                    1 -> context.navigateToTab(CourseTableActivity::class.java)
                    2 -> context.navigateToTab(MainActivity::class.java)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(com.zhizhou.xxtassistant.R.mipmap.ic_launcher_foreground),
                            "小笙助手图标，双击复制诊断日志",
                            Modifier.height(42.dp).padding(end = 10.dp).combinedClickable(
                                onClick = {},
                                onDoubleClick = { copyDiagnostics() }
                            )
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("小笙助手", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "学习通账号：${AccountProfileStore(context).displayName(accountId) ?: "姓名暂未同步"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("数据只保存在本机，设置会立即生效。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { SettingsSectionTitle("版本") }
            item {
                SettingsActionCard(
                    title = "GitHub 版本检查",
                    detail = updateText,
                    action = if (checkingUpdate) "检查中" else "检查"
                ) {
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
            item { SettingsSectionTitle("显示与提醒") }
            item {
                SettingsActionCard(
                    title = "显示设置",
                    detail = "列表模式、课程分组和双列排列",
                    action = "打开"
                ) { displayDialog = true }
            }
            item {
                SettingsActionCard(
                    title = "作业提醒",
                    detail = "截止节点和精确闹钟模式",
                    action = "打开"
                ) { reminderDialog = true }
            }
            item { SettingsSectionTitle("其他功能") }
            item {
                SettingsActionCard("桌宠与扩展功能", "桌宠、绘图等可选功能，不属于基础权限", "进入") { otherDialog = true }
            }
            item { SettingsSectionTitle("权限与后台运行") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        missingNotificationCount > 0 -> "有 $missingNotificationCount 项通知显示设置需要处理"
                                        missingRequiredCount > 0 -> "通知显示已就绪，仍有提醒设置需处理"
                                        else -> "通知显示设置已就绪"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    when {
                                        missingRequiredCount > 0 && missingEnhancementCount > 0 -> "红色状态是当前待处理项；另有 $missingEnhancementCount 项后台增强可按需开启"
                                        missingRequiredCount > 0 -> "红色状态会标出当前待处理项；点击对应按钮可直达设置"
                                        missingEnhancementCount > 0 -> "有 $missingEnhancementCount 项后台增强未开启，不影响基础通知；弹出通知可保持关闭"
                                        else -> "弹出通知可以按需关闭；锁屏显示状态按各通知频道分别检查"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (missingRequiredCount > 0) {
                                    TextButton(onClick = {
                                        permissionItems.firstOrNull { !it.ready && !it.optional }?.open?.invoke()
                                    }) { Text("处理缺失项") }
                                }
                                TextButton(onClick = { refresh++ }) { Text("重新检查") }
                            }
                        }
                        permissionItems.forEach { item -> PermissionRow(item) }
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("首次权限引导", style = MaterialTheme.typography.titleSmall)
                                Text(if (skipGuide) "已关闭自动提醒，可在这里恢复" else "未关闭，缺少权限时会提示一次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = !skipGuide,
                                onCheckedChange = { enabled ->
                                    skipGuide = !enabled
                                    context.getSharedPreferences("xxt_reminder_onboarding", Context.MODE_PRIVATE)
                                        .edit().putBoolean("skip_permission_guide", !enabled).apply()
                                }
                            )
                        }
                    }
                }
            }
            item { SettingsSectionTitle("账户") }
            item {
                SettingsActionCard("退出登录", "清除当前学习通会话，保留本地课程和作业", "退出") {
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle("退出登录")
                        .setMessage("只清除登录凭证，当前账号的作业和课程数据会保留。")
                        .setPositiveButton("退出") { _, _ ->
                            AccountManager(context).logout()
                            com.zhizhou.xxtassistant.remind.ReminderScheduler.cancelAll(context)
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
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (displayDialog) {
        DisplaySettingsDialog(onDismiss = { displayDialog = false })
    }
    if (reminderDialog) {
        ReminderSettingsDialog(onDismiss = { reminderDialog = false })
    }
    if (otherDialog) {
        OtherFeaturesDialog(onDismiss = { otherDialog = false })
    }
}

@androidx.compose.runtime.Composable
private fun OtherFeaturesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf(false) }
    var requestOverlayAfterBattery by remember { mutableStateOf(false) }
    val petStore = remember { PetSettingsStore(context) }
    val overlayGranted = remember(refresh) { Settings.canDrawOverlays(context) }
    val batteryReady = remember(refresh) { isIgnoringBatteryOptimizations(context) }
    val enabled = petStore.isEnabled() && overlayGranted
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(pending, batteryReady, overlayGranted) {
        if (pending && batteryReady && overlayGranted) {
            petStore.setEnabled(true)
            PetOverlayService.startIfPermitted(context)
            pending = false
            requestOverlayAfterBattery = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("其他功能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这些功能按需开启，不会影响作业和课程提醒。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("小笙桌宠")
                        Text(
                            when {
                                enabled && batteryReady -> "已开启"
                                petStore.isEnabled() && !overlayGranted -> "等待悬浮窗授权"
                                petStore.isEnabled() && !batteryReady -> "等待解除后台限制"
                                else -> "未开启"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { requested ->
                        if (!requested) {
                            pending = false
                            requestOverlayAfterBattery = false
                            petStore.setEnabled(false)
                            PetOverlayService.stop(context)
                            refresh++
                        } else if (!batteryReady) {
                            pending = true
                            requestOverlayAfterBattery = true
                            openBatterySettings(context)
                        } else if (!overlayGranted) {
                            pending = true
                            openOverlaySettings(context)
                        } else {
                            petStore.setEnabled(true)
                            PetOverlayService.startIfPermitted(context)
                            refresh++
                        }
                    })
                }
                Text("开启桌宠前需要允许小笙在后台运行，并授予悬浮窗权限。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { openBatterySettings(context) }, enabled = !batteryReady) { Text(if (batteryReady) "后台限制已解除" else "解除后台限制") }
                OutlinedButton(
                    onClick = { pending = true; requestOverlayAfterBattery = false; openOverlaySettings(context) },
                    enabled = !overlayGranted && (batteryReady || !requestOverlayAfterBattery)
                ) { Text(if (overlayGranted) "悬浮窗已允许" else "开启悬浮窗") }
                if (pending && requestOverlayAfterBattery && batteryReady && !overlayGranted) {
                    Button(onClick = { requestOverlayAfterBattery = false; openOverlaySettings(context) }) {
                        Text("下一步：开启悬浮窗")
                    }
                }
                HorizontalDivider()
                Text("Plotforge 绘图", style = MaterialTheme.typography.titleSmall)
                Text("高级绘图模块将在独立页面接入，当前不会伪装成已可用功能。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@androidx.compose.runtime.Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@androidx.compose.runtime.Composable
private fun SettingsActionCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionRow(item: PermissionItem) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    item.ready -> "已开启"
                    item.optional -> "可选"
                    else -> "未开启"
                },
                color = when {
                    item.ready -> MaterialTheme.colorScheme.primary
                    item.optional -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.labelMedium
            )
            if (item.ready) {
                TextButton(onClick = item.open) { Text("查看") }
            } else {
                // Optional rows still expose the system page, but never count
                // as a missing prerequisite for the rest of the app.
                OutlinedButton(onClick = item.open) { Text(if (item.optional) "去设置" else "去开启") }
            }
        }
    }
}

private fun channelDetail(label: String, state: ReminderChannelDiagnostics): String {
    val notification = if (state.notificationEnabled) "频道允许通知" else "频道通知已关闭"
    val lockscreen = if (state.lockscreenEnabled) "频道未屏蔽锁屏展示" else "频道禁止锁屏展示"
    val floating = if (state.floatingEnabled) "频道支持高优先级弹出" else "频道未设高优先级（可选）"
    val sound = if (state.soundEnabled) "声音已开启" else "声音未开启（可选）"
    return "$label：$notification · $lockscreen · $floating；横幅是否显示还受手机系统控制；$sound"
}

private fun floatingDetail(homework: ReminderChannelDiagnostics, course: ReminderChannelDiagnostics): String {
    val homeworkText = if (homework.floatingEnabled) "作业频道为高优先级" else "作业频道不是高优先级"
    val courseText = if (course.floatingEnabled) "课程频道为高优先级" else "课程频道不是高优先级"
    return "$homeworkText · $courseText；横幅显示由系统控制，不想弹出时可保持关闭"
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
            ReminderNotifier.canNotify(context)
        ) { openAppNotificationSettings(context) },
        PermissionItem(
            "作业提醒频道",
            channelDetail("作业提醒", homeworkChannel),
            homeworkChannel.requiredReady
        ) { openChannelSettings(context, ReminderNotifier.CHANNEL_ID) },
        PermissionItem(
            "课程提醒频道",
            channelDetail("课程提醒", courseChannel),
            courseChannel.requiredReady
        ) { openChannelSettings(context, CourseNotification.CHANNEL_ID) },
        PermissionItem(
            "弹出/悬浮通知（可选）",
            floatingDetail(homeworkChannel, courseChannel),
            homeworkChannel.floatingEnabled && courseChannel.floatingEnabled,
            optional = true
        ) { openAppNotificationSettings(context) },
        PermissionItem(
            "精确闹钟",
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "当前 Android 版本无需单独授权"
                ReminderScheduler.canScheduleExact(context) -> "系统已允许精确闹钟"
                !ReminderScheduler.isExactMode(context) -> "普通提醒模式可继续使用；启用精确模式时再授权即可"
                else -> "精确模式已选，但系统尚未授权，提醒时间可能延迟"
            },
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ReminderScheduler.canScheduleExact(context),
            optional = !ReminderScheduler.isExactMode(context) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) { openExactAlarmSettings(context) },
        PermissionItem(
            "后台运行",
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations(context)) "电池优化已豁免" else "可减少省电策略对后台提醒的影响；不想豁免也可继续使用",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations(context),
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

@androidx.compose.runtime.Composable
private fun DisplaySettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ViewSettings(context) }
    var mode by remember { mutableIntStateOf(store.mode) }
    var layout by remember { mutableIntStateOf(store.layout) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("列表模式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mode = ViewSettings.MODE_FLAT }) { Text(if (mode == ViewSettings.MODE_FLAT) "✓ 默认列表" else "默认列表") }
                    OutlinedButton(onClick = { mode = ViewSettings.MODE_GROUP }) { Text(if (mode == ViewSettings.MODE_GROUP) "✓ 按课程分组" else "按课程分组") }
                }
                Text("分组后的课程卡片布局")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { layout = ViewSettings.LAYOUT_SINGLE }) { Text(if (layout == ViewSettings.LAYOUT_SINGLE) "✓ 单列" else "单列") }
                    OutlinedButton(enabled = mode == ViewSettings.MODE_GROUP, onClick = { layout = ViewSettings.LAYOUT_DOUBLE }) { Text(if (layout == ViewSettings.LAYOUT_DOUBLE) "✓ 双列" else "双列") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                store.mode = mode
                store.layout = if (mode == ViewSettings.MODE_GROUP) layout else ViewSettings.LAYOUT_SINGLE
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@androidx.compose.runtime.Composable
private fun ReminderSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accountId = remember { com.zhizhou.xxtassistant.data.AccountManager(context).getActiveUid().orEmpty() }
    val presetNodes = linkedMapOf(
        24 * 60 to "提前 1 天",
        12 * 60 to "提前 12 小时",
        3 * 60 to "提前 3 小时",
        60 to "提前 1 小时",
        30 to "提前 30 分钟",
        15 to "提前 15 分钟"
    )
    var nodes by remember { mutableStateOf(ReminderScheduler.loadNodes(context).toSet()) }
    var exact by remember { mutableStateOf(ReminderScheduler.isExactMode(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作业提醒") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("选择截止前的提醒节点。关闭全部节点后，不会发送作业提醒。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("精确闹钟模式", Modifier.weight(1f))
                    Switch(checked = exact, onCheckedChange = { exact = it })
                }
                HorizontalDivider()
                presetNodes.forEach { (minutes, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = minutes in nodes,
                            onCheckedChange = { checked ->
                                nodes = if (checked) nodes + minutes else nodes - minutes
                            }
                        )
                        Text(label)
                    }
                }
                val custom = nodes.filter { it !in presetNodes.keys }.sorted()
                if (custom.isNotEmpty()) {
                    Text("自定义提醒", style = MaterialTheme.typography.titleSmall)
                    custom.forEach { minutes ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("提前 ${formatReminderMinutes(minutes)}", Modifier.weight(1f))
                            TextButton(onClick = { nodes = nodes - minutes }) { Text("删除") }
                        }
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showCustomReminderPicker(
                            context = context,
                            existing = nodes,
                            preset = presetNodes.keys,
                            onAdded = { nodes = nodes + it }
                        )
                    }
                ) { Text("添加自定义提前时间") }
            }
        },
        confirmButton = {
            Button(onClick = {
                ReminderScheduler.saveNodes(context, nodes.sorted().toIntArray())
                ReminderScheduler.saveExactMode(context, exact)
                if (accountId.isNotBlank()) {
                    ReminderDispatch.enqueueHomework(context, accountId) {
                        HomeworkCache(context).load(accountId) + ManualHomeworkStore(context).list(accountId)
                    }
                }
                ReminderDispatch.enqueueAllCourses(context)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** A compact, touch-friendly wheel editor for one custom reminder node. */
@Suppress("DiscouragedApi")
private fun showCustomReminderPicker(
    context: Context,
    existing: Set<Int>,
    preset: Set<Int>,
    onAdded: (Int) -> Unit
) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    fun picker(max: Int, initial: Int): NumberPicker = NumberPicker(context).apply {
        minValue = 0
        maxValue = max
        value = initial.coerceIn(0, max)
        wrapSelectorWheel = false
        setFormatter { "%02d".format(java.util.Locale.CHINA, it) }
        layoutParams = LinearLayout.LayoutParams(dp(82), dp(142))
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        resources.getIdentifier("numberpicker_input", "id", "android")
            .takeIf { it != 0 }
            ?.let { findViewById<EditText>(it) }
            ?.apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                isFocusable = true
                isFocusableInTouchMode = true
                setSelectAllOnFocus(true)
            }
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(2), dp(12), 0)
    }
    root.addView(android.widget.TextView(context).apply {
        text = "上下滑动选择，也可以点击数字直接输入"
        textSize = 13f
    })
    val wheels = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    val days = picker(7, 0)
    val hours = picker(23, 0)
    val minutes = picker(59, 30)
    listOf("天" to days, "小时" to hours, "分钟" to minutes).forEach { (label, numberPicker) ->
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(numberPicker)
        column.addView(android.widget.TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
        })
        wheels.addView(column)
    }
    root.addView(wheels)
    val manual = EditText(context).apply {
        hint = "或输入总分钟数，例如 90"
        inputType = InputType.TYPE_CLASS_NUMBER
        maxLines = 1
    }
    root.addView(manual)

    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("自定义提前时间")
        .setView(root)
        .setPositiveButton("添加") { _, _ ->
            val typed = manual.text.toString().trim().toIntOrNull()
            val total = typed ?: days.value * 24 * 60 + hours.value * 60 + minutes.value
            when {
                total !in 1..(7 * 24 * 60) ->
                    android.widget.Toast.makeText(context, "请输入 1 分钟至 7 天之间的时间", android.widget.Toast.LENGTH_SHORT).show()
                total in existing || total in preset ->
                    android.widget.Toast.makeText(context, "这个提醒时间已经在列表里了", android.widget.Toast.LENGTH_SHORT).show()
                existing.count { it !in preset } >= 8 ->
                    android.widget.Toast.makeText(context, "自定义提醒最多保存 8 个", android.widget.Toast.LENGTH_SHORT).show()
                else -> onAdded(total)
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun formatReminderMinutes(value: Int): String = when {
    value % 1440 == 0 -> "${value / 1440}天前"
    value % 60 == 0 -> "${value / 60}小时前"
    else -> "${value}分钟前"
}
