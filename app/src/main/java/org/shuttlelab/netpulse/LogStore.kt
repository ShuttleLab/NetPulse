package org.shuttlelab.netpulse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一次检测记录 */
data class CheckLog(
    val time: Long,
    val ok: Boolean,
    val ping: Long,
    val ip: String,
    val region: String
)

/**
 * 检测日志存储：保留最近 N 条记录（N 可在设置中调整），
 * 存在 SharedPreferences 里（JSON 数组，最新的在前）。
 */
object LogStore {
    private const val PREFS = "vpnchecker"
    private const val KEY = "check_log"
    const val DEFAULT_KEEP = 100
    const val MIN_KEEP = 10
    const val MAX_KEEP = 1000

    private fun keepLimit(prefs: android.content.SharedPreferences) =
        prefs.getInt("log_keep", DEFAULT_KEEP).coerceIn(MIN_KEEP, MAX_KEEP)

    fun append(context: Context, entry: CheckLog) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val obj = JSONObject()
            .put("t", entry.time)
            .put("ok", entry.ok)
            .put("p", entry.ping)
            .put("ip", entry.ip)
            .put("r", entry.region)
        // 最新的放最前面
        val out = JSONArray()
        out.put(obj)
        val limit = minOf(arr.length(), keepLimit(prefs) - 1)
        for (i in 0 until limit) out.put(arr.getJSONObject(i))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun load(context: Context): List<CheckLog> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val list = ArrayList<CheckLog>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CheckLog(
                    o.getLong("t"),
                    o.getBoolean("ok"),
                    o.getLong("p"),
                    o.optString("ip"),
                    o.optString("r")
                )
            )
        }
        return list
    }

    /** 设置调小保留条数后，立即裁剪现有记录 */
    fun trimToLimit(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val limit = keepLimit(prefs)
        if (arr.length() <= limit) return
        val out = JSONArray()
        for (i in 0 until limit) out.put(arr.getJSONObject(i))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
