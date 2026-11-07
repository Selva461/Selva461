package com.selva.chargefix

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Battery health diagnostics: cycles, capacity, health %, drain - from the phone or a bug report. */
class HealthActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView

    private val pickReport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) parseReport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health)
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<MaterialButton>(R.id.btnScan).setOnClickListener { scanPhone() }
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.rangeGroup)
            .addOnButtonCheckedListener { _, _, checked -> if (checked) renderCharts() }
        findViewById<MaterialButton>(R.id.btnBefore).setOnClickListener { saveBefore() }
        findViewById<MaterialButton>(R.id.btnAfter).setOnClickListener {
            scanPhone { renderReplace(checkNow = true) }
        }
        findViewById<MaterialButton>(R.id.btnBugreport).setOnClickListener {
            pickReport.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/plain", "application/octet-stream"))
        }
        BatteryLog.schedule(this)
        render()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------------ data sources

    private fun scanPhone(then: () -> Unit = {}) {
        if (!AdbShell.isPaired(this)) {
            tvStatus.text = "Scan needs no-root mode set up (main screen → Set up no-root mode). Or open a bug report instead."
            then()
            return
        }
        tvStatus.text = "Reading battery records…"
        io.execute {
            val msg = try {
                val text = AdbShell.session(this) { sh ->
                    val nodes = BatteryStats.NODES.joinToString("; ") { "[ -r $it ] && echo \"$it=\$(cat $it)\"" } +
                        // Every small readable file in the battery folders (finds IDs, serials, extra counters).
                        "; for f in /sys/class/oplus_chg/battery/* /sys/class/power_supply/battery/*; do " +
                        "[ -f \$f ] && [ -r \$f ] && v=\$(timeout 1 head -c 64 \$f 2>/dev/null | tr '\\n' ' ') && echo \"\$f=\$v\"; done"
                    sh.run("getprop | grep -E 'ro.product.model|ro.build.version.release'; dumpsys battery; " +
                        "logcat -d -b all 2>/dev/null | grep -iE 'healthd|cyclecount|battery_cc|battery_fcc|battery_soh' | tail -n 300; " +
                        "$nodes; dumpsys batterystats 2>/dev/null; true")
                }
                save(BugreportParser.parseText(text), "phone scan")
                "Scanned ✓"
            } catch (e: Exception) {
                "Scan failed: ${e.message ?: e}"
            }
            ui.post { tvStatus.text = msg; render(); then() }
        }
    }

    private fun parseReport(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "report"
        tvStatus.text = "Reading $name… (big reports take up to a minute)"
        io.execute {
            val msg = try {
                val isZip = name.endsWith(".zip", true) || contentResolver.getType(uri)?.contains("zip") == true
                val r = contentResolver.openInputStream(uri)!!.use { input ->
                    BugreportParser.parse(input, isZip) { n -> ui.post { tvStatus.text = "Reading $name… ${n / 1000}k lines" } }
                }
                if (r.linesRead < 100) "That doesn't look like a bug report." else {
                    save(r, "bug report"); "Read ${r.linesRead / 1000}k lines from $name ✓"
                }
            } catch (e: Exception) {
                "Couldn't read it: ${e.message ?: e}"
            }
            ui.post { tvStatus.text = msg; render() }
        }
    }

    private fun prefs() = getSharedPreferences("health_last", Context.MODE_PRIVATE)

    /** Keeps only the summary numbers - the report itself is never stored. */
    private fun save(r: BugreportParser.Result, source: String) {
        val e = prefs().edit().clear()
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

    // ------------------------------------------------------------------ screen

    private fun render() {
        val p = prefs()
        fun i(k: String) = if (p.contains(k)) p.getInt(k, 0) else null
        val live = BatteryStats.report(this) // app's own measurements (no setup needed)

        val design = i("design") ?: live.designMah
        val full = i("full") ?: i("learned") ?: live.capacityMah
        val fullSrc = when {
            i("full") != null -> p.getString("fullSrc", null)
            i("learned") != null -> "Android's learned capacity"
            else -> live.capacitySource
        }
        val health = i("soh") ?: full?.let { (it * 100.0 / design).roundToInt().coerceAtMost(100) }
        val cycles = i("cycles") ?: live.cyclesPhone
        val drain = if (p.contains("drain")) p.getFloat("drain", 0f).toDouble() else live.drainPctPerHour

        // Hero
        val (label, color, textColor) = when {
            health == null -> Triple("Not measured yet", 0xFF6E7781.toInt(), Color.WHITE)
            health >= 85 -> Triple("✓ Good", 0xFF0CA30C.toInt(), Color.WHITE)
            health >= 75 -> Triple("! Worn - okay for now", 0xFFFAB219.toInt(), Color.BLACK)
            else -> Triple("✗ Weak - replace soon", 0xFFD03B3B.toInt(), Color.WHITE)
        }
        findViewById<MaterialCardView>(R.id.cardHero).setCardBackgroundColor(color)
        listOf(R.id.tvHealthPct, R.id.tvHealthLabel, R.id.tvHealthSub).forEach { findViewById<TextView>(it).setTextColor(textColor) }
        findViewById<TextView>(R.id.tvHealthPct).text = health?.let { "$it%" } ?: "—"
        findViewById<TextView>(R.id.tvHealthLabel).text = label
        findViewById<TextView>(R.id.tvHealthSub).text = if (full != null)
            "A full charge holds about ${fmt(full)} mAh. New, it held ${fmt(design)} mAh.\nSource: $fullSrc"
        else "Tap \"Scan phone now\" or open a bug report. The app also measures it by itself over the next day."

        // Tiles
        val tiles = findViewById<LinearLayout>(R.id.tiles)
        tiles.removeAllViews()
        val items = listOf(
            Tile("Charge cycles", cycles?.toString() ?: "—",
                cycles?.let { "${p.getString("cycleSrc", null) ?: "phone"} · most batteries wear out around 500–800" }
                    ?: "Counted by the app so far: ${"%.1f".format(live.cyclesApp)}"),
            Tile("Battery when new", "${fmt(design)} mAh", p.getString("designSrc", null) ?: live.designSource),
            Tile("Drain per hour", drain?.let { "%.1f%%".format(it) } ?: "—",
                drain?.let { "A full charge lasts ~${(100 / it).roundToInt()} h of normal use" } ?: "Needs a few hours of data"),
            Tile("Used today", "${live.usedToday}%", "Tracked by the app (unplugged time)"),
            Tile("Hottest seen", if (p.contains("temp")) "%.0f °C".format(p.getFloat("temp", 0f)) else live.avgTempC?.let { "%.0f °C avg".format(it) } ?: "—",
                "Over 40 °C often ages a battery faster"),
            Tile("Fake-charging fixes", live.fakeFixes.toString(), "Done by Charge Fix"),
        )
        items.chunked(2).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { k, t -> line.addView(tileView(t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (k == 0) marginEnd = dp(6) else marginStart = dp(6); bottomMargin = dp(12)
            }) }
            if (row.size == 1) line.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
            tiles.addView(line)
        }

        findViewById<TextView>(R.id.tvVerdict).text = verdict(health, cycles, drain)
        renderCharts()
        findViewById<TextView>(R.id.tvReport).text = analytics(live)
        renderReplace(checkNow = false)
        if (p.contains("at") && tvStatus.text.isNullOrEmpty()) {
            tvStatus.text = "Last read from ${p.getString("source", "")}: " +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.getLong("at", 0)))
        }
    }

    private fun verdict(health: Int?, cycles: Int?, drain: Double?): String = when {
        health == null -> "Not enough data yet. Tap \"Scan phone now\", or open a bug report."
        health < 70 -> "Yes. Your battery holds only $health% of its original charge. A new battery will feel like a new phone."
        health < 80 -> "Probably. At $health% you lose about ${100 - health}% of your battery life. Replace it if the phone doesn't last a day."
        health < 85 -> "Not yet. At $health% it's worn but fine. Check again in a few months."
        else -> "No. At $health% your battery is healthy." +
            (if ((drain ?: 0.0) > 8) " The fast drain is more likely from apps or screen brightness than the battery." else "")
    } + (cycles?.let { if (it > 800) " It has also done $it cycles, which is a lot." else "" } ?: "")

    // ------------------------------------------------------------------ charts + report

    private fun renderCharts() {
        val now = System.currentTimeMillis()
        val week = findViewById<MaterialButton>(R.id.btn7d).isChecked
        val from = now - (if (week) 7L else 1L) * 86_400_000
        findViewById<LevelChartView>(R.id.levelChart).setData(BatteryLog.load(this, from), from, now)
        findViewById<DailyBarView>(R.id.dailyBars).setData(BatteryStats.report(this).dailyUse)
    }

    private fun analytics(live: StatsReport): String {
        val log = BatteryLog.load(this, System.currentTimeMillis() - 7L * 86_400_000)
        if (log.size < 8) return "The app is collecting data (one reading every 15 minutes). " +
            "Come back in a few hours for a full report."
        val days = live.dailyUse.map { it.second }.filter { it > 0 }
        var plugIns = 0; var nightDrop = 0.0; var nightMs = 0L
        val cal = java.util.Calendar.getInstance()
        for (k in 1 until log.size) {
            val a = log[k - 1]; val b = log[k]
            if (!a.plugged && b.plugged) plugIns++
            cal.timeInMillis = a.t
            val night = cal.get(java.util.Calendar.HOUR_OF_DAY) in 1..5
            if (night && !a.plugged && !b.plugged && b.t - a.t < 45 * 60_000) {
                nightDrop += (a.level - b.level).coerceAtLeast(0); nightMs += b.t - a.t
            }
        }
        val spanDays = ((log.last().t - log.first().t) / 86_400_000.0).coerceAtLeast(0.1)
        val temps = log.map { it.tempC }.filter { it > 0 }
        val lines = mutableListOf<String>()
        if (days.isNotEmpty()) lines += "• You use about ${days.average().roundToInt()}% of your battery per day" +
            " (busiest day: ${days.max()}%)."
        live.drainPctPerHour?.let { lines += "• While unplugged it drops ${"%.1f".format(it)}% per hour, so a full charge lasts about ${(100 / it).roundToInt()} hours." }
        if (nightMs > 2 * 3_600_000) {
            val n = nightDrop / (nightMs / 3_600_000.0)
            lines += "• Overnight (phone idle) it drops ${"%.1f".format(n)}% per hour" +
                if (n > 2.5) " - that's high; an app is probably keeping the phone awake." else " - that's normal."
        }
        lines += "• You plug in about ${"%.1f".format(plugIns / spanDays)} times a day."
        live.chargePctPerMin?.let { lines += "• Charging speed: about ${"%.1f".format(it)}% per minute (0 → 100% in ~${(100 / it).roundToInt()} min)." +
            if (it < 0.6) " That's slow for SUPERVOOC - the cable or charger may not be fast-charging." else "" }
        if (temps.isNotEmpty()) lines += "• Temperature: average ${"%.0f".format(temps.average())} °C, highest ${"%.0f".format(temps.max())} °C" +
            if (temps.max() > 42) " - heat ages batteries; avoid gaming while charging." else "."
        lines += "• Based on ${"%.1f".format(spanDays)} days of readings."
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ replacement check

    private fun beforePrefs() = getSharedPreferences("battery_before", Context.MODE_PRIVATE)

    private fun saveBefore() {
        val now = prefs()
        if (!now.contains("at")) {
            tvStatus.text = "Tap \"Scan phone now\" (or open a bug report) first, then save the snapshot."
            return
        }
        val e = beforePrefs().edit().clear()
        listOf("cycles", "full", "learned", "soh").forEach { k -> if (now.contains(k)) e.putInt(k, now.getInt(k, 0)) }
        BatteryStats.report(this).capacityMah?.let { e.putInt("liveCap", it) }
        e.putString("ids", now.getString("ids", "")).putLong("at", System.currentTimeMillis()).apply()
        tvStatus.text = "Saved ✓ After the new battery is fitted, tap \"Check new battery\"."
        renderReplace(false)
    }

    private fun renderReplace(checkNow: Boolean) {
        val b = beforePrefs(); val n = prefs()
        val tv = findViewById<TextView>(R.id.tvReplace)
        if (!b.contains("at")) {
            tv.text = "Before the service centre: scan, then tap 1. After the new battery is fitted: tap 2. " +
                "The app compares cycles, capacity and the battery's ID."
            return
        }
        val when0 = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(b.getLong("at", 0)))
        if (!checkNow) {
            tv.text = "\"Before\" saved on $when0: " +
                (if (b.contains("cycles")) "${b.getInt("cycles", 0)} cycles, " else "") +
                (if (b.contains("full")) "${fmt(b.getInt("full", 0))} mAh" else "capacity unknown") +
                ".\nAfter the replacement, tap 2."
            return
        }
        val live = BatteryStats.report(this)
        val design = n.getInt("design", live.designMah)
        val beforeAt = b.getLong("at", 0)
        if (!b.contains("afterAt")) b.edit().putLong("afterAt", System.currentTimeMillis()).apply()
        val afterAt = b.getLong("afterAt", System.currentTimeMillis())
        fun cap(p: android.content.SharedPreferences) =
            if (p.contains("full")) p.getInt("full", 0) else if (p.contains("learned")) p.getInt("learned", 0) else null
        val out = ReplacementCheck.evaluate(ReplacementCheck.Input(
            designMah = design,
            cyclesBefore = if (b.contains("cycles")) b.getInt("cycles", 0) else null,
            cyclesNow = if (n.contains("cycles")) n.getInt("cycles", 0) else null,
            capBefore = cap(b) ?: if (b.contains("liveCap")) b.getInt("liveCap", 0) else null,
            capNow = cap(n) ?: live.capacityMah,
            idBefore = b.getString("ids", null), idNow = n.getString("ids", null),
            drainBefore = BatteryStats.drainBetween(this, beforeAt - 7L * 86_400_000, beforeAt),
            drainAfter = BatteryStats.drainBetween(this, afterAt, System.currentTimeMillis()),
            daysSince = (System.currentTimeMillis() - afterAt) / 86_400_000.0,
            fullChargesSince = BatteryStats.fullChargesSince(this, afterAt),
        ))
        tv.text = (out.lines + "" + out.summary).joinToString("\n")
    }

    private data class Tile(val label: String, val value: String, val sub: String)

    private fun tileView(t: Tile) = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(context).apply { text = t.label; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge) })
            addView(TextView(context).apply { text = t.value; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall) })
            addView(TextView(context).apply { text = t.sub; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall) })
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun fmt(v: Int) = "%,d".format(v)
}
