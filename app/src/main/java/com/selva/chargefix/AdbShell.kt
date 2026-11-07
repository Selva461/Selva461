package com.selva.chargefix

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Charge Fix's own ADB client - no Shizuku or any other app involved.
 *
 * Android's Wireless debugging lets a paired client run commands as the "shell" user.
 * The app pairs with its own phone once (you type the pairing code), and from then on
 * it can connect to itself, run the USB-port commands, and disconnect.
 *
 * During the first connection it grants itself WRITE_SECURE_SETTINGS, which lets it
 * switch Wireless debugging ON only for the few seconds a fix takes and OFF again.
 */
object AdbShell {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val PREFS = "adb"

    // ---------------------------------------------------------------- keys / identity

    internal class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val keyFile = File(ctx.filesDir, "adb_key.pk8")
        private val certFile = File(ctx.filesDir, "adb_cert.der")
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            if (keyFile.exists() && certFile.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=Charge Fix")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                    Date(now + 30L * 365 * 86_400_000L), name, kp.public
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
                key = kp.private
                cert = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "Charge Fix"
    }

    @Volatile private var manager: Manager? = null
    @Synchronized private fun mgr(ctx: Context): Manager =
        manager ?: Manager(ctx.applicationContext).also { manager = it }

    // ---------------------------------------------------------------- state

    fun isPaired(ctx: Context) = prefs(ctx).getBoolean("paired", false)
    fun canToggleWirelessDebugging(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun forget(ctx: Context) {
        runCatching { manager?.close() }
        manager = null
        File(ctx.filesDir, "adb_key.pk8").delete()
        File(ctx.filesDir, "adb_cert.der").delete()
        prefs(ctx).edit().putBoolean("paired", false).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun wirelessDebuggingOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun setWirelessDebugging(ctx: Context, on: Boolean) = runCatching {
        Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, if (on) 1 else 0)
    }.isSuccess

    // ---------------------------------------------------------------- pairing

    /** Pairs with this phone's Wireless debugging using the 6-digit code. Blocking. */
    fun pair(ctx: Context, port: Int, code: String): String {
        val m = mgr(ctx)
        val ok = m.pair("127.0.0.1", port, code.trim())
        if (!ok) return "Pairing failed - check the code and try again."
        prefs(ctx).edit().putBoolean("paired", true).apply()
        // Use the fresh connection to let the app switch Wireless debugging on/off itself.
        return try {
            connect(ctx, m)
            val pkg = ctx.packageName
            run(m, "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            val granted = canToggleWirelessDebugging(ctx)
            runCatching { m.disconnect() }
            "Paired ✓" + if (granted) " - Charge Fix can now switch Wireless debugging on and off by itself." else ""
        } catch (e: Exception) {
            "Paired ✓ (couldn't finish setup: ${e.message}). Tap Test connection while Wireless debugging is on."
        }
    }

    // ---------------------------------------------------------------- sessions

    class Session internal constructor(private val ctx: Context, private val m: Manager) {
        fun run(cmd: String): String = run(m, cmd)
        /** Runs a USB operation in a helper process with shell rights. */
        fun usbOp(op: String, pauseSec: Int = 4): String {
            val apk = ctx.applicationInfo.sourceDir
            return run("CLASSPATH=$apk app_process /system/bin ${ShellMain::class.java.name} $op $pauseSec 2>&1")
        }
    }

    class NotReady(msg: String) : Exception(msg)

    /**
     * Turns Wireless debugging on (if allowed and needed), connects to this phone,
     * runs [block], then disconnects and turns Wireless debugging back off.
     */
    @Synchronized
    fun <T> session(ctx: Context, block: (Session) -> T): T {
        if (!isPaired(ctx)) throw NotReady("Not set up yet. Tap \"Set up no-root mode\" first.")
        if (!onWifi(ctx)) throw NotReady("Connect to Wi-Fi first - Android's Wireless debugging only works on Wi-Fi.")
        val wasOn = wirelessDebuggingOn(ctx)
        var turnedOn = false
        if (!wasOn) {
            if (!canToggleWirelessDebugging(ctx)) {
                throw NotReady("Turn on Wireless debugging (Developer options), then try again.")
            }
            if (!setWirelessDebugging(ctx, true)) throw NotReady("Couldn't turn on Wireless debugging.")
            turnedOn = true
        }
        val m = mgr(ctx)
        try {
            connect(ctx, m)
            return block(Session(ctx, m))
        } finally {
            runCatching { m.disconnect() }
            if (turnedOn) setWirelessDebugging(ctx, false)
        }
    }

    private fun connect(ctx: Context, m: Manager) {
        if (m.isConnected) return
        // adbd needs a few seconds to come up after Wireless debugging is switched on.
        var last: Exception? = null
        repeat(6) {
            try {
                if (m.connectTls(ctx, 5_000)) return
            } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                prefs(ctx).edit().putBoolean("paired", false).apply()
                throw NotReady("This phone forgot the pairing. Set up no-root mode again.")
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(2_000)
        }
        throw NotReady("Couldn't connect to Wireless debugging" +
            (last?.message?.let { " ($it)" } ?: "") +
            ". If Android asked \"Allow wireless debugging on this network?\", tap Always allow and try again.")
    }

    private fun run(m: AbsAdbConnectionManager, cmd: String): String {
        m.openStream("shell:$cmd").use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val input = s.openInputStream()
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } catch (e: java.io.IOException) {
                // The stream is closed when the command exits; keep what we got.
            }
            return out.toString().trim()
        }
    }
}
