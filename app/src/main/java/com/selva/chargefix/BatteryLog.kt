package com.selva.chargefix

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/** One stored reading. */
data class LogEntry(
    val t: Long,
    val level: Int,
    val plugged: Boolean,
    val tempC: Float,
    val voltageMv: Int,
    /** Charge left in the battery, mAh (from the charge counter), or -1. */
    val chargeMah: Int,
    /** Screen on? null = unknown (older readings). */
    val screenOn: Boolean? = null,
)

/**
 * Tiny on-device battery history (CSV in the app's private folder, max 90 days).
 * A system job records one line every ~15 minutes - it wakes for a few
 * milliseconds and costs practically no battery.
 */
object BatteryLog {
    private const val FILE = "battery_log.csv"
    private const val KEEP_MS = 90L * 24 * 3600_000
    private const val JOB_ID = 77

    @Synchronized
    fun record(ctx: Context) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (lvl < 0 || scale <= 0) return
        val level = lvl * 100 / scale
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val f = File(ctx.filesDir, FILE)
        val screen = if (ctx.getSystemService(android.os.PowerManager::class.java).isInteractive) 1 else 0
        val line = "${System.currentTimeMillis()},$level,${if (plugged) 1 else 0},$temp,$volt,${chargeMah(ctx)},$screen\n"
        f.appendText(line)
        if (f.length() > 600_000) prune(f)
    }

    /** Remaining charge in mAh from the battery's charge counter, or -1 if not reported. */
    fun chargeMah(ctx: Context): Int {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val c = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (c <= 0 || c == Int.MIN_VALUE) return -1
        // Spec says µAh; some phones report mAh.
        return if (c > 50_000) c / 1000 else c
    }

    @Synchronized
    fun load(ctx: Context, sinceMs: Long = 0): List<LogEntry> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 6) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            if (t < sinceMs) return@mapNotNull null
            LogEntry(t, p[1].toIntOrNull() ?: return@mapNotNull null, p[2] == "1",
                p[3].toFloatOrNull() ?: 0f, p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: -1,
                p.getOrNull(6)?.let { s -> if (s == "1") true else if (s == "0") false else null })
        }
    }

    /**
     * Merges past readings (e.g. from Android's battery history) into the log. Only fills gaps:
     * anything within 7 minutes of an existing reading is skipped. Returns how many were added.
     */
    @Synchronized
    fun importEntries(ctx: Context, entries: List<LogEntry>): Int {
        if (entries.isEmpty()) return 0
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val existing = load(ctx)
        val times = existing.map { it.t }.sorted()
        fun near(t: Long): Boolean {
            val i = times.binarySearch(t).let { if (it < 0) -it - 1 else it }
            return (i < times.size && times[i] - t < 7 * 60_000) || (i > 0 && t - times[i - 1] < 7 * 60_000)
        }
        val add = entries.filter { it.t in cutoff..System.currentTimeMillis() && !near(it.t) }
        if (add.isEmpty()) return 0
        val all = (existing + add).sortedBy { it.t }
        File(ctx.filesDir, FILE).writeText(all.joinToString("") { e ->
            "${e.t},${e.level},${if (e.plugged) 1 else 0},${e.tempC},${e.voltageMv},${e.chargeMah}," +
                "${when (e.screenOn) { true -> "1"; false -> "0"; null -> "-" }}\n"
        })
        return add.size
    }

    private fun prune(f: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0) >= cutoff }
        f.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, LogJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
            .build())
        record(ctx)
    }

    class LogJob : JobService() {
        override fun onStartJob(params: JobParameters?): Boolean {
            runCatching { record(this) }
            return false
        }
        override fun onStopJob(params: JobParameters?) = true
    }
}
