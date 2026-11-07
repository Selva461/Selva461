package com.selva.chargefix

import android.content.Context

/** Latest battery-health numbers (summary only) + the scan that produces them. Used by UI and background jobs. */
object HealthStore {
    fun prefs(c: Context) = c.getSharedPreferences("health_last", Context.MODE_PRIVATE)

    fun save(c: Context, r: BugreportParser.Result, source: String) {
        val e = prefs(c).edit().clear()
        fun put(k: String, v: Int?) { if (v != null) e.putInt(k, v) }
        put("cycles", r.cycleCount); put("full", r.fullChargeMah); put("design", r.designMah)
        put("learned", r.learnedMah); put("soh", r.sohPct); put("volt", r.voltageMv)
        r.tempMaxC?.let { e.putFloat("temp", it.toFloat()) }
        r.drainPctPerHour?.let { e.putFloat("drain", it.toFloat()) }
        e.putString("cycleSrc", r.cycleSource); e.putString("fullSrc", r.fullChargeSource)
        e.putString("designSrc", r.designSource); e.putString("health", r.healthText)
        e.putString("model", r.model); e.putString("source", source)
        e.putString("ids", r.ids.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        e.putLong("at", System.currentTimeMillis()).apply()
    }

    /** Reads the phone's battery records over the app's own debug connection. Blocking. */
    fun scan(c: Context): BugreportParser.Result {
        val text = AdbShell.session(c) { sh ->
            val nodes = BatteryStats.NODES.joinToString("; ") { "[ -r $it ] && echo \"$it=\$(cat $it)\"" } +
                "; for f in /sys/class/oplus_chg/battery/* /sys/class/power_supply/battery/*; do " +
                "[ -f \$f ] && [ -r \$f ] && v=\$(timeout 1 head -c 64 \$f 2>/dev/null | tr '\\n' ' ') && echo \"\$f=\$v\"; done"
            sh.run("getprop | grep -E 'ro.product.model|ro.build.version.release'; dumpsys battery; " +
                "logcat -d -b all 2>/dev/null | grep -iE 'healthd|cyclecount|battery_cc|battery_fcc|battery_soh' | tail -n 300; " +
                "$nodes; dumpsys batterystats 2>/dev/null; true")
        }
        val r = BugreportParser.parseText(text)
        save(c, r, "automatic scan")
        return r
    }

    /** Capacity (mAh) for the battery right now: phone-reported, else learned, else measured. */
    fun capacity(c: Context): Int? {
        val p = prefs(c)
        return if (p.contains("full")) p.getInt("full", 0) else if (p.contains("learned")) p.getInt("learned", 0)
        else BatteryStats.report(c).capacityMah
    }

    fun healthPct(c: Context): Int? {
        val p = prefs(c)
        if (p.contains("soh")) return p.getInt("soh", 0)
        val design = if (p.contains("design")) p.getInt("design", 0) else BatteryStats.report(c).designMah
        return capacity(c)?.let { (it * 100 / design).coerceAtMost(100) }
    }
}
