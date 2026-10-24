package com.zhizhou.xxtassistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zhizhou.xxtassistant.data.FocusSettings
import com.zhizhou.xxtassistant.data.FocusSettingsStore
import com.zhizhou.xxtassistant.focus.FocusAccessibilityService

class FocusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FocusScreen() }
    }
}

private data class LaunchableApp(val packageName: String, val label: String)

private fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val matches = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return matches.mapNotNull {
        val packageName = it.activityInfo?.packageName ?: return@mapNotNull null
        if (packageName == context.packageName) return@mapNotNull null
        LaunchableApp(packageName, it.loadLabel(pm).toString())
    }.distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

private fun focusServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == FocusAccessibilityService::class.java.name
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusScreen() {
    val context = LocalContext.current
    val store = remember { FocusSettingsStore(context) }
    val apps = remember { launchableApps(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var limitText by remember { mutableStateOf(settings.dailyLimitMinutes.toString()) }
    var appPicker by remember { mutableStateOf(false) }
    var pendingEntertainment by remember { mutableStateOf(settings.entertainmentPackages) }
    var studyPicker by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val serviceReady = remember(refresh) { focusServiceEnabled(context) }
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }

    DisposableEffect(context) {
        val owner = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    fun saveConfiguration(): Boolean {
        val minutes = limitText.toIntOrNull()
        if (minutes == null || minutes !in 1..1440) {
            Toast.makeText(context, "每日时长请输入 1 至 1440 分钟", Toast.LENGTH_SHORT).show()
            return false
        }
        val updated = settings.copy(dailyLimitMinutes = minutes)
        if (updated.studyPackage in updated.entertainmentPackages) {
            Toast.makeText(context, "学习应用不能同时属于受限应用", Toast.LENGTH_SHORT).show()
            return false
        }
        settings = updated
        store.save(updated)
        Toast.makeText(context, "自律设置已保存", Toast.LENGTH_SHORT).show()
        return true
    }

    Scaffold(topBar = { TopAppBar(title = { Text("自律模式") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("把休息留在计划里", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "选择娱乐应用和学习应用。当天使用受限应用达到设定时长后，小笙会离开当前应用并打开学习应用。Android 不允许普通应用强制结束其他应用进程。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用自律模式", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                settings.enabled && serviceReady -> "运行中 · 每日用量仅保存在本机"
                                settings.enabled -> "等待开启无障碍服务"
                                else -> "已关闭，不会监测应用使用情况"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { enable ->
                        if (enable) {
                            val minutes = limitText.toIntOrNull()
                            if (!settings.configured || minutes == null || minutes !in 1..1440) {
                                Toast.makeText(context, "请先选择应用并保存有效的每日时长", Toast.LENGTH_SHORT).show()
                            } else {
                                disclosure = true
                            }
                        } else {
                            settings = settings.copy(enabled = false)
                            store.save(settings)
                        }
                    })
                }
            }
            item { HorizontalDivider() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("受限娱乐应用", style = MaterialTheme.typography.titleMedium)
                    Text(
                        settings.entertainmentPackages.map { byPackage[it]?.label ?: it }.sorted().joinToString("、").ifBlank { "尚未选择" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = {
                        pendingEntertainment = settings.entertainmentPackages
                        appPicker = true
                    }) { Text("选择应用") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("到时打开", style = MaterialTheme.typography.titleMedium)
                    Text(
                        settings.studyPackage?.let { byPackage[it]?.label ?: it } ?: "尚未选择学习应用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { studyPicker = true }) { Text("选择学习应用") }
                }
            }
            item {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { input -> limitText = input.filter(Char::isDigit).take(4) },
                    label = { Text("每日娱乐时长") },
                    suffix = { Text("分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Button(onClick = { saveConfiguration() }, modifier = Modifier.fillMaxWidth()) { Text("保存设置") } }
            if (settings.enabled && !serviceReady) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("前往开启自律无障碍服务") }
                        Text(
                            "在系统列表中选择小笙助手的自律模式。Android 13 及以上的侧载安装可能还要求你在应用信息中手动允许受限设置。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                Text(
                    "无障碍服务只识别你选择的应用包名和前台停留时长，不读取页面文字、输入内容，也不上传使用记录。你可以随时关闭。部分手机的省电策略可能影响服务运行。达到上限后若厂商阻止后台启动学习应用，日志会记录跳转失败。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }

    if (appPicker) {
        FocusAppPicker(
            title = "选择受限应用",
            apps = apps.filter { it.packageName != settings.studyPackage },
            selected = pendingEntertainment,
            multiple = true,
            onToggle = { packageName ->
                pendingEntertainment = if (packageName in pendingEntertainment) {
                    pendingEntertainment - packageName
                } else {
                    pendingEntertainment + packageName
                }
            },
            onConfirm = {
                val updated = settings.copy(entertainmentPackages = pendingEntertainment)
                settings = if (updated.configured) updated else updated.copy(enabled = false)
                store.save(settings)
                appPicker = false
            },
            onDismiss = { appPicker = false }
        )
    }
    if (studyPicker) {
        FocusAppPicker(
            title = "选择学习应用",
            apps = apps.filter { it.packageName !in settings.entertainmentPackages },
            selected = setOfNotNull(settings.studyPackage),
            multiple = false,
            onToggle = { packageName ->
                settings = settings.copy(studyPackage = packageName)
                store.save(settings)
                studyPicker = false
            },
            onConfirm = { studyPicker = false },
            onDismiss = { studyPicker = false }
        )
    }
    if (disclosure) {
        AlertDialog(
            onDismissRequest = { disclosure = false },
            title = { Text("启用前请确认") },
            text = { Text("自律模式会通过无障碍服务识别你选定的前台应用及停留时长。达到每日上限后会返回桌面并打开所选学习应用。它不会读取屏幕文字、代替你点击内容或上传记录。需要你在系统设置中手动开启服务。") },
            confirmButton = {
                Button(onClick = {
                    disclosure = false
                    settings = settings.copy(enabled = true)
                    store.save(settings)
                    if (!serviceReady) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("同意并继续") }
            },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun FocusAppPicker(
    title: String,
    apps: List<LaunchableApp>,
    selected: Set<String>,
    multiple: Boolean,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    query, { query = it },
                    label = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = app.packageName in selected, onCheckedChange = { onToggle(app.packageName) })
                            Text(app.label, fontWeight = if (app.packageName in selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!multiple) Text("选择一个学习应用", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onConfirm) { Text("完成") }
                }
            }
        }
    }
}
