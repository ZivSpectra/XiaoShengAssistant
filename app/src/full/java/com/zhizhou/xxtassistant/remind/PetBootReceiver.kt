package com.zhizhou.xxtassistant.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.zhizhou.xxtassistant.data.PetSettingsStore

/** 设备重启/应用升级后，在用户之前开启且仍有权限时恢复桌宠。 */
class PetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!PetSettingsStore(context).isEnabled() || !Settings.canDrawOverlays(context)) return
        val service = Intent(context, PetOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
        else context.startService(service)
    }
}
