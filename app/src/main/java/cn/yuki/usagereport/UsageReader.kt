package cn.yuki.usagereport

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 读取今日各应用前台使用时长（UsageStatsManager，需要“使用情况访问”授权）。 */
object UsageReader {

    data class AppUsage(val label: String, val packageName: String, val foregroundMs: Long)

    data class DayUsage(val date: String, val totalMs: Long, val top: List<AppUsage>)

    /** 是否已拿到使用情况访问权限。 */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 今天（本地时间 00:00 起）各应用前台时长，按时长降序。 */
    fun todayUsage(context: Context): DayUsage {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val begin = cal.timeInMillis
        val end = System.currentTimeMillis()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)

        val merged = HashMap<String, Long>()
        for (s in stats) {
            if (s.totalTimeInForeground > 0) {
                merged[s.packageName] = (merged[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
        }

        val pm = context.packageManager
        val launcherPkg = resolveLauncherPackage(pm)
        val skip = setOf("android", "com.android.systemui", context.packageName, launcherPkg)

        val list = merged.entries
            .filter { it.key !in skip && it.value >= 60_000L } // 只统计 >= 1 分钟
            .map { (pkg, ms) -> AppUsage(labelOf(pm, pkg), pkg, ms) }
            .sortedByDescending { it.foregroundMs }

        val total = list.sumOf { it.foregroundMs }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(end))
        return DayUsage(date = date, totalMs = total, top = list.take(5))
    }

    private fun labelOf(pm: PackageManager, pkg: String): String {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun resolveLauncherPackage(pm: PackageManager): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = pm.resolveActivity(intent, 0)
        return resolve?.activityInfo?.packageName
    }

    fun formatDuration(ms: Long): String {
        val totalMin = (ms / 60_000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}小时${m}分" else "${m}分"
    }
}
