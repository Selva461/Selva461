package com.selva.chargefix

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var hasRoot = false

    private lateinit var card: MaterialCardView
    private lateinit var tvVerdict: TextView
    private lateinit var tvVerdictDetail: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvRoot: TextView
    private lateinit var tvLog: TextView
    private lateinit var swWatchdog: MaterialSwitch
    private lateinit var swAutoFix: MaterialSwitch
    private lateinit var controlButtons: List<MaterialButton>
    private lateinit var rootButtons: List<MaterialButton>
    private lateinit var btnReset: MaterialButton
    private lateinit var tvAdb: TextView
    private lateinit var btnAdbSetup: MaterialButton
    private lateinit var btnAdbTest: MaterialButton
    private var fixing = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }
    private val poll = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        card = findViewById(R.id.cardVerdict)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvVerdictDetail = findViewById(R.id.tvVerdictDetail)
        tvDetails = findViewById(R.id.tvDetails)
        tvRoot = findViewById(R.id.tvRoot)
        tvLog = findViewById(R.id.tvLog)
        swWatchdog = findViewById(R.id.swWatchdog)
        swAutoFix = findViewById(R.id.swAutoFix)

        btnReset = findViewById(R.id.btnReset)
        tvAdb = findViewById(R.id.tvAdb)
        btnAdbSetup = findViewById(R.id.btnAdbSetup)
        btnAdbTest = findViewById(R.id.btnAdbTest)
        val btnStop = findViewById<MaterialButton>(R.id.btnStop)
        val btnStart = findViewById<MaterialButton>(R.id.btnStart)
        val btnReboot = findViewById<MaterialButton>(R.id.btnReboot)
        controlButtons = listOf(btnReset, btnStop, btnStart, btnReboot)
        rootButtons = listOf(btnStop, btnStart, btnReboot)

        btnReset.setOnClickListener { runFix() }
        btnAdbSetup.setOnClickListener { onSetupButton() }
        btnAdbTest.setOnClickListener { testAdb() }
        btnStop.setOnClickListener { runRoot("Turning charging OFF…") { ChargeControl.stopCharging() } }
        btnStart.setOnClickListener { runRoot("Turning charging ON…") { ChargeControl.startCharging() } }
        btnReboot.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Restart phone?")
                .setMessage("This restarts your phone right now, which always clears the stuck charging state.")
                .setPositiveButton("Restart") { _, _ -> runRoot("Restarting…") { ChargeControl.reboot() } }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.btnBatterySettings).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }

        swWatchdog.isChecked = Prefs.watchdog(this)
        swAutoFix.isChecked = Prefs.autoFix(this)
        swWatchdog.setOnCheckedChangeListener { _, on ->
            if (on) {
                askNotificationPermission(); WatchdogService.start(this)
            } else WatchdogService.stop(this)
        }
        swAutoFix.setOnCheckedChangeListener { _, on ->
            Prefs.setAutoFix(this, on)
            if (on && !swWatchdog.isChecked) swWatchdog.isChecked = true
        }
        if (Prefs.watchdog(this)) WatchdogService.start(this)

        checkRoot()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ui.post(poll)
        updateAdb()
    }

    override fun onPause() {
        unregisterReceiver(batteryReceiver)
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val s = BatteryReader.read(this)
        val (title, detail, color) = when (BatteryReader.verdict(s)) {
            Verdict.UNPLUGGED -> Triple("Not plugged in", "Plug in the charger to test it.", 0xFF6E7781.toInt())
            Verdict.CHARGING_OK -> Triple("Really charging ✓", "Power is going into the battery.", 0xFF1A7F37.toInt())
            Verdict.FULL -> Triple("Full / topping off", "Battery is (nearly) full.", 0xFF1A7F37.toInt())
            Verdict.FAKE_CHARGING -> Triple("FAKE charging ✗",
                "Bolt is showing, but the battery is draining. Press \"Fix charging now\".",
                0xFFCF222E.toInt())
            Verdict.UNKNOWN -> Triple("Can't tell", "This phone doesn't report charging current.", 0xFF9A6700.toInt())
        }
        card.setCardBackgroundColor(color)
        tvVerdict.setTextColor(Color.WHITE); tvVerdictDetail.setTextColor(Color.WHITE); tvDetails.setTextColor(Color.WHITE)
        tvVerdict.text = title
        tvVerdictDetail.text = detail
        val cur = s.netCurrentMa?.let { "${if (it > 0) "+" else ""}$it mA" } ?: "not reported"
        tvDetails.text = buildString {
            appendLine("Battery : ${s.level}%")
            appendLine("Source  : ${s.pluggedLabel}")
            appendLine("Status  : ${s.statusLabel}")
            appendLine("Current : $cur  (+ in / − out)")
            appendLine("Voltage : ${s.voltageMv} mV")
            append("Temp    : ${"%.1f".format(s.temperatureC)} °C")
        }
    }

    private fun checkRoot() {
        io.execute {
            val root = ChargeControl.hasRoot()
            val nodes = ChargeControl.presentNodes()
            ui.post {
                hasRoot = root
                tvRoot.text = when {
                    root && nodes.isNotEmpty() -> "✓ Root access OK · ${nodes.size} charging switch(es) found"
                    root -> "✓ Root OK, but no known charging switch was seen yet (will still try)."
                    else -> "No root - \"Fix charging now\" uses no-root mode instead (see above). " +
                        "The three root-only buttons below are greyed out."
                }
                rootButtons.forEach { it.alpha = if (root) 1f else 0.4f }
            }
        }
    }

    private fun runRoot(msg: String, action: () -> ChargeControl.Result) {
        if (!hasRoot) {
            Toast.makeText(this, "Needs root. Without root: unplug and restart the phone.", Toast.LENGTH_LONG).show()
            checkRoot()
            return
        }
        tvLog.text = msg
        controlButtons.forEach { it.isEnabled = false }
        io.execute {
            val r = action()
            ui.post {
                controlButtons.forEach { it.isEnabled = true }
                tvLog.text = (if (r.ok) "Done.\n" else "Failed.\n") + r.output
                refresh()
            }
        }
    }

    private fun runFix() {
        if (fixing) return
        if (!hasRoot && !AdbShell.isPaired(this)) {
            showSetupDialog()
            return
        }
        fixing = true
        controlButtons.forEach { it.isEnabled = false }
        btnReset.text = "Fixing… (keep the charger plugged in)"
        tvLog.text = "Starting…"
        io.execute {
            val r = Fixer.fix(this, hasRoot) { p -> ui.post { tvLog.text = p } }
            ui.post {
                fixing = false
                controlButtons.forEach { it.isEnabled = true }
                btnReset.text = "Fix charging now"
                tvLog.text = r.log.trim()
                refresh()
            }
        }
    }

    private fun updateAdb() {
        val paired = AdbShell.isPaired(this)
        val toggle = AdbShell.canToggleWirelessDebugging(this)
        val working = Fixer.workingMethod(this)
        tvAdb.text = when {
            !paired -> "Not set up. Charge Fix pairs with your phone's own Wireless debugging once " +
                "(about 2 minutes). No other app needed."
            toggle -> "✓ Ready. On Wi-Fi, \"Fix charging now\" switches Wireless debugging on for a few " +
                "seconds, fixes, and switches it off again."
            else -> "✓ Paired. Turn on Wireless debugging before pressing Fix (the app couldn't get " +
                "permission to switch it itself)."
        } + (working?.takeIf { it != Fixer.Method.ROOT }?.let { "\nWorked last time: ${it.label}" } ?: "")
        btnAdbSetup.text = if (paired) "Forget pairing" else "Set up no-root mode"
        btnAdbTest.visibility = if (paired) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun onSetupButton() {
        if (!AdbShell.isPaired(this)) { showSetupDialog(); return }
        AlertDialog.Builder(this)
            .setTitle("Forget pairing?")
            .setMessage("Charge Fix will delete its key. Also remove \"Charge Fix\" under Wireless debugging → Paired devices.")
            .setPositiveButton("Forget") { _, _ -> AdbShell.forget(this); updateAdb() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Set up no-root mode")
            .setMessage(getString(R.string.adb_steps))
            .setPositiveButton("Start") { _, _ ->
                askNotificationPermission()
                PairingService.start(this)
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure {
                    Toast.makeText(this, "Turn on Developer options first (see step 1).", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testAdb() {
        tvLog.text = "Connecting to Wireless debugging…"
        io.execute {
            val out = try {
                AdbShell.session(this) { sh ->
                    val id = sh.run("id")
                    val usb = sh.usbOp(UsbOps.OP_INFO, 0)
                    (if (id.contains("uid=2000")) "✓ Works - shell access OK\n" else "$id\n") + usb
                }
            } catch (e: Exception) {
                "✗ ${e.message ?: e}"
            }
            ui.post { tvLog.text = out; updateAdb() }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
