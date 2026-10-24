package com.zhizhou.xxtassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zhizhou.xxtassistant.data.PetSettingsStore
import com.zhizhou.xxtassistant.remind.PetOverlayService

object EditionFeatures {
    const val available = true

    @Composable
    fun Screen(context: Context) {
        var refresh by remember { mutableIntStateOf(0) }
        var petDialog by remember { mutableStateOf(false) }
        val petStore = remember { PetSettingsStore(context) }
        val overlayAllowed = remember(refresh) { Settings.canDrawOverlays(context) }
        val batteryReady = remember(refresh) { isIgnoringBatteryOptimizations(context) }
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh++
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("可选功能", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { petDialog = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("小笙桌宠", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            !overlayAllowed -> "需要悬浮窗权限"
                            petStore.isEnabled() && batteryReady -> "已开启"
                            petStore.isEnabled() -> "后台省电限制可能影响运行"
                            else -> "未开启"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("设置 ›", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            TextButton(onClick = { context.startActivity(Intent(context, FocusActivity::class.java)) }) {
                Text("自律模式")
            }
            Text("自律模式正在优化中。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("其他高级功能暂未开放。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (petDialog) {
            PetFeatureDialog(
                context = context,
                enabled = petStore.isEnabled() && overlayAllowed,
                overlayAllowed = overlayAllowed,
                batteryReady = batteryReady,
                onRefresh = { refresh++ },
                onDismiss = { petDialog = false }
            )
        }
    }
}

@Composable
private fun PetFeatureDialog(
    context: Context,
    enabled: Boolean,
    overlayAllowed: Boolean,
    batteryReady: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val store = remember { PetSettingsStore(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小笙桌宠") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开启桌宠", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { requested ->
                        when {
                            !requested -> {
                                store.setEnabled(false)
                                PetOverlayService.stop(context)
                                onRefresh()
                            }
                            !overlayAllowed -> context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                            else -> {
                                store.setEnabled(true)
                                PetOverlayService.startIfPermitted(context)
                                onRefresh()
                            }
                        }
                    })
                }
                Text("桌宠会显示为可拖动的透明人物立绘。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    },
                    enabled = !overlayAllowed
                ) { Text(if (overlayAllowed) "悬浮窗已允许" else "开启悬浮窗权限") }
                OutlinedButton(onClick = { openBatterySettings(context) }, enabled = !batteryReady) {
                    Text(if (batteryReady) "后台限制已解除" else "检查后台运行限制")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
        context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun openBatterySettings(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
