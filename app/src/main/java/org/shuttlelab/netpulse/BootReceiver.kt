package org.shuttlelab.netpulse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val prefs = context.getSharedPreferences("vpnchecker", Context.MODE_PRIVATE)
            // 仅当关机/重启前服务确实在运行时才自动重启，避免用户停掉后又被拉起
            if (prefs.getBoolean("running", false)) {
                val serviceIntent = Intent(context, CheckerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
