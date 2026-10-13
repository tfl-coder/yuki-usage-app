package cn.yuki.usagereport

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var etAppId: EditText
    private lateinit var etSecret: EditText
    private lateinit var etChat: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etAppId = findViewById(R.id.etAppId)
        etSecret = findViewById(R.id.etSecret)
        etChat = findViewById(R.id.etChat)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = Prefs.get(this)
        etAppId.setText(prefs.getString(Prefs.KEY_APP_ID, ""))
        etSecret.setText(prefs.getString(Prefs.KEY_APP_SECRET, ""))
        etChat.setText(prefs.getString(Prefs.KEY_CHAT_ID, ""))
        refreshStatus()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "打开失败，请到系统设置里手动找「使用情况访问」", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnSendNow).setOnClickListener {
            save()
            tvStatus.text = "状态：上报中…"
            Thread {
                val status = try {
                    val s = ReportScheduler.doReport(this)
                    if (s.startsWith("上报成功")) {
                        Prefs.get(this).edit()
                            .putString(Prefs.KEY_LAST_REPORT_DATE, Prefs.today())
                            .apply()
                    }
                    s
                } catch (e: Exception) {
                    "上报失败：${e.message}"
                }
                Prefs.get(this).edit().putString(Prefs.KEY_LAST_STATUS, status).apply()
                runOnUiThread {
                    refreshStatus()
                    Toast.makeText(this, status, Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        // 每次打开应用都确保今晚的闹钟在（后台被杀后的自愈）
        ReportScheduler.scheduleNext(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun save() {
        Prefs.get(this).edit()
            .putString(Prefs.KEY_APP_ID, etAppId.text.toString().trim())
            .putString(Prefs.KEY_APP_SECRET, etSecret.text.toString().trim())
            .putString(Prefs.KEY_CHAT_ID, etChat.text.toString().trim())
            .apply()
    }

    private fun refreshStatus() {
        if (!::tvStatus.isInitialized) return
        val access = if (UsageReader.hasUsageAccess(this)) "已授权" else "未授权"
        val appId = if (Prefs.appId(this).isBlank()) "未填" else "已填"
        val secret = if (Prefs.appSecret(this).isBlank()) "未填" else "已填"
        val chat = if (Prefs.chatId(this).isBlank()) "未填" else "已填"
        val last = Prefs.get(this).getString(Prefs.KEY_LAST_STATUS, "—")
        tvStatus.text = "状态：使用情况访问=$access ｜ App ID=$appId ｜ App Secret=$secret ｜ 群 ID=$chat ｜ 上次上报：$last"
    }
}
