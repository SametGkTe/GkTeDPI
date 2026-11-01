package com.sametgkte.gktedpi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val prefs = context.getPreferences()
        if (!prefs.getBoolean("auto_start_boot", false)) {
            return
        }

        val mode = prefs.mode()
        if (mode == Mode.VPN && VpnService.prepare(context) != null) {
            Log.w("BootReceiver", "VPN permission not granted yet; skipping auto-start on boot")
            return
        }

        Log.i("BootReceiver", "Auto-starting GkTeDPI ($mode) after $action")
        ServiceManager.start(context, mode)
    }
}
