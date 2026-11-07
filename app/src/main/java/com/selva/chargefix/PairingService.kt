package com.selva.chargefix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.android.AdbMdns
import java.net.InetAddress

/**
 * One-time pairing helper. It watches for the pairing port that appears while
 * "Pair device with pairing code" is open in Wireless debugging, and shows a
 * notification where you type the 6-digit code - so you never leave Settings.
 */
class PairingService : Service() {

    @Volatile private var pairingPort = -1
    private var mdns: AdbMdns? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                if (!code.isNullOrBlank()) doPair(code)
                return START_NOT_STICKY
            }
        }
        val n = build("Waiting for the pairing code…",
            "Open Wireless debugging → \"Pair device with pairing code\", then pull down this notification and enter the code.", true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(ID, n)

        if (mdns == null) {
            mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, object : AdbMdns.OnAdbDaemonDiscoveredListener {
                override fun onPortChanged(host: InetAddress?, port: Int) {
                    if (port > 0) pairingPort = port
                }
            }).also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun doPair(code: String) {
        notifyMsg("Pairing…", "Connecting to Wireless debugging.", false)
        Thread {
            // The pairing port shows up a moment after the dialog opens.
            repeat(10) { if (pairingPort <= 0) Thread.sleep(500) }
            if (pairingPort <= 0) {
                notifyMsg("Pairing code dialog not found",
                    "Keep \"Pair device with pairing code\" open on screen, then enter the code again.", true)
                return@Thread
            }
            val msg = try {
                AdbShell.pair(this, pairingPort, code)
            } catch (e: Exception) {
                "Pairing failed: ${e.message ?: e}"
            }
            if (AdbShell.isPaired(this)) {
                getSystemService(NotificationManager::class.java).notify(ID_DONE,
                    NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_bolt)
                        .setContentTitle("Charge Fix is set up")
                        .setContentText(msg)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                        .setContentIntent(WatchdogService.openAppIntent(this))
                        .setAutoCancel(true).build())
                stopEverything()
            } else {
                notifyMsg("Pairing didn't work", "$msg\nOpen a new pairing code and try again.", true)
            }
        }.start()
    }

    private fun notifyMsg(title: String, text: String, withInput: Boolean) =
        getSystemService(NotificationManager::class.java).notify(ID, build(title, text, withInput))

    private fun build(title: String, text: String, withInput: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", PendingIntent.getService(this, 11,
                Intent(this, PairingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (withInput) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            val pi = PendingIntent.getService(this, 10,
                Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(NotificationCompat.Action.Builder(0, "Enter pairing code", pi)
                .addRemoteInput(input).build())
        }
        return b.build()
    }

    private fun stopEverything() {
        runCatching { mdns?.stop() }
        mdns = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { mdns?.stop() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pairing"
        private const val ID = 20
        private const val ID_DONE = 21
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "com.selva.chargefix.PAIR_CODE"
        private const val ACTION_STOP = "com.selva.chargefix.PAIR_STOP"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, PairingService::class.java))

        private fun createChannel(ctx: Context) =
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Setup / pairing", NotificationManager.IMPORTANCE_HIGH))
    }
}
