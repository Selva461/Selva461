package com.selva.chargefix

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/** One reading of the battery state, with the current normalised to milliamps. */
data class BatterySnapshot(
    val level: Int,
    val plugged: Int,
    val status: Int,
    val voltageMv: Int,
    val temperatureC: Float,
    /** Current flowing INTO the battery in mA. Positive = really charging, negative = draining. */
    val netCurrentMa: Int?,
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

enum class Verdict { UNPLUGGED, CHARGING_OK, FULL, FAKE_CHARGING, UNKNOWN }

object BatteryReader {
    private const val PREFS = "battery_cal"
    private const val KEY_DISCHARGE_SIGN = "discharge_sign"

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
        // Learn which sign this phone uses for "draining": whenever we're unplugged
        // and discharging, the current we see is by definition a drain.
        if (ma != null && plugged == 0 && status == BatteryManager.BATTERY_STATUS_DISCHARGING && abs(ma) > 20) {
            val sign = if (ma < 0) -1 else 1
            if (prefs.getInt(KEY_DISCHARGE_SIGN, 0) != sign) {
                prefs.edit().putInt(KEY_DISCHARGE_SIGN, sign).apply()
            }
        }
        // Android standard: negative = discharging.
        val dischargeSign = prefs.getInt(KEY_DISCHARGE_SIGN, -1)
        val net = ma?.let { if (dischargeSign < 0) it else -it }

        return BatterySnapshot(level, plugged, status, voltage, temp, net)
    }

    fun verdict(s: BatterySnapshot): Verdict = when {
        !s.isPlugged -> Verdict.UNPLUGGED
        s.isFull -> Verdict.FULL
        s.netCurrentMa == null -> if (s.saysCharging) Verdict.UNKNOWN else Verdict.FAKE_CHARGING
        s.netCurrentMa >= MIN_REAL_CHARGE_MA -> Verdict.CHARGING_OK
        // Near-full batteries trickle; don't cry wolf above 95 %.
        s.level >= 95 -> Verdict.FULL
        else -> Verdict.FAKE_CHARGING
    }
}
