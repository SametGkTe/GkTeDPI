package com.sametgkte.gktedpi.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.widget.GkTeWidgetUpdater

/**
 * Broadcast API Receiver for external apps, Tasker, MacroDroid, and ADB scripts.
 */
class ExternalControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (!GkTeVpnApiBridge.isApiEnabled(context)) return

        val token = intent.getStringExtra(GkTeVpnApiBridge.EXTRA_TOKEN)
        if (!GkTeVpnApiBridge.isTokenValid(context, token)) return

        GkTeVpnApiBridge.applyOptionalOverrides(
            context,
            intent.getStringExtra(GkTeVpnApiBridge.EXTRA_MODE),
            intent.getStringExtra(GkTeVpnApiBridge.EXTRA_PRESET_ID),
            intent.getStringExtra(GkTeVpnApiBridge.EXTRA_PROFILE_ID),
            intent.getStringExtra(GkTeVpnApiBridge.EXTRA_CMD_ARGS),
        )

        when (act) {
            GkTeVpnApiBridge.ACTION_BROADCAST_START -> {
                GkTeVpnApiBridge.launchGatewayActivityFromBackground(
                    context,
                    GkTeVpnApiBridge.ACTION_START,
                    intent.extras
                )
            }
            GkTeVpnApiBridge.ACTION_BROADCAST_STOP -> {
                val (status, _) = appStatus
                if (status == AppStatus.Running) {
                    ServiceManager.stop(context)
                }
                GkTeWidgetUpdater.updateAll(context)
                GkTeVpnApiBridge.broadcastStateChange(context)
            }
            GkTeVpnApiBridge.ACTION_BROADCAST_TOGGLE -> {
                val (status, _) = appStatus
                if (status == AppStatus.Running) {
                    ServiceManager.stop(context)
                    GkTeWidgetUpdater.updateAll(context)
                    GkTeVpnApiBridge.broadcastStateChange(context)
                } else {
                    GkTeVpnApiBridge.launchGatewayActivityFromBackground(
                        context,
                        GkTeVpnApiBridge.ACTION_START,
                        intent.extras
                    )
                }
            }
            GkTeVpnApiBridge.ACTION_BROADCAST_STATUS -> {
                GkTeVpnApiBridge.broadcastStateChange(context)
            }
        }
    }
}
