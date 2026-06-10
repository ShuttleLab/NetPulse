package org.shuttlelab.netpulse

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 出口 IP 信息 */
data class IpInfo(val ip: String, val region: String, val isp: String)

/**
 * 通过 ip9.com.cn 查询当前出口公网 IP 及归属地。
 * 必须在后台线程调用（内部走网络请求）。
 */
object IpLookup {
    private const val API = "https://ip9.com.cn/get"

    fun fetch(): IpInfo? {
        return try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val ip = data.optString("ip")
            if (ip.isBlank()) return null
            val region = listOf(
                data.optString("country"),
                data.optString("prov"),
                data.optString("city")
            ).filter { it.isNotBlank() }.joinToString(" ")
            IpInfo(ip, region, data.optString("isp"))
        } catch (_: Exception) {
            null
        }
    }
}
