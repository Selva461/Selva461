package com.selva.chargefix

/**
 * Decides whether the battery was really replaced - robust to a hardware-only swap
 * where the service centre does NOT reset anything in software.
 *
 * Evidence, strongest first:
 *  - battery identity from the battery's own chip changed            -> proof
 *  - drain per hour clearly improved with normal use (needs ~6 h each) -> strong
 *  - capacity rose to near design (phone relearns over 2-3 full charges) -> strong
 *  - cycle count reset                                                -> proof if it happens,
 *    but NOT resetting is not counted against (the count may live in the phone's memory)
 * A "not replaced" verdict is only given after 3+ days and 2+ full charges with no improvement.
 */
object ReplacementCheck {

    data class Input(
        val designMah: Int,
        val cyclesBefore: Int?, val cyclesNow: Int?,
        val capBefore: Int?, val capNow: Int?,
        val idBefore: String?, val idNow: String?,
        val drainBefore: Double?, val drainAfter: Double?,   // % per hour, unplugged
        val daysSince: Double, val fullChargesSince: Int,
    )

    enum class Verdict { REPLACED, WAIT, NOT_REPLACED }
    data class Output(val verdict: Verdict, val lines: List<String>, val summary: String)

    fun evaluate(i: Input): Output {
        val lines = mutableListOf<String>()
        var proof = 0          // signals that on their own show a new battery
        var improved = 0       // strong supporting signals
        var unchanged = 0      // signals that measured "same as before"
        val calibrated = i.daysSince >= 3 && i.fullChargesSince >= 2

        // 1. Battery identity (hardware)
        if (!i.idBefore.isNullOrBlank() && !i.idNow.isNullOrBlank()) {
            if (i.idBefore != i.idNow) { proof++; lines += "✓ Battery ID changed - a different battery is fitted" }
            else { unchanged++; lines += "! Battery ID is the same as before" }
        } else lines += "• Battery ID: this phone doesn't show one (that's normal)"

        // 2. Real-world drain
        if (i.drainBefore != null && i.drainAfter != null && i.drainBefore > 0) {
            val gain = (i.drainBefore - i.drainAfter) / i.drainBefore
            val txt = "%.1f%% → %.1f%% per hour".format(i.drainBefore, i.drainAfter)
            when {
                gain >= 0.15 -> { improved++; lines += "✓ Drains slower: $txt (${(gain * 100).toInt()}% better)" }
                gain <= 0.05 && calibrated -> { unchanged++; lines += "! Drain about the same: $txt" }
                else -> lines += "• Drain: $txt - keep using it normally, it needs more data"
            }
        } else lines += "• Drain comparison: needs about 6 hours of normal (unplugged) use first"

        // 3. Capacity (relearned by the phone over a few full charges)
        if (i.capNow != null) {
            val pct = i.capNow * 100 / i.designMah
            val rose = i.capBefore != null && i.capNow >= i.capBefore * 1.08
            when {
                pct >= 93 || (rose && pct >= 85) -> { improved++; lines += "✓ Capacity ${"%,d".format(i.capNow)} mAh ($pct% of new)" }
                !calibrated -> lines += "• Capacity ${"%,d".format(i.capNow)} mAh ($pct%) - the phone is still learning the new battery. " +
                    "Charge to 100% ${maxOf(0, 2 - i.fullChargesSince)} more time(s)."
                else -> { unchanged++; lines += "! Capacity still low: ${"%,d".format(i.capNow)} mAh ($pct%) after ${i.fullChargesSince} full charges" }
            }
        } else lines += "• Capacity: not reported yet"

        // 4. Cycle count - only ever counts FOR, never against
        if (i.cyclesBefore != null && i.cyclesNow != null) {
            if (i.cyclesNow <= 10 || i.cyclesNow < i.cyclesBefore / 2) {
                proof++; lines += "✓ Charge cycles reset: ${i.cyclesBefore} → ${i.cyclesNow}"
            } else lines += "• Charge cycles ${i.cyclesBefore} → ${i.cyclesNow}: not reset. That's OK - on many phones " +
                "the count is kept in the phone, not the battery, so a plain swap doesn't reset it."
        }

        val verdict = when {
            proof >= 1 || improved >= 2 -> Verdict.REPLACED
            improved == 1 && unchanged == 0 -> if (calibrated) Verdict.REPLACED else Verdict.WAIT
            calibrated && improved == 0 && unchanged >= 2 -> Verdict.NOT_REPLACED
            else -> Verdict.WAIT
        }
        val summary = when (verdict) {
            Verdict.REPLACED -> "✓ Yes - the evidence shows a new battery."
            Verdict.NOT_REPLACED -> "✗ No improvement after ${"%.0f".format(i.daysSince)} days and ${i.fullChargesSince} full charges. " +
                "Take the phone and receipt back to the service centre."
            Verdict.WAIT -> "… Too early to be sure. Use the phone normally and charge to 100% a couple of times; " +
                "check again in ${maxOf(1, 3 - i.daysSince.toInt())} day(s)."
        }
        return Output(verdict, lines, summary)
    }
}
