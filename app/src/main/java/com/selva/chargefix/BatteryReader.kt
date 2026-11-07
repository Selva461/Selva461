package com.selva.chargefix

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlin.math.abs

/** One reading of the battery state, with the current normalised to milliamps. */
data class BatterySnapshot(
    val level: Int,
    val plugged: Int,
    val status: Int,
    val voltageMv: Int,
    val temperatureC: Float,
    /** mA flowing INTO the battery (+ charging, - draining). Null until the app has learned
     *  which way this phone's sensor counts (OnePlus counts the opposite way to stock Android). */
    val netCurrentMa: Int?,
    /** The sensor value as the phone reports it, for display. */
    val rawCurrentMa: Int? = null,
) {
    val isPlugged get() = plugged != 0
    val saysCharging get() = status == BatteryManager.BATTERY_STATUS_CHARGING
    val isFull get() = status == BatteryManager.BATTERY_STATUS_FULL

    val pluggedLabel
        get() = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Charger (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Not plugged in"
            else -> "Plugged ($plugged)"
        }

    val statusLabel
        get() = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
}

enum class Verdict { UNPLUGGED, CHARGING_OK, FULL, FAKE_CHARGING, UNKNOWN, CHECKING }

object BatteryReader {
    private const val PREFS = "battery_cal"
    // v2: the old key could hold a guessed (wrong) default, so start fresh.
    private const val KEY_DISCHARGE_SIGN = "discharge_sign_v2"

    /** Recent readings of the battery's charge counter, used to learn the sensor's sign. */
    private class Sample(val t: Long, val counterUah: Long, val rawMa: Int, val plugged: Boolean)
    private val history = ArrayDeque<Sample>()

    /** Below this many mA flowing into the battery we don't call it "really charging". */
    const val MIN_REAL_CHARGE_MA = 50

    fun read(context: Context): BatterySnapshot {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level = sticky?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val ma: Int? = if (raw == Int.MIN_VALUE || raw == 0) null else {
            // The API says microamps, but many OnePlus/Oppo builds report milliamps.
            if (abs(raw) >= 20_000) raw / 1000 else raw
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        if (ma != null && counter > 0 && counter != Int.MIN_VALUE.toLong()) {
            learnFromCounter(prefs, Sample(SystemClock.elapsedRealtime(), counter, ma, plugged != 0))
        }
        // Learn which sign this phone uses for "draining": whenever we're unplugged
        // and discharging, the current we see is by definition a drain.
        if (ma != null && plugged == 0 && status == BatteryManager.BATTERY_STATUS_DISCHARGING && abs(ma) > 20) {
            val sign = if (ma < 0) -1 else 1
            if (prefs.getInt(KEY_DISCHARGE_SIGN, 0) != sign) {
                prefs.edit().putInt(KEY_DISCHARGE_SIGN, sign).apply()
            }
        }
        val dischargeSign = prefs.getInt(KEY_DISCHARGE_SIGN, 0) // 0 = not learned yet
        val net = if (ma == null || dischargeSign == 0) null else if (dischargeSign < 0) ma else -ma

        return BatterySnapshot(level, plugged, status, voltage, temp, net, ma)
    }

    /**
     * If the battery's charge counter clearly went up (or down) over the last ~15 s, the
     * current sensor's reading during that time tells us which sign means "into the battery".
     */
    @Synchronized
    private fun learnFromCounter(prefs: android.content.SharedPreferences, now: Sample) {
        if (history.isNotEmpty() && history.last().plugged != now.plugged) history.clear()
        history.addLast(now)
        while (history.isNotEmpty() && now.t - history.first().t > 30_000) history.removeFirst()
        val first = history.first()
        if (now.t - first.t < 12_000) return
        val delta = now.counterUah - first.counterUah
        if (abs(delta) < 300) return // not enough change to be sure
        val avgMa = history.map { it.rawMa }.average()
        if (abs(avgMa) < 20) return
        val rawSign = if (avgMa < 0) -1 else 1
        val dischargeSign = if (delta > 0) -rawSign else rawSign
        if (prefs.getInt(KEY_DISCHARGE_SIGN, 0) != dischargeSign) {
            prefs.edit().putInt(KEY_DISCHARGE_SIGN, dischargeSign).apply()
        }
    }

    fun verdict(s: BatterySnapshot): Verdict = when {
        !s.isPlugged -> Verdict.UNPLUGGED
        s.isFull -> Verdict.FULL
        s.netCurrentMa == null -> if (s.rawCurrentMa == null) Verdict.UNKNOWN else Verdict.CHECKING
        s.netCurrentMa >= MIN_REAL_CHARGE_MA -> Verdict.CHARGING_OK
        // Near-full batteries trickle; don't cry wolf above 95 %.
        s.level >= 95 -> Verdict.FULL
        else -> Verdict.FAKE_CHARGING
    }
}
