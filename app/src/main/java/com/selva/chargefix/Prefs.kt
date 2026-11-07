package com.selva.chargefix

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun autoFix(c: Context) = p(c).getBoolean("auto_fix", false)
    fun setAutoFix(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_fix", v).apply()
    fun watchdog(c: Context) = p(c).getBoolean("watchdog", false)
    fun setWatchdog(c: Context, v: Boolean) = p(c).edit().putBoolean("watchdog", v).apply()
}
