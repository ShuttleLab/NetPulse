package org.shuttlelab.netpulse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val prefs = context.getSharedPreferences("netpulse", Context.MODE_PRIVATE)
            // 仅当关机/重启前服务确实在运行时才自动重启，避免用户停掉后又被拉起
            if (prefs.getBoolean("running", false)) {
                val serviceIntent = Intent(context, CheckerService::class.java)
                // Android 12+ 从后台/开机启动前台服务可能抛 ForegroundServiceStartNotAllowedException，
                // 捕获以免开机崩溃；拉不起时用户回 App 手动启动即可。
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
