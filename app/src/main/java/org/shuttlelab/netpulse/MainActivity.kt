package org.shuttlelab.netpulse

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** 折线图展示的最近检测次数 */
        private const val CHART_POINTS = 60
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var pingText: TextView
    private lateinit var regionText: TextView
    private lateinit var ipText: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var toggleBtn: Button
    private lateinit var settingsBtn: ImageView
    private lateinit var aboutBtn: ImageView
    private lateinit var countdownText: TextView
    private lateinit var logList: ListView
    private lateinit var logEmpty: TextView
    private lateinit var clearLogBtn: TextView
    private lateinit var latencyChart: LatencyChartView
    private lateinit var logAdapter: LogAdapter

    // 延迟着色：默认色不变，>500ms 黄、>1000ms 红
    private val pingDefaultTop = Color.parseColor("#CCCCCC")
    private val pingDefaultLog = Color.parseColor("#888888")
    private val pingWarn = Color.parseColor("#FFD600")
    private val pingBad = Color.parseColor("#FF5252")

    private fun latencyColor(ping: Long, default: Int): Int = when {
        ping > 1000 -> pingBad
        ping > 500 -> pingWarn
        else -> default
    }

    private var lastUiSuccess: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra("success", false)
            val ping = intent.getLongExtra("ping", -1)
            updateStatusUI(success, ping)
            updateControls()
            reloadLog()
            showCachedIp()
            // 连接状态切换时刷新出口 IP（翻墙开/关会改变出口 IP）
            if (lastUiSuccess != null && lastUiSuccess != success) refreshIp()
            lastUiSuccess = success
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets(findViewById(R.id.rootScroll))

        prefs = getSharedPreferences("netpulse", MODE_PRIVATE)

        // 迁移旧版默认地址（google.com 首页）到 generate_204 检测端点
        if (prefs.getString("url", null) == CheckerService.LEGACY_DEFAULT_URL) {
            prefs.edit().putString("url", CheckerService.DEFAULT_URL).apply()
        }

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        pingText = findViewById(R.id.pingText)
        regionText = findViewById(R.id.regionText)
        ipText = findViewById(R.id.ipText)
        refreshBtn = findViewById(R.id.refreshBtn)
        toggleBtn = findViewById(R.id.toggleBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        aboutBtn = findViewById(R.id.aboutBtn)
        countdownText = findViewById(R.id.countdownText)
        logList = findViewById(R.id.logList)
        logEmpty = findViewById(R.id.logEmpty)
        clearLogBtn = findViewById(R.id.clearLogBtn)
        latencyChart = findViewById(R.id.latencyChart)

        logAdapter = LogAdapter(emptyList())
        logList.adapter = logAdapter
        // 点击日志条目复制其出口 IP
        logList.setOnItemClickListener { _, _, position, _ ->
            copyIp(logAdapter.getItem(position).ip)
        }
        // 点击 IP 卡片复制当前出口 IP
        ipText.setOnClickListener { copyIp(prefs.getString("last_ip", "") ?: "") }

        toggleBtn.setOnClickListener { onToggle() }
        refreshBtn.setOnClickListener { onRefresh() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        aboutBtn.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        clearLogBtn.setOnClickListener {
            LogStore.clear(this)
            reloadLog()
        }

        requestNotificationPermission()

        showCachedIp()
        refreshIp()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("org.shuttlelab.netpulse.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        syncUi()
        reloadLog()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        handler.removeCallbacks(ticker)
    }

    private fun isRunning() = prefs.getBoolean("running", false)

    private fun onToggle() {
        if (isRunning()) stopChecker() else startChecker()
    }

    private fun startChecker() {
        prefs.edit().putBoolean("running", true).apply()  // 乐观置位，服务启动后也会写同样的值

        val intent = Intent(this, CheckerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, getString(R.string.toast_started), Toast.LENGTH_SHORT).show()
        statusDot.setBackgroundResource(R.drawable.dot_gray)
        statusText.text = getString(R.string.status_checking)
        pingText.text = ""
        updateControls()
    }

    private fun stopChecker() {
        stopService(Intent(this, CheckerService::class.java))
        prefs.edit().putBoolean("running", false).apply()
        statusDot.setBackgroundResource(R.drawable.dot_gray)
        statusText.text = getString(R.string.status_stopped)
        pingText.text = ""
        countdownText.text = ""
        lastUiSuccess = null
        updateControls()
        Toast.makeText(this, getString(R.string.toast_stopped), Toast.LENGTH_SHORT).show()
    }

    /** 按当前 running / 最近结果同步整个界面 */
    private fun syncUi() {
        updateControls()
        showCachedIp()
        if (isRunning()) {
            if (prefs.contains("last_time")) {
                updateStatusUI(prefs.getBoolean("last_success", false), prefs.getLong("last_ping", -1))
            } else {
                statusDot.setBackgroundResource(R.drawable.dot_gray)
                statusText.text = getString(R.string.status_checking)
                pingText.text = ""
            }
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_gray)
            statusText.text = getString(R.string.status_idle)
            pingText.text = ""
        }
    }

    private fun updateControls() {
        if (isRunning()) {
            toggleBtn.text = getString(R.string.btn_stop)
            toggleBtn.setTextColor(Color.WHITE)
            toggleBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
        } else {
            toggleBtn.text = getString(R.string.btn_start)
            toggleBtn.setTextColor(Color.BLACK)
            toggleBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        }
    }

    private fun updateStatusUI(success: Boolean, ping: Long) {
        if (success) {
            statusDot.setBackgroundResource(R.drawable.dot_green)
            statusText.text = getString(R.string.status_ok)
            pingText.text = if (ping > 0) getString(R.string.latency_fmt, ping) else ""
            pingText.setTextColor(latencyColor(ping, pingDefaultTop))
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_red)
            statusText.text = getString(R.string.status_fail)
            pingText.text = getString(R.string.conn_failed)
            pingText.setTextColor(pingDefaultTop)
        }
    }

    private fun updateCountdown() {
        if (!isRunning()) {
            countdownText.text = ""
            return
        }
        val next = prefs.getLong("next_check", 0L)
        val remain = (next - System.currentTimeMillis()) / 1000
        countdownText.text = if (remain in 0..86400) {
            getString(R.string.countdown_fmt, remain)
        } else {
            getString(R.string.status_checking)
        }
    }

    // ---------- 出口 IP ----------

    private fun showCachedIp() {
        val ip = prefs.getString("last_ip", "") ?: ""
        val region = prefs.getString("last_region", "") ?: ""
        val isp = prefs.getString("last_isp", "") ?: ""
        regionText.text = if (region.isNotBlank()) region else "--"
        ipText.text = if (ip.isNotBlank()) {
            listOf(ip, isp).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            getString(R.string.ip_unknown)
        }
    }

    /** 刷新：重查出口 IP，并在服务运行时立即触发一次连通性检测（刷新延迟/状态） */
    private fun onRefresh() {
        refreshIp()
        if (isRunning()) {
            statusText.text = getString(R.string.status_checking)
            // 重新投递 onStartCommand → 重启检测循环 → 立刻检测一次
            try {
                startService(Intent(this, CheckerService::class.java))
            } catch (_: Exception) {}
        }
    }

    private fun refreshIp() {
        ipText.text = getString(R.string.ip_querying)
        val chinese = IpLookup.preferChinese(prefs)
        Thread {
            val info = IpLookup.fetch(chinese)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info != null) {
                    prefs.edit()
                        .putString("last_ip", info.ip)
                        .putString("last_region", info.region)
                        .putString("last_isp", info.isp)
                        .apply()
                    showCachedIp()
                } else if ((prefs.getString("last_ip", "") ?: "").isBlank()) {
                    regionText.text = "--"
                    ipText.text = getString(R.string.ip_lookup_failed)
                } else {
                    showCachedIp()
                }
            }
        }.start()
    }

    private fun copyIp(ip: String) {
        if (ip.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_no_ip), Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ip", ip))
        Toast.makeText(this, getString(R.string.toast_ip_copied_fmt, ip), Toast.LENGTH_SHORT).show()
    }

    // ---------- 日志 ----------

    private fun reloadLog() {
        val items = LogStore.load(this)
        if (items.isEmpty()) {
            logEmpty.visibility = View.VISIBLE
            logList.visibility = View.GONE
            latencyChart.visibility = View.GONE
        } else {
            logEmpty.visibility = View.GONE
            logList.visibility = View.VISIBLE
            logAdapter.items = items
            logAdapter.notifyDataSetChanged()
            // 折线图：取最近若干次，按时间升序（最旧在左、最新在右）
            val recent = items.take(CHART_POINTS).reversed().map { if (it.ok) it.ping else -1L }
            if (recent.any { it > 0 }) {
                latencyChart.setData(recent)
                latencyChart.visibility = View.VISIBLE
            } else {
                latencyChart.visibility = View.GONE
            }
        }
    }

    private inner class LogAdapter(var items: List<CheckLog>) : BaseAdapter() {
        private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
            val e = items[position]
            v.findViewById<TextView>(R.id.logTime).text = fmt.format(Date(e.time))
            val res = v.findViewById<TextView>(R.id.logResult)
            if (e.ok) {
                res.text = getString(R.string.log_up)
                res.setTextColor(Color.parseColor("#00E676"))
            } else {
                res.text = getString(R.string.log_down)
                res.setTextColor(Color.parseColor("#FF5252"))
            }
            val pingView = v.findViewById<TextView>(R.id.logPing)
            pingView.text = if (e.ping > 0) "${e.ping}ms" else "--"
            // 复用视图必须每次显式设色，避免颜色串行
            pingView.setTextColor(latencyColor(e.ping, pingDefaultLog))

            val base = when {
                e.ip.isBlank() -> getString(R.string.log_ip_unknown)
                e.region.isBlank() -> e.ip
                else -> "${e.ip} · ${e.region}"
            }
            // 与时间上更早的一条（列表中下一项）相比，IP 是否发生变更
            val older = items.getOrNull(position + 1)
            val changed = e.ip.isNotBlank() && older != null &&
                older.ip.isNotBlank() && older.ip != e.ip
            val logIp = v.findViewById<TextView>(R.id.logIp)
            if (changed) {
                logIp.text = getString(R.string.log_ip_changed_fmt, base)
                logIp.setTextColor(Color.parseColor("#FFB300"))
            } else {
                logIp.text = base
                logIp.setTextColor(Color.parseColor("#666666"))
            }
            return v
        }
    }

    // ---------- 权限 ----------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
