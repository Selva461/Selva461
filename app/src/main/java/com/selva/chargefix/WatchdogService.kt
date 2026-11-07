package com.selva.chargefix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Background watchdog: while the phone is plugged in, it checks every 10 s whether
 * current is really flowing into the battery. If the phone shows "charging" but the
 * battery is actually draining for about a minute, it alerts you - and if you have
 * root and turned on auto-fix, it resets the charger by itself.
 */
class WatchdogService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var badSamples = 0
    private var levelAtPlugIn = -1
    private var wasPlugged = false
    private var fixAttemptsThisPlug = 0
    private var lastFixAt = 0L
    private var alerted = false
    private var ticking = false
    private var plugInAt = 0L
    private var goodSince = 0L
    private var doneThisPlug = false

    /** Wakes the watchdog only when a charger is connected; otherwise it sleeps (no polling). */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.post {
                if (i.action == Intent.ACTION_POWER_CONNECTED) startTicking() else {
                    resetPlug(); stopTicking()
                    getSystemService(NotificationManager::class.java)
                        .notify(ID_STATUS, statusNotification("Sleeping - wakes up when you plug in"))
                }
            }
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun resetPlug() {
        wasPlugged = false; badSamples = 0; fixAttemptsThisPlug = 0; alerted = false
        levelAtPlugIn = -1; goodSince = 0L; doneThisPlug = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastArmTry = 0L

    /** Re-arms the no-Wi-Fi mode the moment the phone joins any Wi-Fi after a restart. */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.postDelayed({ armIfNeeded() }, 5_000)
        }
    }

    private fun armIfNeeded() {
        if (!AdbShell.isPaired(this)) return
        if (System.currentTimeMillis() - lastArmTry < 5 * 60_000L) return
        lastArmTry = System.currentTimeMillis()
        val st = AdbShell.ensureArmed(this)
        if (st == "armed") lastArmTry = 0L // check again on the next Wi-Fi join
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        thread = HandlerThread("charge-watchdog").also { it.start() }
        handler = Handler(thread.looper)
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback)
        }
        handler.postDelayed({ armIfNeeded() }, 10_000)
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWatchdog(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = statusNotification("Watching your charger…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_STATUS, n)
        }
        if (intent?.action == ACTION_FIX_NOW) handler.post { doFix(manual = true) }
        // Check now (in case it's already plugged in); after that only plug-in wakes it.
        stopTicking(); startTicking()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        runCatching { unregisterReceiver(powerReceiver) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            check()
            if (ticking) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun check() {
        val s = BatteryReader.read(this)
        val nm = getSystemService(NotificationManager::class.java)

        if (!s.isPlugged) {
            resetPlug(); stopTicking()
            nm.notify(ID_STATUS, statusNotification("Sleeping - wakes up when you plug in"))
            return
        }
        if (!wasPlugged) {
            wasPlugged = true; levelAtPlugIn = s.level; plugInAt = System.currentTimeMillis()
        }
        // Give up watching after 10 min on the charger (no endless polling).
        if (System.currentTimeMillis() - plugInAt > MAX_WATCH_MS) {
            stopTicking()
            nm.notify(ID_STATUS, statusNotification("Charging checked · sleeping until next plug-in"))
            return
        }

        val verdict = BatteryReader.verdict(s)
        if (verdict == Verdict.CHECKING) {
            nm.notify(ID_STATUS, statusNotification("Checking charging… ${s.level}%"))
            return
        }
        // A dropping battery level while plugged in is proof, whatever the current sensor says.
        val levelDropped = levelAtPlugIn >= 0 && s.level < levelAtPlugIn
        val bad = verdict == Verdict.FAKE_CHARGING || levelDropped
        badSamples = if (bad) badSamples + 1 else 0

        val cur = s.netCurrentMa?.let { "${if (it > 0) "+" else ""}$it mA" } ?: "? mA"
        if (!bad) {
            if (alerted) nm.cancel(ID_ALERT)
            alerted = false
            if (goodSince == 0L) goodSince = System.currentTimeMillis()
            // Really charging for a full minute (and the level isn't falling) -> done until next plug-in.
            if (System.currentTimeMillis() - goodSince >= 60_000) {
                stopTicking()
                nm.notify(ID_STATUS, statusNotification("Really charging ✓ ${s.level}% · sleeping until next plug-in"))
            } else {
                nm.notify(ID_STATUS, statusNotification("Really charging · ${s.level}% · $cur"))
            }
            return
        }
        goodSince = 0L
        nm.notify(ID_STATUS, statusNotification("NOT really charging · ${s.level}% · $cur"))

        if (badSamples >= BAD_SAMPLES_TO_ACT || levelDropped) {
            val canAutoFix = Prefs.autoFix(this) &&
                fixAttemptsThisPlug < MAX_AUTO_FIXES &&
                System.currentTimeMillis() - lastFixAt > FIX_COOLDOWN_MS
            if (canAutoFix) {
                doFix(manual = false)
            } else if (!alerted) {
                alerted = true
                nm.notify(ID_ALERT, alertNotification(cur, s.level))
            }
        }
    }

    private fun doFix(manual: Boolean) {
        lastFixAt = System.currentTimeMillis()
        if (!manual) fixAttemptsThisPlug++
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(ID_STATUS, statusNotification("Resetting charger…"))
        val r = Fixer.fix(this, ChargeControl.hasRoot())
        badSamples = 0
        levelAtPlugIn = BatteryReader.read(this).level
        val text = if (r.fixed) "Really charging again${r.method?.let { " (${it.label})" } ?: ""}."
        else "Couldn't fix it automatically. Unplug, restart the phone, plug in again."
        nm.notify(ID_ALERT, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(if (r.fixed) "Charging fixed" else "Charging fix failed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(this))
            .setAutoCancel(true)
            .build())
        alerted = !r.fixed
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Charge Fix watchdog")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(this))
            .addAction(0, "Stop watching", PendingIntent.getService(
                this, 2, Intent(this, WatchdogService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    private fun alertNotification(cur: String, level: Int): Notification {
        val text = "The phone says it's charging, but the battery is draining ($cur, $level%). " +
            "Tap \"Fix now\", or unplug and restart the phone."
        return NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Fake charging detected!")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(this))
            .addAction(0, "Fix now", PendingIntent.getService(
                this, 1, Intent(this, WatchdogService::class.java).setAction(ACTION_FIX_NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
    }

    companion object {
        const val ACTION_FIX_NOW = "com.selva.chargefix.FIX_NOW"
        const val ACTION_STOP = "com.selva.chargefix.STOP"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2
        private const val INTERVAL_MS = 5_000L
        private const val MAX_WATCH_MS = 10 * 60_000L
        private const val BAD_SAMPLES_TO_ACT = 6 // ~30 seconds
        private const val MAX_AUTO_FIXES = 3
        private const val FIX_COOLDOWN_MS = 2 * 60_000L

        fun start(ctx: Context) {
            Prefs.setWatchdog(ctx, true)
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchdogService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs.setWatchdog(ctx, false)
            ctx.stopService(Intent(ctx, WatchdogService::class.java))
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Watchdog status", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Fake charging alerts", NotificationManager.IMPORTANCE_HIGH))
        }

        fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
