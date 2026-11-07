package com.selva.chargefix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BugreportParserTest {

    @Test
    fun parsesHealthdOnePlusAndBatteryStats() {
        val text = """
            [ro.product.model]: [LE2101]
            [ro.build.version.release]: [13]
            11-07 12:15:00.000 healthd: battery l=66 v=4391 t=43.4 h=2 st=2 c=-1232000 fc=3712000 cc=512 chg=a
            /sys/class/oplus_chg/battery/battery_soh=82
              Estimated battery capacity: 4500 mAh
              Last learned battery capacity: 3690 mAh
              Min learned battery capacity: 3600 mAh
              Max learned battery capacity: 3800 mAh
            Battery History (2% used, 10KB used of 4096KB, 100 strings using 4KB):
                               0 (15) RESET:TIME: 2026-11-07-10-00-00
                               0 (2) 090 status=discharging health=good plug=none
                         +1h00m00s000ms (2) 080
                         +2h00m00s000ms (2) 070
                         +2h10m00s000ms (2) 070 +plugged status=charging
                         +3h00m00s000ms (2) 090
            
        """.trimIndent()
        val r = BugreportParser.parseText(text)
        assertEquals("LE2101", r.model)
        assertEquals(512, r.cycleCount)
        assertEquals(3712, r.fullChargeMah)
        assertEquals(4500, r.designMah)
        assertEquals(3690, r.learnedMah)
        assertEquals(82, r.sohPct)
        assertEquals(10.0, r.drainPctPerHour!!, 0.01)
    }

    @Test
    fun capturesBatteryIdentityFromScan() {
        val r = BugreportParser.parseText("""
            /sys/class/oplus_chg/battery/battery_sn=4G21A0123
            /sys/class/oplus_chg/battery/battery_manu_date=2021-03-08
            /sys/class/power_supply/battery/capacity=66
            /sys/class/power_supply/usb/serial_number=IGNORED
        """.trimIndent())
        assertEquals("4G21A0123", r.ids["battery_sn"])
        assertEquals("2021-03-08", r.ids["battery_manu_date"])
        assertTrue("capacity" !in r.ids && r.ids.size == 2)
    }

    /** Runs against a real report when BUGREPORT points at one (not committed). */
    @Test
    fun realReportIfProvided() {
        val path = System.getenv("BUGREPORT") ?: return
        val f = File(path)
        val r = f.inputStream().use { BugreportParser.parse(it, f.name.endsWith(".zip")) }
        println("REAL REPORT -> $r")
        assertTrue(r.linesRead > 1000)
    }
}
