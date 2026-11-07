package com.selva.chargefix

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turns the phone's charging input off and on through the kernel's sysfs switches.
 *
 * Android does not let normal apps touch the charger hardware, so this needs ROOT.
 * Cutting the input and re-enabling it makes the charger chip renegotiate with the
 * cable, which is the same thing a reboot does to clear the stuck "fake charging" state.
 */
object ChargeControl {

    /** A sysfs switch: what to write to stop charging, and what to write to allow it again. */
    data class Node(val path: String, val stop: String, val start: String)

    /** Known switches on OnePlus / Oppo / Realme (OPlus) and generic Qualcomm kernels. */
    val NODES = listOf(
        Node("/sys/class/oplus_chg/battery/mmi_charging_enable", "0", "1"),
        Node("/sys/class/power_supply/battery/mmi_charging_enable", "0", "1"),
        Node("/sys/class/power_supply/battery/op_disable_charge", "1", "0"),
        Node("/sys/class/power_supply/battery/input_suspend", "1", "0"),
        Node("/sys/class/qcom-battery/input_suspend", "1", "0"),
        Node("/sys/class/power_supply/battery/charging_enabled", "0", "1"),
        Node("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1"),
    )

    data class Result(val ok: Boolean, val output: String)

    /** Runs a shell script as root. Blocking; call off the main thread. */
    fun su(script: String, timeoutSec: Long = 30): Result = try {
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!finished) {
            p.destroy(); Result(false, "Timed out. $out")
        } else Result(p.exitValue() == 0, out)
    } catch (e: Exception) {
        Result(false, e.message ?: e.toString())
    }

    fun hasRoot(): Boolean = su("id", 15).let { it.ok && it.output.contains("uid=0") }

    /** Which switches exist on this phone (sysfs is usually world-readable for existence). */
    fun presentNodes(): List<Node> = NODES.filter { File(it.path).exists() }

    private fun writeAll(pick: (Node) -> String) =
        NODES.joinToString("\n") { n ->
            "if [ -e ${n.path} ]; then echo ${pick(n)} > ${n.path} && echo \"${n.path} <- ${pick(n)}\"; fi"
        }

    fun stopCharging(): Result = su(writeAll { it.stop }).withEmptyCheck()

    fun startCharging(): Result = su(writeAll { it.start }).withEmptyCheck()

    /**
     * Cut charging, wait, then turn it back on - all inside ONE root shell, so charging is
     * always switched back on even if the app is killed in the middle.
     */
    fun resetCharging(pauseSec: Int = 4): Result =
        su("${writeAll { it.stop }}\nsleep $pauseSec\n${writeAll { it.start }}", 30L + pauseSec)
            .withEmptyCheck()

    fun reboot(): Result = su("svc power reboot || reboot")

    private fun Result.withEmptyCheck() =
        if (ok && output.isBlank()) Result(false, "No known charging switch found on this phone.") else this
}
