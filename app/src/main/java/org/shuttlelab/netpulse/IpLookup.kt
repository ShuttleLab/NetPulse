package org.shuttlelab.netpulse

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 出口 IP 信息 */
data class IpInfo(val ip: String, val region: String, val isp: String)

/**
 * 查询当前出口公网 IP 及归属地。必须在后台线程调用（内部走网络请求）。
 *
 * 归属地随界面语言切换数据源：中文用 ip9.com.cn（返回中文地名），
 * 英文用 ip.im（返回英文地名）。主源失败时回退到另一个，保证至少能拿到 IP。
 */
object IpLookup {
    private const val IP9_API = "https://ip9.com.cn/get"
    private const val IPIM_API = "https://ip.im/info"

    /** 按 lang 偏好判断是否走中文数据源；"system" 时看系统语言 */
    fun preferChinese(prefs: SharedPreferences): Boolean =
        when (prefs.getString("lang", "system")) {
            "zh" -> true
            "en" -> false
            else -> Locale.getDefault().language.startsWith("zh")
        }

    fun fetch(chinese: Boolean): IpInfo? =
        if (chinese) {
            fetchIp9() ?: fetchIpIm()
        } else {
            fetchIpIm() ?: fetchIp9()
        }

    /** ip9.com.cn：JSON，中文地名 */
    private fun fetchIp9(): IpInfo? = request(IP9_API) { body ->
        val data = JSONObject(body).optJSONObject("data") ?: return@request null
        val ip = data.optString("ip")
        if (ip.isBlank()) return@request null
        val region = listOf(
            data.optString("country"),
            data.optString("prov"),
            data.optString("city")
        ).filter { it.isNotBlank() }.joinToString(" ")
        IpInfo(ip, region, data.optString("isp"))
    }

    /**
     * ip.im/info：纯文本，英文地名。正文为 `Key:Value` 行（外加无关的 ASCII 横幅），
     * 逐行按首个冒号拆分即可，忽略不带冒号的横幅行。
     */
    private fun fetchIpIm(): IpInfo? = request(IPIM_API) { body ->
        val map = HashMap<String, String>()
        body.lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val key = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
            }
        }
        val ip = map["Ip"].orEmpty()
        if (ip.isBlank()) return@request null
        val region = listOf(map["Country"], map["Region"], map["City"])
            .filterNot { it.isNullOrBlank() }
            .distinct()
            .joinToString(" ")
        // Org 形如 "AS21859 Zenlayer Inc"，去掉前导 ASN 与 ip9 的 isp 风格保持一致
        val isp = map["Org"].orEmpty().replace(Regex("^AS\\d+\\s+"), "")
        IpInfo(ip, region, isp)
    }

    private inline fun request(api: String, parse: (String) -> IpInfo?): IpInfo? {
        return try {
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
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
            parse(body)
        } catch (_: Exception) {
            null
        }
    }
}
