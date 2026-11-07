package com.selva.chargefix

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * USB-C port commands. These run inside a short-lived process that the app starts
 * over its own ADB connection (see AdbShell), so it has ADB "shell" rights (uid 2000).
 * The shell user holds MANAGE_USB, so it may command the USB-C port controller -
 * something a normal app can't do - without needing root.
 */
object UsbOps {

    fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        out.trim()
    } catch (e: Exception) {
        "exec failed: $e"
    }

    fun usbOp(op: String, pauseSec: Int): String {
        val log = StringBuilder()
        try {
            val mgr = usbManager()
            val port = pickPort(mgr, log) ?: return log.append("No USB port found.").toString()
            when (op) {
                OP_LIMIT_POWER -> {
                    log.appendLine("Limit power ON  -> ${statusName(call(mgr, "enableLimitPowerTransfer", port, true))}")
                    Thread.sleep(pauseSec * 1000L)
                    log.appendLine("Limit power OFF -> ${statusName(call(mgr, "enableLimitPowerTransfer", port, false))}")
                }
                OP_PORT_RESET -> {
                    log.appendLine("Reset USB port -> ${statusName(call(mgr, "resetUsbPort", port, null))}")
                }
                OP_ROLE_SWAP -> {
                    log.appendLine(exec("dumpsys usb set-port-roles $port source host").lineSequence().take(3).joinToString(" | "))
                    Thread.sleep(pauseSec * 1000L)
                    log.appendLine(exec("dumpsys usb set-port-roles $port sink device").lineSequence().take(3).joinToString(" | "))
                }
                OP_INFO -> {
                    val hal = runCatching { mgr.javaClass.getMethod("getUsbHalVersion").invoke(mgr) }.getOrNull()
                    log.appendLine("USB HAL version code: $hal")
                    runCatching {
                        log.appendLine(mgr.javaClass.getMethod("getPortStatus", String::class.java).invoke(mgr, port).toString())
                    }
                }
                else -> log.appendLine("Unknown op $op")
            }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log.appendLine("Failed: $cause")
        }
        return log.toString().trim()
    }

    // ---- hidden-API plumbing (allowed here because this is not an app process) ----

    private fun usbManager(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "usb") as IBinder
        return Class.forName("android.hardware.usb.IUsbManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private fun pickPort(mgr: Any, log: StringBuilder): String? {
        val ports = mgr.javaClass.getMethod("getPorts").invoke(mgr) as List<*>
        val ids = ports.mapNotNull { p -> p?.javaClass?.getMethod("getId")?.invoke(p) as? String }
        val connected = ids.firstOrNull { id ->
            runCatching {
                val st = mgr.javaClass.getMethod("getPortStatus", String::class.java).invoke(mgr, id)
                st?.javaClass?.getMethod("isConnected")?.invoke(st) as? Boolean
            }.getOrNull() == true
        }
        val id = connected ?: ids.firstOrNull()
        log.appendLine("Port: $id (of ${ids.size})")
        return id
    }

    /** Calls an IUsbManager method that reports its result through IUsbOperationInternal. */
    private fun call(mgr: Any, name: String, port: String, flag: Boolean?): Int {
        val cb = OpCallback()
        val cbIface = Class.forName("android.hardware.usb.IUsbOperationInternal\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, cb)
        val m = mgr.javaClass.methods.firstOrNull { it.name == name }
            ?: return STATUS_NO_METHOD
        val opId = (System.nanoTime() and 0x7fffffff).toInt()
        if (flag == null) m.invoke(mgr, port, opId, cbIface) else m.invoke(mgr, port, flag, opId, cbIface)
        return if (cb.latch.await(10, TimeUnit.SECONDS)) cb.status else STATUS_TIMEOUT
    }

    private class OpCallback : Binder() {
        val latch = CountDownLatch(1)
        @Volatile var status = STATUS_TIMEOUT
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == FIRST_CALL_TRANSACTION) {
                runCatching { data.enforceInterface("android.hardware.usb.IUsbOperationInternal") }
                status = data.readInt()
                latch.countDown()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    // ---- constants ----
    const val OP_LIMIT_POWER = "limit_power"
    const val OP_PORT_RESET = "port_reset"
    const val OP_ROLE_SWAP = "role_swap"
    const val OP_INFO = "info"
    private const val STATUS_TIMEOUT = -100
    private const val STATUS_NO_METHOD = -101

    fun statusName(s: Int) = when (s) {
        0 -> "SUCCESS"
        1 -> "ERROR_INTERNAL"
        2 -> "NOT_SUPPORTED by this phone's USB driver"
        3 -> "PORT_MISMATCH"
        4 -> "ERROR_OTHER"
        STATUS_TIMEOUT -> "no answer (timeout)"
        STATUS_NO_METHOD -> "not available on this Android version"
        else -> "status $s"
    }
}
