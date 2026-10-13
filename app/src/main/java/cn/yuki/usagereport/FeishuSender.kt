package cn.yuki.usagereport

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** 以飞书自建应用身份把日报文本发进指定群。 */
object FeishuSender {

    private const val API = "https://open.feishu.cn"

    private fun postJson(url: String, body: JSONObject, token: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return JSONObject(text)
    }

    private fun tenantToken(appId: String, appSecret: String): String {
        val body = JSONObject()
            .put("app_id", appId)
            .put("app_secret", appSecret)
        val resp = postJson("$API/open-apis/auth/v3/tenant_access_token/internal", body, null)
        if (resp.optInt("code") != 0) {
            throw IllegalStateException("获取 token 失败: ${resp.optString("msg")}")
        }
        return resp.getString("tenant_access_token")
    }

    /** 发送一条文本消息到群，成功返回飞书的 message_id。 */
    fun sendText(appId: String, appSecret: String, chatId: String, text: String): String {
        val token = tenantToken(appId, appSecret)
        val content = JSONObject().put("text", text)
        val body = JSONObject()
            .put("receive_id", chatId)
            .put("msg_type", "text")
            .put("content", content.toString())
        val resp = postJson(
            "$API/open-apis/im/v1/messages?receive_id_type=chat_id",
            body,
            token
        )
        if (resp.optInt("code") != 0) {
            throw IllegalStateException("发送失败: ${resp.optString("msg")} (code=${resp.optInt("code")})")
        }
        return resp.optJSONObject("data")?.optString("message_id") ?: ""
    }

    /** 把 DayUsage 组装成日报文本（人看的 + 一行机器可读的 USAGE_JSON）。 */
    fun buildReport(usage: UsageReader.DayUsage): String {
        val sb = StringBuilder()
        sb.append("📱 屏幕使用日报 ").append(usage.date).append('\n')
        sb.append("总时长：").append(UsageReader.formatDuration(usage.totalMs)).append('\n')
        for (app in usage.top) {
            sb.append(app.label).append(' ').append(UsageReader.formatDuration(app.foregroundMs)).append('\n')
        }
        val topArr = JSONArray()
        for (app in usage.top) {
            topArr.put(
                JSONArray()
                    .put(app.label)
                    .put((app.foregroundMs / 60_000L).toInt())
            )
        }
        val json = JSONObject()
            .put("date", usage.date)
            .put("total_min", (usage.totalMs / 60_000L).toInt())
            .put("top", topArr)
        sb.append("USAGE_JSON:").append(json.toString())
        return sb.toString()
    }
}
