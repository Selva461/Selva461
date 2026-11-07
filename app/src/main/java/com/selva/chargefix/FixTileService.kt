package com.selva.chargefix

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile ("Fix charging") so the reset is one swipe away. */
class FixTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Fix charging"; updateTile() }
    }

    override fun onClick() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE; tile.label = "Resetting…"; tile.updateTile()
        Thread {
            val r = ChargeControl.resetCharging()
            mainExecutor.execute {
                tile.state = Tile.STATE_INACTIVE; tile.label = "Fix charging"; tile.updateTile()
                if (r.ok) {
                    Toast.makeText(this, "Charger reset done", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Needs root - opening app", Toast.LENGTH_LONG).show()
                    openApp()
                }
            }
        }.start()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
