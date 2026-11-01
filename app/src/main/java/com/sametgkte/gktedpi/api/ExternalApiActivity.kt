package com.sametgkte.gktedpi.api

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode
import com.sametgkte.gktedpi.widget.GkTeWidgetUpdater

/**
 * Zero-UI Translucent Gateway Activity for External Apps, Deep Links (gktedpi://vpn/...),
 * and Home Screen Widgets. Automatically handles VpnService.prepare() when required.
 */
class ExternalApiActivity : AppCompatActivity() {

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
                finishWithStatus(ok = true, message = "VPN_STARTED")
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                finishWithStatus(ok = false, message = "VPN_PERMISSION_DENIED")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) {
            finishWithStatus(false, "EMPTY_INTENT")
            return
        }

        val internalTrusted = incoming.getBooleanExtra("internal_trusted", false)
        if (!internalTrusted) {
            if (!GkTeVpnApiBridge.isApiEnabled(this)) {
                finishWithStatus(false, "API_DISABLED")
                return
            }
            val token = incoming.getStringExtra(GkTeVpnApiBridge.EXTRA_TOKEN)
                ?: incoming.data?.getQueryParameter("token")
            if (!GkTeVpnApiBridge.isTokenValid(this, token)) {
                finishWithStatus(false, "INVALID_API_TOKEN")
                return
            }
        }

        val uri = incoming.data
        val resolvedAction = when {
            uri != null && uri.scheme == "gktedpi" -> {
                val path = (uri.host.orEmpty() + uri.path.orEmpty()).lowercase()
                when {
                    path.contains("stop") -> GkTeVpnApiBridge.ACTION_STOP
                    path.contains("toggle") -> GkTeVpnApiBridge.ACTION_TOGGLE
                    path.contains("profile") -> GkTeVpnApiBridge.ACTION_NEXT_PROFILE
                    else -> GkTeVpnApiBridge.ACTION_START
                }
            }
            else -> incoming.action ?: GkTeVpnApiBridge.ACTION_TOGGLE
        }

        val modeParam = incoming.getStringExtra(GkTeVpnApiBridge.EXTRA_MODE)
            ?: uri?.getQueryParameter("mode")
        val presetId = incoming.getStringExtra(GkTeVpnApiBridge.EXTRA_PRESET_ID)
            ?: uri?.getQueryParameter("preset")
        val profileId = incoming.getStringExtra(GkTeVpnApiBridge.EXTRA_PROFILE_ID)
            ?: uri?.getQueryParameter("profile")
        val cmdArgs = incoming.getStringExtra(GkTeVpnApiBridge.EXTRA_CMD_ARGS)
            ?: uri?.getQueryParameter("cmd")

        GkTeVpnApiBridge.applyOptionalOverrides(this, modeParam, presetId, profileId, cmdArgs)

        val (currentStatus, _) = appStatus
        when (resolvedAction) {
            GkTeVpnApiBridge.ACTION_STOP -> {
                if (currentStatus == AppStatus.Running) {
                    ServiceManager.stop(this)
                }
                GkTeWidgetUpdater.updateAll(this)
                finishWithStatus(true, "STOPPED")
            }

            GkTeVpnApiBridge.ACTION_NEXT_PROFILE -> {
                val nextName = GkTeVpnApiBridge.cycleNextProfile(this)
                GkTeWidgetUpdater.updateAll(this)
                finishWithStatus(true, "PROFILE_SWITCHED:$nextName")
            }

            GkTeVpnApiBridge.ACTION_TOGGLE -> {
                if (currentStatus == AppStatus.Running) {
                    ServiceManager.stop(this)
                    GkTeWidgetUpdater.updateAll(this)
                    finishWithStatus(true, "STOPPED")
                } else {
                    startConfiguredService()
                }
            }

            GkTeVpnApiBridge.ACTION_START -> {
                if (currentStatus == AppStatus.Running) {
                    ServiceManager.restartIfRunning(this)
                    GkTeWidgetUpdater.updateAll(this)
                    finishWithStatus(true, "ALREADY_RUNNING_RELOADED")
                } else {
                    startConfiguredService()
                }
            }

            else -> finishWithStatus(false, "UNKNOWN_ACTION")
        }
    }

    private fun startConfiguredService() {
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    vpnConsentLauncher.launch(prepareIntent)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                    GkTeWidgetUpdater.updateAll(this)
                    finishWithStatus(true, "VPN_STARTED")
                }
            }
            Mode.Proxy -> {
                ServiceManager.start(this, Mode.Proxy)
                GkTeWidgetUpdater.updateAll(this)
                finishWithStatus(true, "PROXY_STARTED")
            }
        }
    }

    private fun finishWithStatus(ok: Boolean, message: String) {
        val resultData = Intent().apply {
            val ctx = this@ExternalApiActivity
            putExtras(GkTeVpnApiBridge.buildStatusBundle(ctx))
            putExtra("ok", ok)
            putExtra("message", message)
        }
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, resultData)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
