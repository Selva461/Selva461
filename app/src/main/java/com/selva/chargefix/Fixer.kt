package com.selva.chargefix

import android.content.Context

/**
 * Picks whatever way of kicking the charger is available on this phone:
 * root (sysfs switch) if present, otherwise the app's own ADB connection + USB-port commands.
 * Every no-root method is verified by actually measuring the current afterwards.
 */
object Fixer {

    enum class Method(val key: String, val label: String) {
        ROOT("root", "Root: charging switch off/on"),
        OTG_OFF(UsbOps.OP_OTG_OFF, "Turn off OTG, back to charging mode"),
        LIMIT_POWER(UsbOps.OP_LIMIT_POWER, "Limit USB power on/off"),
        PORT_RESET(UsbOps.OP_PORT_RESET, "Reset USB-C port"),
        ROLE_SWAP(UsbOps.OP_ROLE_SWAP, "USB power-role swap"),
    }

    // OTG/role first: on the OnePlus 9R the stuck state is the port acting as a power SOURCE.
    // Limit-power and port-reset are NOT_SUPPORTED by the 9R's USB driver, so they go last.
    private val NO_ROOT_METHODS = listOf(Method.OTG_OFF, Method.ROLE_SWAP, Method.LIMIT_POWER, Method.PORT_RESET)
    private const val VERIFY_SEC = 20

    data class Outcome(val fixed: Boolean, val method: Method?, val log: String)

    private fun prefs(c: Context) = c.getSharedPreferences("fixer", Context.MODE_PRIVATE)
    fun workingMethod(c: Context): Method? =
        prefs(c).getString("working", null)?.let { k -> Method.entries.firstOrNull { it.key == k } }
    private fun remember(c: Context, m: Method) = prefs(c).edit().putString("working", m.key).apply()

    private fun runRoot(): String =
        ChargeControl.resetCharging().let { (if (it.ok) "OK\n" else "Failed\n") + it.output }

    /**
     * The main "fix it" action. Tries the method that worked before first, then all the
     * others, and after each one waits up to 20 s to see real current flowing in.
     */
    fun fix(ctx: Context, hasRoot: Boolean, progress: (String) -> Unit = {}): Outcome {
        val log = StringBuilder()
        fun say(s: String) { log.appendLine(s); progress(log.toString().trim()) }

        var before = BatteryReader.read(ctx)
        // First learn which way this phone's current sensor counts (up to ~30 s).
        if (BatteryReader.verdict(before) == Verdict.CHECKING) {
            say("Checking whether it's really charging…")
            repeat(15) {
                if (BatteryReader.verdict(before) != Verdict.CHECKING) return@repeat
                Thread.sleep(2000)
                before = BatteryReader.read(ctx)
            }
        }
        if (!before.isPlugged) { say("Not plugged in."); return Outcome(false, null, log.toString()) }
        if (BatteryReader.verdict(before).let { it == Verdict.CHARGING_OK || it == Verdict.FULL }) {
            say("It's already really charging (${before.netCurrentMa} mA). Nothing to fix.")
            return Outcome(true, null, log.toString())
        }

        fun attempt(m: Method, runner: () -> String): Boolean {
            say("▶ ${m.label}…")
            say(runner().prependIndent("   "))
            if (waitForRealCharging(ctx)) {
                val s = BatteryReader.read(ctx)
                say("✓ FIXED - really charging now (+${s.netCurrentMa} mA).")
                remember(ctx, m)
                return true
            }
            say("   ✗ still not charging.")
            return false
        }

        if (hasRoot && attempt(Method.ROOT, ::runRoot)) return Outcome(true, Method.ROOT, log.toString())

        if (!AdbShell.isPaired(ctx)) {
            if (!hasRoot) say("No-root mode isn't set up yet. Tap \"Set up no-root mode\" first.")
        } else {
            val order = NO_ROOT_METHODS.sortedByDescending { it == workingMethod(ctx) }
            try {
                val fixedBy = AdbShell.session(ctx) { sh ->
                    say("Connected to Wireless debugging.")
                    order.firstOrNull { m -> attempt(m) { sh.usbOp(m.key) } }
                }
                if (fixedBy != null) { BatteryStats.countFakeFix(ctx); return Outcome(true, fixedBy, log.toString()) }
            } catch (e: AdbShell.NotReady) {
                say(e.message ?: "Not ready.")
                return Outcome(false, null, log.toString())
            } catch (e: Exception) {
                say("ADB error: ${e.message ?: e}")
                return Outcome(false, null, log.toString())
            }
        }
        say("Nothing fixed it this time. Unplug, restart the phone, plug in.")
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
