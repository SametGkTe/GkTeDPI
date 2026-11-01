package com.sametgkte.gktedpi.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.activities.MainActivity
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.FAILED_BROADCAST
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.data.SENDER
import com.sametgkte.gktedpi.data.STARTED_BROADCAST
import com.sametgkte.gktedpi.data.STOPPED_BROADCAST
import com.sametgkte.gktedpi.data.Sender
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode

@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    companion object {
        private val TAG: String = QuickTileService::class.java.simpleName
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd) ?: return

            when (intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.senderName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateStatus()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(STARTED_BROADCAST)
                addAction(STOPPED_BROADCAST)
                addAction(FAILED_BROADCAST)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStopListening() {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { toggleService() }
        } else {
            toggleService()
        }
    }

    private fun toggleService() {
        val (status, _) = appStatus
        when (status) {
            AppStatus.Running -> ServiceManager.stop(this)
            AppStatus.Halted -> {
                val mode = getPreferences().mode()
                if (mode == Mode.VPN && VpnService.prepare(this) != null) {
                    val wrapper = PendingIntentActivityWrapper(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT,
                        false
                    )
                    TileServiceCompat.startActivityAndCollapse(this, wrapper)
                } else {
                    ServiceManager.start(this, mode)
                }
            }
        }
    }

    private fun updateStatus() {
        val tile = qsTile ?: return
        val (status, _) = appStatus
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = ProfileManager.getActiveProfile(this).name
        }
        tile.state = when (status) {
            AppStatus.Running -> Tile.STATE_ACTIVE
            AppStatus.Halted -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
        Log.d(TAG, "QuickTile updated: $status")
    }
}
