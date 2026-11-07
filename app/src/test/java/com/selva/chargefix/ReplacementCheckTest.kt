package com.selva.chargefix

import com.selva.chargefix.ReplacementCheck.Input
import com.selva.chargefix.ReplacementCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplacementCheckTest {
    private val base = Input(
        designMah = 4500, cyclesBefore = 600, cyclesNow = 600,
        capBefore = 3200, capNow = 3200, idBefore = null, idNow = null,
        drainBefore = 9.0, drainAfter = null, daysSince = 0.1, fullChargesSince = 0,
    )
    private fun v(i: Input) = ReplacementCheck.evaluate(i).verdict

    @Test fun hardwareOnlySwap_dayOne_isWaitNotFail() {
        // Nothing reset in software, capacity not relearned yet, no drain data yet.
        assertEquals(Verdict.WAIT, v(base))
    }

    @Test fun hardwareOnlySwap_afterUse_drainAndCapacityImprove_isReplaced() {
        assertEquals(Verdict.REPLACED, v(base.copy(drainAfter = 6.2, capNow = 4350, daysSince = 3.5, fullChargesSince = 2)))
    }

    @Test fun hardwareOnlySwap_onlyDrainImproved_earlyIsWait_laterIsReplaced() {
        assertEquals(Verdict.WAIT, v(base.copy(drainAfter = 6.5, daysSince = 1.0, fullChargesSince = 1)))
        assertEquals(Verdict.REPLACED, v(base.copy(drainAfter = 6.5, capNow = null, daysSince = 3.0, fullChargesSince = 2)))
    }

    @Test fun cyclesNotResetIsNeverCountedAgainst() {
        val out = ReplacementCheck.evaluate(base.copy(drainAfter = 6.0, capNow = 4400, daysSince = 4.0, fullChargesSince = 3))
        assertEquals(Verdict.REPLACED, out.verdict)
    }

    @Test fun cyclesReset_isProofImmediately() {
        assertEquals(Verdict.REPLACED, v(base.copy(cyclesNow = 2)))
    }

    @Test fun batteryIdChanged_isProofImmediately() {
        assertEquals(Verdict.REPLACED, v(base.copy(idBefore = "sn:AAA", idNow = "sn:BBB")))
    }

    @Test fun nothingChangedAfterThreeDays_isNotReplaced() {
        assertEquals(Verdict.NOT_REPLACED, v(base.copy(drainAfter = 8.9, capNow = 3210, daysSince = 4.0, fullChargesSince = 3)))
    }

    @Test fun nothingChangedButTooEarly_isStillWait() {
        assertEquals(Verdict.WAIT, v(base.copy(drainAfter = 8.9, capNow = 3210, daysSince = 1.0, fullChargesSince = 1)))
    }
}
