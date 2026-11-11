package com.granularvolume.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.granularvolume.MainActivity
import com.granularvolume.util.ControlLive
import com.granularvolume.util.Prefs
import com.granularvolume.util.ProAccess
import com.granularvolume.util.ReviewHelper

class GranularVolumeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        ProAccess.evaluateGrandfather(applicationContext)

        val running = ControlLive.running
        if (running) {
            Prefs.setServiceWasRunning(applicationContext, false)
            stopService(Intent(this, VolumeControlService::class.java))
            syncTile(active = false)
        } else {
            startForegroundService(Intent(this, VolumeControlService::class.java))
            syncTile(active = true)
            ReviewHelper.reviewIntentIfDue(applicationContext)?.let { startActivityAndCollapseCompat(it) }
        }
    }

    private fun syncTile(active: Boolean = ControlLive.running) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(com.granularvolume.R.string.app_name)
        tile.updateTile()
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
