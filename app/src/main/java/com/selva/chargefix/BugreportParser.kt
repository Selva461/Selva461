package com.selva.chargefix

import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pulls battery-health facts out of an Android bug report (or `dumpsys` output),
 * entirely on the phone. Line-by-line streaming, so a 100 MB report is fine.
 *
 * Approach inspired by Google's Battery Historian (Apache-2.0); this is a small,
 * independent Kotlin implementation of just the battery-health parts.
 */
class BugreportParser {

    data class Result(
        var model: String? = null,
        var androidVersion: String? = null,
        var cycleCount: Int? = null,
        var cycleSource: String? = null,
        var fullChargeMah: Int? = null,       // what a full charge holds now
        var fullChargeSource: String? = null,
        var designMah: Int? = null,
        var designSource: String? = null,
        var learnedMah: Int? = null,
        var learnedMinMah: Int? = null,
        var learnedMaxMah: Int? = null,
        var sohPct: Int? = null,               // health % reported by the phone itself
        var healthText: String? = null,        // Android's Good/Overheat/Dead...
        var tempMaxC: Double? = null,
        var voltageMv: Int? = null,
        var drainPctPerHour: Double? = null,
        var screenOffDrainPctPerHour: Double? = null,
        var linesRead: Long = 0,
        /** Battery identity facts (serial, manufacture date, type...) if the phone exposes them. */
        val ids: MutableMap<String, String> = linkedMapOf(),
        /** Past readings with real timestamps, from Android's battery history (thinned to ~1 per 5 min). */
        val history: MutableList<LogEntry> = mutableListOf(),
    )

    private val r = Result()

    // healthd: battery l=66 v=4391 t=43.4 h=2 st=2 c=-1232000 fc=3800000 cc=420 chg=a
    private val healthd = Regex("""healthd.*?battery l=(\d+) v=(\d+) t=(-?[\d.]+) h=(\d+).*?(?:fc=(\d+))?(?: cc=(\d+))?(?:\s|$)""")
    private val fcRe = Regex("""\bfc=(\d+)""")
    private val ccRe = Regex("""\bcc=(\d+)""")
    private val estCap = Regex("""Estimated battery capacity:\s*(\d+)\s*mAh""")
    private val lastLearned = Regex("""Last learned battery capacity:\s*(\d+)\s*mAh""")
    private val minLearned = Regex("""Min learned battery capacity:\s*(\d+)\s*mAh""")
    private val maxLearned = Regex("""Max learned battery capacity:\s*(\d+)\s*mAh""")
    private val cycleGeneric = Regex("""(?i)\b(?:battery[ _]?)?cycle[ _]?count\b\s*[:=]\s*(\d+)""")
    private val oplus = Regex("""(?i)\b(battery_cc|battery_fcc|battery_soh|design_capacity|charge_full_design|charge_full)\b\s*[:=]\s*(\d+)""")
    private val model = Regex("""\[ro\.product\.model]: \[(.+)]""")
    private val release = Regex("""\[ro\.build\.version\.release]: \[(.+)]""")
    private val healthLine = Regex("""^\s+health:\s*(\d+)\s*$""")
    private val voltLine = Regex("""^\s+voltage:\s*(\d+)\s*$""")
    private val tempLine = Regex("""^\s+temperature:\s*(\d+)\s*$""")

    // Battery History lines:  +1h02m03s123ms (2) 066 ...  (level is the 3-digit field)
    private val histLine = Regex("""^\s+\+?((?:\d+d)?(?:\d+h)?(?:\d+m)?(?:\d+s)?(?:\d+ms)?|0)\s+\(\d+\)\s+(\d{3})\b(.*)$""")
    private var inHistory = false
    private val hist = ArrayList<Triple<Long, Int, Boolean>>() // time ms, level, plugged
    private var histScreenOn: Boolean? = null
    private var histPlugged = false
    private var histTemp = 0f
    private var histVolt = 0
    private var histCharge = -1
    private var timeBase: Long? = null   // absolute ms = timeBase + offset
    private val timeLine = Regex("""^\s+\+?((?:\d+d)?(?:\d+h)?(?:\d+m)?(?:\d+s)?(?:\d+ms)?|0)\s+\(\d+\)\s+(?:RESET:)?TIME:\s+(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})""")
    private val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US)
    private val kv = Regex("""\b(plug|temp|volt|charge)=(\w+)""")

    private val idName = Regex("""(?i)(sn|serial|manu|date|type|vendor|auth|_id|batt_id|model)""")

    fun feed(line: String) {
        r.linesRead++
        // "path=value" lines from our own scan of the battery folders
        if (line.startsWith("/sys/class/") && line.contains('=') &&
            (line.contains("/battery/") || line.contains("/bms/"))) {
            val key = line.substringBefore('=').substringAfterLast('/')
            val v = line.substringAfter('=').trim()
            if (v.isNotEmpty() && v.length <= 64 && idName.containsMatchIn(key) && r.ids.size < 12) r.ids[key] = v
        }
        if (line.contains("healthd") && line.contains("battery l=")) {
            healthd.find(line)?.let { m ->
                m.groupValues[2].toIntOrNull()?.let { r.voltageMv = it }
                m.groupValues[3].toDoubleOrNull()?.let { t -> if (t > (r.tempMaxC ?: -99.0)) r.tempMaxC = t }
            }
            fcRe.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { setFull(toMah(it), "battery service (fc)") }
            ccRe.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { setCycles(it, "battery service (cc)") }
            return
        }
        estCap.find(line)?.let { setDesign(it.groupValues[1].toInt(), "phone's power profile"); return }
        lastLearned.find(line)?.let { v -> v.groupValues[1].toInt().takeIf { it > 0 }?.let { r.learnedMah = it }; return }
        minLearned.find(line)?.let { v -> v.groupValues[1].toInt().takeIf { it > 0 }?.let { r.learnedMinMah = it }; return }
        maxLearned.find(line)?.let { v -> v.groupValues[1].toInt().takeIf { it > 0 }?.let { r.learnedMaxMah = it }; return }
        oplus.find(line)?.let { m ->
            val v = m.groupValues[2].toLong()
            when (m.groupValues[1].lowercase()) {
                "battery_cc" -> setCycles(v.toInt(), "OnePlus battery data")
                "battery_fcc", "charge_full" -> setFull(toMah(v), "OnePlus battery data")
                "battery_soh" -> if (v in 1..120) r.sohPct = v.toInt()
                "design_capacity", "charge_full_design" -> setDesign(toMah(v), "reported by phone", force = true)
            }
        }
        cycleGeneric.find(line)?.let { setCycles(it.groupValues[1].toInt(), "system report") }
        if (r.model == null) model.find(line)?.let { r.model = it.groupValues[1] }
        if (r.androidVersion == null) release.find(line)?.let { r.androidVersion = it.groupValues[1] }
        healthLine.find(line)?.let { r.healthText = healthName(it.groupValues[1].toInt()) }
        voltLine.find(line)?.let { v -> v.groupValues[1].toInt().takeIf { it in 2500..5000 }?.let { r.voltageMv = it } }
        tempLine.find(line)?.let { t -> (t.groupValues[1].toInt() / 10.0).let { if (it > (r.tempMaxC ?: -99.0)) r.tempMaxC = it } }

        // Battery history timeline
        if (line.startsWith("Battery History")) { inHistory = true; return }
        if (inHistory) {
            if (line.isBlank() || line.startsWith("Per-PID") || line.startsWith("Daily stats") || line.startsWith("Statistics since")) {
                inHistory = false; return
            }
            timeLine.find(line)?.let { m ->
                runCatching { dateFmt.parse(m.groupValues[2])!!.time }.getOrNull()?.let { abs ->
                    timeBase = abs - parseDur(m.groupValues[1])
                }
                return
            }
            histLine.find(line)?.let { m ->
                val t = parseDur(m.groupValues[1])
                val level = m.groupValues[2].toInt()
                val rest = m.groupValues[3]
                if (rest.contains("+screen")) histScreenOn = true
                if (rest.contains("-screen")) histScreenOn = false
                if (rest.contains("+plugged") || rest.contains("status=charging")) histPlugged = true
                if (rest.contains("-plugged") || rest.contains("status=discharging")) histPlugged = false
                kv.findAll(rest).forEach { k ->
                    val v = k.groupValues[2]
                    when (k.groupValues[1]) {
                        "plug" -> histPlugged = v != "none"
                        "temp" -> v.toIntOrNull()?.let { histTemp = it / 10f }
                        "volt" -> v.toIntOrNull()?.let { histVolt = it }
                        "charge" -> v.toIntOrNull()?.let { histCharge = it }
                    }
                }
                hist.add(Triple(t, level, histPlugged))
                val base = timeBase
                if (base != null && base + t > MIN_VALID_TIME) {
                    val abs = base + t
                    val prev = r.history.lastOrNull()
                    if (prev == null || prev.level != level || prev.plugged != histPlugged ||
                        prev.screenOn != histScreenOn || abs - prev.t >= 5 * 60_000) {
                        if (prev == null || abs >= prev.t) {
                            r.history.add(LogEntry(abs, level, histPlugged, histTemp, histVolt, histCharge, histScreenOn))
                        }
                    }
                }
            }
        }
    }

    private fun setCycles(v: Int, src: String) {
        if (v in 1..5000 && (r.cycleCount == null || src.startsWith("OnePlus") || src.contains("(cc)"))) {
            r.cycleCount = v; r.cycleSource = src
        }
    }
    private fun setFull(v: Int, src: String) {
        if (v in 500..10000) { r.fullChargeMah = v; r.fullChargeSource = src }
    }
    private fun setDesign(v: Int, src: String, force: Boolean = false) {
        if (v in 1000..10000 && (force || r.designMah == null)) { r.designMah = v; r.designSource = src }
    }
    private fun toMah(v: Long) = (if (v > 50_000) v / 1000 else v).toInt()

    private fun healthName(h: Int) = when (h) {
        2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over voltage"; 6 -> "Failure"; 7 -> "Cold"; else -> "Unknown"
    }

    private fun parseDur(s: String): Long {
        if (s == "0") return 0
        var ms = 0L
        Regex("""(\d+)(d|h|ms|m|s)""").findAll(s).forEach { m ->
            val n = m.groupValues[1].toLong()
            ms += when (m.groupValues[2]) { "d" -> n * 86_400_000; "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; else -> n }
        }
        return ms
    }

    fun finish(): Result {
        // Drain from the timeline: only stretches where it wasn't charging.
        var drop = 0.0; var ms = 0L
        for (k in 1 until hist.size) {
            val a = hist[k - 1]; val b = hist[k]
            val dt = b.first - a.first
            if (dt <= 0 || dt > 3 * 3_600_000) continue
            if (!b.third && b.second <= a.second) { drop += (a.second - b.second); ms += dt }
        }
        if (ms > 30 * 60_000) r.drainPctPerHour = drop / (ms / 3_600_000.0)
        return r
    }

    companion object {
        /** Ignore timestamps before 2015 (clock not set yet after a reset). */
        private const val MIN_VALID_TIME = 1_420_070_400_000L

        /** Parses a .zip bug report (finds the main bugreport-*.txt inside) or a plain text file. */
        fun parse(input: InputStream, isZip: Boolean, onProgress: (Long) -> Unit = {}): Result {
            val p = BugreportParser()
            if (isZip) {
                ZipInputStream(input.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val n = e.name.substringAfterLast('/')
                        if (!e.isDirectory && n.startsWith("bugreport") && n.endsWith(".txt")) {
                            readLines(z.bufferedReader(), p, onProgress)
                            break
                        }
                        e = z.nextEntry
                    }
                }
            } else {
                input.bufferedReader().use { readLines(it, p, onProgress) }
            }
            return p.finish()
        }

        fun parseText(text: String): Result {
            val p = BugreportParser()
            text.lineSequence().forEach { p.feed(it) }
            return p.finish()
        }

        private fun readLines(br: BufferedReader, p: BugreportParser, onProgress: (Long) -> Unit) {
            while (true) {
                val l = br.readLine() ?: break
                p.feed(l)
                if (p.r.linesRead % 50_000 == 0L) onProgress(p.r.linesRead)
            }
        }
    }
}
