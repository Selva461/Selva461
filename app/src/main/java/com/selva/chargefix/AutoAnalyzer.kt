package com.selva.chargefix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationCompat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Runs everything without the user: exact scans, battery-swap detection, results
 * and a daily report - delivered as notifications. A system job every 3 hours,
 * a few seconds each time.
 */
object AutoAnalyzer {
    private const val JOB_ID = 78
    private const val CH_REPORT = "report"
    private const val CH_SWAP = "swap"
    private const val ID_DAILY = 40
    private const val ID_SWAP = 41
    private const val OFF_GAP_MS = 20 * 60_000L

    private fun watch(c: Context) = c.getSharedPreferences("swap_watch", Context.MODE_PRIVATE)
    private fun state(c: Context) = c.getSharedPreferences("auto_state", Context.MODE_PRIVATE)

    fun schedule(c: Context) {
        BatteryLog.schedule(c)
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) == null) {
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, Job::class.java))
                .setPeriodic(3 * 3_600_000L).setPersisted(true).build())
        }
    }

    /**
     * After every start-up: if the phone was off for 20+ minutes (a battery swap needs that),
     * remember the battery as it was just before, and start watching.
     */
    fun onBoot(c: Context) {
        schedule(c)
        val last = BatteryLog.load(c, System.currentTimeMillis() - 30L * 86_400_000).lastOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - last.t < OFF_GAP_MS) return
        val h = HealthStore.prefs(c)
        val e = watch(c).edit().clear()
        e.putLong("offAt", last.t).putLong("onAt", now)
        if (h.contains("cycles")) e.putInt("cycles", h.getInt("cycles", 0))
        HealthStore.capacity(c)?.let { e.putInt("cap", it) }
        e.putString("ids", h.getString("ids", null))
        BatteryStats.drainBetween(c, last.t - 7L * 86_400_000, last.t)?.let { e.putFloat("drain", it.toFloat()) }
        e.putString("verdict", ReplacementCheck.Verdict.WAIT.name).apply()
        BatteryLog.record(c)
    }

    /** One background pass. Blocking; runs on the job's thread. */
    fun runOnce(c: Context) {
        BatteryLog.record(c)
        // Fresh exact numbers about twice a day, only if the no-Wi-Fi link is up (no prompts, no Wi-Fi needed).
        val st = state(c)
        if (System.currentTimeMillis() - st.getLong("scanAt", 0) > 10 * 3_600_000L && AdbShell.isPaired(c)) {
            runCatching {
                if (AdbShell.localReady(c) || AdbShell.onWifi(c)) {
                    HealthStore.scan(c); st.edit().putLong("scanAt", System.currentTimeMillis()).apply()
                }
            }
        }
        evaluateSwap(c, notify = true)
        dailyReport(c)
    }

    /** Status line for the app screen, or null when nothing is being watched. */
    fun swapStatus(c: Context): String? {
        val w = watch(c)
        if (!w.contains("onAt")) return null
        return evaluateSwap(c, notify = false)
    }

    private fun evaluateSwap(c: Context, notify: Boolean): String? {
        val w = watch(c)
        if (!w.contains("onAt")) return null
        val onAt = w.getLong("onAt", 0)
        val days = (System.currentTimeMillis() - onAt) / 86_400_000.0
        if (days > 10) { w.edit().clear().apply(); return null }
        val h = HealthStore.prefs(c)
        val scannedAfter = h.getLong("at", 0) > onAt
        val out = ReplacementCheck.evaluate(ReplacementCheck.Input(
            designMah = if (h.contains("design")) h.getInt("design", 0) else BatteryStats.report(c).designMah,
            cyclesBefore = if (w.contains("cycles")) w.getInt("cycles", 0) else null,
            cyclesNow = if (scannedAfter && h.contains("cycles")) h.getInt("cycles", 0) else null,
            capBefore = if (w.contains("cap")) w.getInt("cap", 0) else null,
            capNow = if (scannedAfter) HealthStore.capacity(c) else BatteryStats.capacitySince(c, onAt),
            idBefore = w.getString("ids", null), idNow = if (scannedAfter) h.getString("ids", null) else null,
            drainBefore = if (w.contains("drain")) w.getFloat("drain", 0f).toDouble() else null,
            drainAfter = BatteryStats.drainBetween(c, onAt, System.currentTimeMillis()),
            daysSince = days,
            fullChargesSince = BatteryStats.fullChargesSince(c, onAt),
        ))
        val offTxt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(w.getLong("offAt", 0)))
        val text = "Phone was switched off on $offTxt. Battery replaced?\n" + (out.lines + "" + out.summary).joinToString("\n")
        val prev = w.getString("verdict", null)
        if (notify && out.verdict.name != prev && out.verdict != ReplacementCheck.Verdict.WAIT) {
            post(c, CH_SWAP, ID_SWAP, out.summary, text)
        }
        w.edit().putString("verdict", out.verdict.name).apply()
        return text
    }

    private fun dailyReport(c: Context) {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val st = state(c)
        if (cal.get(Calendar.HOUR_OF_DAY) < 19 || st.getInt("reportDay", 0) == today) return
        val r = BatteryStats.report(c)
        val health = HealthStore.healthPct(c)
        val title = "Battery today: ${r.usedToday}% used" + (health?.let { " · health $it%" } ?: "")
        val body = buildString {
            append("Used today: ${r.usedToday}% of a full charge.")
            r.drainPctPerHour?.let { append("\nDrains ${"%.1f".format(it)}%/hour → a full charge lasts ~${(100 / it).toInt()} h.") }
            health?.let { append("\nBattery health: $it%" + if (it < 80) " - replacement worth it." else ".") }
            HealthStore.prefs(c).takeIf { it.contains("cycles") }?.let { append("\nCharge cycles: ${it.getInt("cycles", 0)}.") }
            val sp = BatteryStats.screenSplit(c, System.currentTimeMillis() - 86_400_000L)
            sp.onRate?.let { append("\nScreen on: ${"%.1f".format(it)}%/h") }
            sp.offRate?.let { append(if (sp.onRate != null) " · " else "\n"); append("screen off: ${"%.1f".format(it)}%/h") }
            if (r.fakeFixes > 0) append("\nFake-charging fixes so far: ${r.fakeFixes}.")
        }
        post(c, CH_REPORT, ID_DAILY, title, body)
        st.edit().putInt("reportDay", today).apply()
    }

    private fun post(c: Context, ch: String, id: Int, title: String, body: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REPORT, "Daily battery report", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SWAP, "Battery replacement results", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(id, NotificationCompat.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(android.app.PendingIntent.getActivity(c, id,
                android.content.Intent(c, HealthActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    class Job : JobService() {
        override fun onStartJob(params: JobParameters?): Boolean {
            Thread {
                runCatching { runOnce(this) }
                jobFinished(params, false)
            }.start()
            return true
        }
        override fun onStopJob(params: JobParameters?) = true
    }
}
