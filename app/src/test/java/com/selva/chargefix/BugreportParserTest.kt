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

    @Test
    fun buildsTimelineFromBatteryHistory() {
        val r = BugreportParser.parseText(
            "Battery History (1% used, 56KB used of 4096KB, 216 strings using 11KB):\n" +
            "                    0 (20) RESET:TIME: 1970-02-04-03-02-31\n" +
            "                    0 (4) 061 80080000 status=charging health=good plug=usb temp=250 volt=3899 charge=3138 +running +plugged\n" +
            "               +369ms (14) TIME: 2025-02-25-20-33-33\n" +
            "            +19s806ms (2) 061 80180000 +screen\n" +
            "         +15m00s101ms (5) 061 c0900021 status=discharging plug=none volt=4031 -plugged\n" +
            "      +1h15m00s000ms (2) 058 c0900021 temp=300 charge=2990 -screen\n" +
            "\n")
        // First entry is before the clock was set (1970) -> skipped; the rest get real dates.
        assertEquals(3, r.history.size)
        val f = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US)
        val base = f.parse("2025-02-25-20-33-33")!!.time - 369
        assertEquals(base + 19_806, r.history[0].t)
        assertEquals(true, r.history[0].plugged)
        assertEquals(true, r.history[0].screenOn)
        assertEquals(false, r.history[1].plugged)
        assertEquals(58, r.history[2].level)
        assertEquals(2990, r.history[2].chargeMah)
        assertEquals(30f, r.history[2].tempC)
        assertEquals(false, r.history[2].screenOn)
    }

    /** Runs against a real report when BUGREPORT points at one (not committed). */
    @Test
    fun realReportIfProvided() {
        val path = System.getenv("BUGREPORT") ?: return
        val f = File(path)
        val r = f.inputStream().use { BugreportParser.parse(it, f.name.endsWith(".zip")) }
        println("REAL REPORT -> ${r.copy(history = mutableListOf())}")
        println("REAL HISTORY -> ${r.history.size} entries, ${r.history.firstOrNull()?.let { java.util.Date(it.t) }} .. ${r.history.lastOrNull()?.let { java.util.Date(it.t) }}")
        println("REAL HISTORY SAMPLE -> ${r.history.take(3)}")
        assertTrue(r.linesRead > 1000)
    }
}
