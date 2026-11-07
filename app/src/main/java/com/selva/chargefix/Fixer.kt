package com.selva.chargefix

import android.content.Context

/**
 * Picks whatever way of kicking the charger is available on this phone:
 * root (sysfs switch) if present, otherwise Shizuku + USB-port commands.
 * Every no-root method is verified by actually measuring the current afterwards.
 */
object Fixer {

    enum class Method(val key: String, val label: String) {
        ROOT("root", "Root: charging switch off/on"),
        LIMIT_POWER(ShellUserService.OP_LIMIT_POWER, "Limit USB power on/off"),
        PORT_RESET(ShellUserService.OP_PORT_RESET, "Reset USB-C port"),
        ROLE_SWAP(ShellUserService.OP_ROLE_SWAP, "USB power-role swap"),
    }

    private val NO_ROOT_METHODS = listOf(Method.LIMIT_POWER, Method.PORT_RESET, Method.ROLE_SWAP)
    private const val VERIFY_SEC = 20

    data class Outcome(val fixed: Boolean, val method: Method?, val log: String)

    private fun prefs(c: Context) = c.getSharedPreferences("fixer", Context.MODE_PRIVATE)
    fun workingMethod(c: Context): Method? =
        prefs(c).getString("working", null)?.let { k -> Method.entries.firstOrNull { it.key == k } }
    private fun remember(c: Context, m: Method) = prefs(c).edit().putString("working", m.key).apply()

    /** Runs one method once (no verification). Blocking. */
    fun run(m: Method): String = when (m) {
        Method.ROOT -> ChargeControl.resetCharging().let { (if (it.ok) "OK\n" else "Failed\n") + it.output }
        else -> ShizukuShell.get()?.usbOp(m.key, 4) ?: "Shizuku not connected"
    }

    /**
     * The main "fix it" action. Tries the method that worked before first, then all the
     * others, and after each one waits up to 20 s to see real current flowing in.
     */
    fun fix(ctx: Context, hasRoot: Boolean, progress: (String) -> Unit = {}): Outcome {
        val log = StringBuilder()
        fun say(s: String) { log.appendLine(s); progress(log.toString().trim()) }

        val before = BatteryReader.read(ctx)
        if (!before.isPlugged) { say("Not plugged in."); return Outcome(false, null, log.toString()) }
        if (BatteryReader.verdict(before) == Verdict.CHARGING_OK) {
            say("It's already really charging (${before.netCurrentMa} mA). Nothing to fix.")
            return Outcome(true, null, log.toString())
        }

        val candidates = buildList {
            if (hasRoot) add(Method.ROOT)
            if (ShizukuShell.state(ctx) == ShizukuShell.State.READY) addAll(NO_ROOT_METHODS)
        }.sortedByDescending { it == workingMethod(ctx) }

        if (candidates.isEmpty()) {
            say("No way to control the charger: no root, and Shizuku isn't running.")
            return Outcome(false, null, log.toString())
        }

        for (m in candidates) {
            say("▶ ${m.label}…")
            say(run(m).prependIndent("   "))
            if (waitForRealCharging(ctx)) {
                val s = BatteryReader.read(ctx)
                say("✓ FIXED - really charging now (+${s.netCurrentMa} mA).")
                remember(ctx, m)
                return Outcome(true, m, log.toString())
            }
            say("   ✗ still not charging.")
        }
        say("None of the methods fixed it on this phone. Unplug, restart, plug in.")
        return Outcome(false, null, log.toString())
    }

    private fun waitForRealCharging(ctx: Context): Boolean {
        repeat(VERIFY_SEC / 2) {
            Thread.sleep(2000)
            val s = BatteryReader.read(ctx)
            if (BatteryReader.verdict(s) == Verdict.CHARGING_OK) return true
        }
        return false
    }
}
