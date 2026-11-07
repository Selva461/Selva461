package com.selva.chargefix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the watchdog again after every restart, so nothing needs to be opened by hand. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (Prefs.watchdog(context)) runCatching { WatchdogService.start(context) }
        }
    }
}
