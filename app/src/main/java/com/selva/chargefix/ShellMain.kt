package com.selva.chargefix

/**
 * Entry point for the helper process started over ADB as:
 *   CLASSPATH=<this apk> app_process /system/bin com.selva.chargefix.ShellMain <op> <pauseSec>
 * It runs as the shell user, does one USB operation, prints a log and exits.
 */
object ShellMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val op = args.getOrNull(0) ?: UsbOps.OP_INFO
        val pause = args.getOrNull(1)?.toIntOrNull() ?: 4
        println(UsbOps.usbOp(op, pause))
        System.out.flush()
        System.exit(0)
    }
}
