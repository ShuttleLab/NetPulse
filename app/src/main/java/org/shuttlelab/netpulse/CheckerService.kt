package org.shuttlelab.netpulse

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class CheckerService : Service() {

    companion object {
        const val CHANNEL_ID = "netpulse_status"
        const val NOTIF_ID = 1001

        // 连通性检测专用端点：返回 204 No Content，流量极小、不会触发验证码。
        // 这些域名在墙内均被封锁，能拿到 204 即证明翻墙真正生效。
        const val DEFAULT_URL = "https://www.google.com/generate_204"
        val FALLBACK_URLS = listOf(
            "https://www.google.com/generate_204",
            "https://clients3.google.com/generate_204"
        )
        // 旧版本默认值，迁移用
        const val LEGACY_DEFAULT_URL = "https://www.google.com"
        private const val PROBE_TIMEOUT_MS = 5000
        private const val IP_REFRESH_MS = 30_000L  // 出口 IP 最多每 30s 查一次，避免频繁请求
    }

    private lateinit var prefs: SharedPreferences
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null
    private var lastState: Boolean? = null  // null = unknown, true = success, false = fail
    private var lastIpFetch = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("netpulse", MODE_PRIVATE)
        migrateLegacyUrl()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification(getString(R.string.notif_checking), Color.GRAY)
        // Android 14 起前台服务需指定 type；用 3 参版本更稳妥。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        prefs.edit().putBoolean("running", true).apply()
        startChecking()
        return START_STICKY  // If killed, restart automatically
    }

    override fun onDestroy() {
        prefs.edit().putBoolean("running", false).apply()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun startChecking() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            while (isActive) {
                val primary = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
                val intervalSec = prefs.getInt("interval", 15).toLong()

                val (success, ping) = checkConnectivity(primary)
                maybeRefreshIp()
                // 记录下次检测时间，供主界面倒计时显示
                prefs.edit()
                    .putLong("next_check", System.currentTimeMillis() + intervalSec * 1000)
                    .apply()
                withContext(Dispatchers.Main) {
                    onCheckResult(success, ping)
                }

                delay(intervalSec * 1000)
            }
        }
    }

    /** 节流查询出口 IP，结果存入 prefs 供日志与主界面使用 */
    private fun maybeRefreshIp() {
        val now = System.currentTimeMillis()
        if (now - lastIpFetch < IP_REFRESH_MS) return
        lastIpFetch = now
        val info = IpLookup.fetch(IpLookup.preferChinese(prefs)) ?: return
        prefs.edit()
            .putString("last_ip", info.ip)
            .putString("last_region", info.region)
            .putString("last_isp", info.isp)
            .apply()
    }

    /**
     * 依次探测：先用用户配置的地址，失败再用内置的 Google 系备用端点。
     * 任意一个判定成功即认为翻墙可用，全部失败才算不可用。
     */
    private fun checkConnectivity(primary: String): Pair<Boolean, Long> {
        val urls = buildList {
            add(primary)
            FALLBACK_URLS.forEach { if (it != primary) add(it) }
        }
        for (u in urls) {
            val (ok, ping) = probe(u)
            if (ok) return Pair(true, ping)
        }
        return Pair(false, -1L)
    }

    private fun probe(urlString: String): Pair<Boolean, Long> {
        return try {
            val start = System.currentTimeMillis()
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false  // 重定向 = 劫持页/captive portal，不算真正连通
                useCaches = false
            }
            conn.connect()
            val code = conn.responseCode
            val ping = System.currentTimeMillis() - start
            conn.disconnect()
            // generate_204 端点必须返回精确的 204，避免劫持/注入页的 200 造成误判；
            // 用户若自定义了普通地址，则放宽为 2xx。
            val ok = if (urlString.endsWith("generate_204")) code == 204 else code in 200..299
            Pair(ok, ping)
        } catch (_: Exception) {
            Pair(false, -1L)
        }
    }

    private fun migrateLegacyUrl() {
        if (prefs.getString("url", null) == LEGACY_DEFAULT_URL) {
            prefs.edit().putString("url", DEFAULT_URL).apply()
        }
    }

    private fun onCheckResult(success: Boolean, ping: Long) {
        val stateChanged = lastState != null && lastState != success

        // 记录日志（保留条数可配置）并持久化最后一次结果，供主界面恢复时显示
        val now = System.currentTimeMillis()
        val ip = prefs.getString("last_ip", "") ?: ""
        val region = prefs.getString("last_region", "") ?: ""
        LogStore.append(this, CheckLog(now, success, ping, ip, region))
        prefs.edit()
            .putBoolean("last_success", success)
            .putLong("last_ping", ping)
            .putLong("last_time", now)
            .apply()

        // Notify main activity
        val broadcastIntent = Intent("org.shuttlelab.netpulse.STATUS_UPDATE").apply {
            putExtra("success", success)
            putExtra("ping", ping)
            `package` = packageName
        }
        sendBroadcast(broadcastIntent)

        // Update notification icon
        val color = if (success) Color.GREEN else Color.RED
        val label = if (success) {
            if (ping > 0) getString(R.string.notif_online_fmt, ping) else getString(R.string.notif_online)
        } else {
            getString(R.string.notif_offline)
        }
        val notif = buildNotification(label, color)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notif)

        // Alert if state just changed to failure
        if (stateChanged && !success) {
            alertUser()
        }

        lastState = success
    }

    private fun alertUser() {
        val vibrate = prefs.getBoolean("vibration", true)
        val sound = prefs.getBoolean("sound", false)

        if (vibrate) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 300, 200, 300, 200, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }

        if (sound) {
            try {
                val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(applicationContext, notification)
                ringtone.play()
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(text: String, dotColor: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // 状态栏小图标会被系统渲染成单色，无法区分颜色，故用不同「形状」区分状态：
        // 对勾=正常、叉号=断开、空心圆=检测中。
        val iconRes = when {
            dotColor == Color.GREEN -> R.drawable.ic_stat_connected
            dotColor == Color.RED -> R.drawable.ic_stat_disconnected
            else -> R.drawable.ic_stat_checking
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setColor(dotColor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 立即显示状态栏图标，绕过 Android 12+ 对前台服务通知最多 10s 的延迟
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
