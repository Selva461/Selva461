package com.selva.chargefix

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything shown on the Battery Health screen, worked out from the log + the phone. */
data class StatsReport(
    val designMah: Int,
    val designSource: String,
    val capacityMah: Int?,       // what a full battery holds today
    val capacitySource: String,
    val healthPct: Int?,
    val cyclesPhone: Int?,       // reported by the phone
    val cyclesApp: Double,       // counted by this app since install
    val drainPctPerHour: Double?,
    val usedToday: Int,
    val fullChargeHours: Double?,
    val chargePctPerMin: Double?,
    val fakeFixes: Int,
    val trackedDays: Double,
    val dailyUse: List<Pair<String, Int>>, // last 7 days, label -> % used
    val avgTempC: Double?,
)

object BatteryStats {
    /** OnePlus 9R (LE2100/LE2101): 4500 mAh (2 x 2250). */
    private fun knownDesignMah(): Int? =
        if (Build.MODEL.startsWith("LE210") || Build.MODEL.contains("9R")) 4500 else null

    private fun prefs(c: Context) = c.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun countFakeFix(c: Context) =
        prefs(c).edit().putInt("fake_fixes", prefs(c).getInt("fake_fixes", 0) + 1).apply()

    // ------------------------------------------------------------- exact numbers from the phone

    /** Places phones keep battery facts. OnePlus/OPlus keep exact cycle count & capacity. */
    val NODES = listOf(
        "/sys/class/oplus_chg/battery/battery_cc",
        "/sys/class/oplus_chg/battery/battery_fcc",
        "/sys/class/oplus_chg/battery/battery_soh",
        "/sys/class/oplus_chg/battery/design_capacity",
        "/sys/class/power_supply/battery/cycle_count",
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/battery/battery_cc",
        "/sys/class/power_supply/battery/battery_fcc",
        "/sys/class/power_supply/battery/battery_soh",
        "/sys/class/power_supply/bms/cycle_count",
        "/sys/class/power_supply/bms/charge_full",
        "/sys/class/power_supply/bms/charge_full_design",
    )

    /**
     * Reads those files - directly if allowed, otherwise over the app's own ADB connection
     * (shell can read more). Saves whatever it finds. Blocking.
     */
    fun readExactFromPhone(ctx: Context): String {
        val found = mutableMapOf<String, String>()
        NODES.forEach { p -> runCatching { File(p).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { found[p] = it } }
        var note = ""
        if (found.size < NODES.size && AdbShell.isPaired(ctx)) {
            try {
                val out = AdbShell.session(ctx) { sh ->
                    sh.run(NODES.joinToString("; ") { "[ -r $it ] && echo \"$it=\$(cat $it)\"" } + "; true")
                }
                out.lineSequence().forEach { l ->
                    val k = l.substringBefore('=', ""); val v = l.substringAfter('=', "").trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) found[k] = v
                }
            } catch (e: Exception) {
                note = " (debug connection: ${e.message})"
            }
        }
        val e = prefs(ctx).edit()
        fun num(vararg keys: String) = keys.firstNotNullOfOrNull { k -> found.entries.firstOrNull { it.key.endsWith(k) }?.value?.toLongOrNull() }
        num("battery_cc", "cycle_count")?.let { e.putInt("cycles", it.toInt()) }
        num("battery_fcc", "charge_full")?.let { e.putInt("fcc", mah(it)) }
        num("design_capacity", "charge_full_design")?.let { e.putInt("design", mah(it)) }
        num("battery_soh")?.let { e.putInt("soh", it.toInt()) }
        e.putLong("exact_at", System.currentTimeMillis()).apply()
        return if (found.isEmpty()) "Your phone didn't share exact numbers$note. Showing the app's own measurements."
        else "Read ${found.size} value(s) from the phone ✓"
    }

    private fun mah(v: Long) = (if (v > 50_000) v / 1000 else v).toInt()

    // ------------------------------------------------------------- the report

    fun report(ctx: Context): StatsReport {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val log = BatteryLog.load(ctx, now - 30L * 86_400_000)

        // Design capacity
        val design = p.getInt("design", 0).takeIf { it > 1000 }
        val designMah = design ?: knownDesignMah() ?: 4500
        val designSource = when {
            design != null -> "reported by phone"
            knownDesignMah() != null -> "OnePlus 9R spec"
            else -> "assumed"
        }

        // Capacity today: phone's own figure, else estimate from charge counter / percentage.
        val fcc = p.getInt("fcc", 0).takeIf { it > 500 }
        val estimates = log.filter { it.chargeMah > 0 && it.level in 30..95 }
            .map { it.chargeMah * 100.0 / it.level }
            .filter { it in 1000.0..8000.0 }
            .takeLast(200)
        val estimate = if (estimates.size >= 3) estimates.sorted()[estimates.size / 2].roundToInt() else null
        val capacity = fcc ?: estimate ?: liveEstimate(ctx)
        val capSource = when {
            fcc != null -> "reported by phone"
            estimate != null -> "measured over ${estimates.size} readings"
            capacity != null -> "rough, from one reading"
            else -> "not available yet"
        }
        val soh = p.getInt("soh", 0).takeIf { it in 1..120 }
        val health = soh ?: capacity?.let { (it * 100.0 / designMah).roundToInt().coerceAtMost(100) }

        // Cycles: phone (Android 14 extra or OnePlus file), and our own count.
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val apiCycles = sticky?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it > 0 }
        val cyclesPhone = p.getInt("cycles", 0).takeIf { it > 0 } ?: apiCycles

        val all = BatteryLog.load(ctx)
        var charged = 0; var used = 0
        var drainPct = 0.0; var drainMs = 0L
        var chgPct = 0.0; var chgMs = 0L
        for (k in 1 until all.size) {
            val a = all[k - 1]; val b = all[k]
            val dt = b.t - a.t
            if (dt <= 0 || dt > 45 * 60_000) continue // gap (phone off / job skipped)
            val d = b.level - a.level
            if (a.plugged && b.plugged && d > 0) { charged += d; chgPct += d; chgMs += dt }
            if (!a.plugged && !b.plugged) {
                if (d < 0) used -= d
                drainPct += -d.toDouble(); drainMs += dt
            }
        }
        val drainRate = if (drainMs > 3 * 3600_000L) drainPct / (drainMs / 3600_000.0) else null
        val chargeRate = if (chgMs > 20 * 60_000L) chgPct / (chgMs / 60_000.0) else null

        // Per-day use, last 7 days
        val cal = Calendar.getInstance()
        val days = (6 downTo 0).map { back ->
            cal.timeInMillis = now; cal.add(Calendar.DAY_OF_YEAR, -back)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val label = if (back == 0) "Today" else
                cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault()) ?: "-$back"
            label to usedBetween(all, start, start + 86_400_000)
        }

        val first = all.firstOrNull()?.t
        return StatsReport(
            designMah, designSource, capacity, capSource, health, cyclesPhone,
            charged / 100.0, drainRate, days.last().second,
            drainRate?.let { 100 / max(it, 0.1) }, chargeRate,
            p.getInt("fake_fixes", 0),
            first?.let { (now - it) / 86_400_000.0 } ?: 0.0,
            days,
            log.takeLast(500).map { it.tempC.toDouble() }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /** % per hour lost while unplugged between two times; null if under 6 hours of data. */
    fun drainBetween(ctx: Context, from: Long, to: Long): Double? {
        val log = BatteryLog.load(ctx, from).filter { it.t <= to }
        var drop = 0.0; var ms = 0L
        for (k in 1 until log.size) {
            val a = log[k - 1]; val b = log[k]
            if (a.plugged || b.plugged || b.t - a.t > 45 * 60_000) continue
            drop += (a.level - b.level); ms += b.t - a.t
        }
        return if (ms >= 6 * 3_600_000L && drop > 0) drop / (ms / 3_600_000.0) else null
    }

    /** Capacity measured only from readings after a time (e.g. after a battery swap). */
    fun capacitySince(ctx: Context, from: Long): Int? {
        val est = BatteryLog.load(ctx, from).filter { it.chargeMah > 0 && it.level in 30..95 }
            .map { it.chargeMah * 100.0 / it.level }.filter { it in 1000.0..8000.0 }
        return if (est.size >= 3) est.sorted()[est.size / 2].roundToInt() else null
    }

        /** How many times the battery reached ~100% while charging since a time. */
    fun fullChargesSince(ctx: Context, from: Long): Int {
        var n = 0; var armed = true
        BatteryLog.load(ctx, from).forEach { e ->
            if (e.plugged && e.level >= 98 && armed) { n++; armed = false }
            if (e.level < 90) armed = true
        }
        return n
    }

    private fun usedBetween(all: List<LogEntry>, from: Long, to: Long): Int {
        var used = 0
        for (k in 1 until all.size) {
            val a = all[k - 1]; val b = all[k]
            if (b.t < from || a.t >= to) continue
            if (b.t - a.t > 45 * 60_000) continue
            if (!a.plugged && !b.plugged && b.level < a.level) used += a.level - b.level
        }
        return used
    }

    private fun liveEstimate(ctx: Context): Int? {
        val c = BatteryLog.chargeMah(ctx)
        val lvl = BatteryReader.read(ctx).level
        return if (c > 0 && lvl in 30..95) (c * 100.0 / lvl).roundToInt().takeIf { it in 1000..8000 } else null
    }
}
