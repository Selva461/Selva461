package com.selva.chargefix

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Connects to our ShellUserService through Shizuku (ADB-level rights, no root). */
object ShizukuShell {

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile private var service: IShellService? = null
    @Volatile private var bindLatch = CountDownLatch(1)
    private var bound = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder)
            bindLatch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false
        }
    }

    fun state(ctx: Context): State {
        val installed = try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }
        return when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
            Shizuku.isPreV11() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Returns the shell service, binding it if needed. Blocking - call off the main thread. */
    @Synchronized
    fun get(timeoutSec: Long = 15): IShellService? {
        service?.let { s -> if (s.asBinder().pingBinder()) return s }
        service = null
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
        bindLatch = CountDownLatch(1)
        if (!bound) {
            runCatching { Shizuku.bindUserService(args, conn); bound = true }
        }
        bindLatch.await(timeoutSec, TimeUnit.SECONDS)
        return service
    }

    const val REQUEST_CODE = 42
}
