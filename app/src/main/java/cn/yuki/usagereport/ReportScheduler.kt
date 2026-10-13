package cn.yuki.usagereport

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 配置存储（SharedPreferences）。App Secret 只存手机本地，不进源码。 */
object Prefs {
    private const val NAME = "cfg"
    const val KEY_APP_ID = "app_id"
    const val KEY_APP_SECRET = "app_secret"
    const val KEY_CHAT_ID = "chat_id"
    const val KEY_LAST_REPORT_DATE = "last_report_date"
    const val KEY_LAST_STATUS = "last_status"

    const val DEFAULT_APP_ID = "cli_aa4721d225795d0c"
    const val DEFAULT_CHAT_ID = "oc_471d9c88f660e52f9350267e215c4720"

    fun get(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun appId(context: Context): String =
        get(context).getString(KEY_APP_ID, DEFAULT_APP_ID) ?: DEFAULT_APP_ID

    fun appSecret(context: Context): String =
        get(context).getString(KEY_APP_SECRET, "") ?: ""

    fun chatId(context: Context): String =
        get(context).getString(KEY_CHAT_ID, DEFAULT_CHAT_ID) ?: DEFAULT_CHAT_ID

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

/** 每天 22:00 上报一次；开机、打开应用、每次上报后都会重新排下一次。 */
object ReportScheduler {

    private const val TAG = "ReportScheduler"
    const val REPORT_HOUR = 22

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReportReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = nextTriggerMillis()
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            Log.i(TAG, "下一次上报已排: ${Date(trigger)}")
        } catch (e: Exception) {
            Log.e(TAG, "排定闹钟失败", e)
        }
    }

    private fun nextTriggerMillis(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, REPORT_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    /** 真正执行上报（子线程里调用）。返回状态说明文字。 */
    fun doReport(context: Context): String {
        val secret = Prefs.appSecret(context)
        if (secret.isBlank()) {
            return "未填 App Secret，跳过上报"
        }
        if (!UsageReader.hasUsageAccess(context)) {
            return "未授予使用情况访问权限，跳过上报"
        }
        val usage = UsageReader.todayUsage(context)
        val text = FeishuSender.buildReport(usage)
        val msgId = FeishuSender.sendText(
            Prefs.appId(context),
            secret,
            Prefs.chatId(context),
            text
        )
        return "上报成功（${usage.date}，message_id=$msgId）"
    }
}

class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            try {
                val prefs = Prefs.get(context)
                val today = Prefs.today()
                if (prefs.getString(Prefs.KEY_LAST_REPORT_DATE, "") == today) {
                    Log.i("ReportReceiver", "今天已上报，跳过")
                } else {
                    val status = ReportScheduler.doReport(context)
                    Log.i("ReportReceiver", status)
                    val editor = prefs.edit().putString(Prefs.KEY_LAST_STATUS, status)
                    if (status.startsWith("上报成功")) {
                        editor.putString(Prefs.KEY_LAST_REPORT_DATE, today)
                    }
                    editor.apply()
                }
            } catch (e: Exception) {
                Log.e("ReportReceiver", "上报异常", e)
            } finally {
                ReportScheduler.scheduleNext(context)
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ReportScheduler.scheduleNext(context)
        }
    }
}
