package com.sametgkte.gktedpi.api

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.ui.PersonalizationManager
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode

/**
 * Official GkTeDPI Inter-App VPN & DPI Gateway Contract & Helper.
 * Allows authorized ecosystem apps (e.g. GkteTok, NectarLink, automation tools)
 * to query, start, stop, toggle, and switch presets/profiles seamlessly.
 */
object GkTeVpnApiBridge {

    const val AUTHORITY = "com.sametgkte.gktedpi.api"
    const val PERMISSION_CONTROL = "com.sametgkte.gktedpi.permission.CONTROL_VPN"

    // Activity Gateway Actions (recommended: handles VpnService.prepare() + Android 14/15 FGS seamlessly)
    const val ACTION_START = "com.sametgkte.gktedpi.api.START"
    const val ACTION_STOP = "com.sametgkte.gktedpi.api.STOP"
    const val ACTION_TOGGLE = "com.sametgkte.gktedpi.api.TOGGLE"
    const val ACTION_NEXT_PROFILE = "com.sametgkte.gktedpi.api.NEXT_PROFILE"

    // Broadcast Actions
    const val ACTION_BROADCAST_START = "com.sametgkte.gktedpi.api.BROADCAST_START"
    const val ACTION_BROADCAST_STOP = "com.sametgkte.gktedpi.api.BROADCAST_STOP"
    const val ACTION_BROADCAST_TOGGLE = "com.sametgkte.gktedpi.api.BROADCAST_TOGGLE"
    const val ACTION_BROADCAST_STATUS = "com.sametgkte.gktedpi.api.BROADCAST_STATUS"

    // Outgoing Live State Event Broadcast
    const val EVENT_STATE_CHANGED = "com.sametgkte.gktedpi.api.EVENT_STATE_CHANGED"

    // Optional Extras / Query Parameters
    const val EXTRA_TOKEN = "api_token"
    const val EXTRA_MODE = "mode"             // "vpn" or "proxy"
    const val EXTRA_PRESET_ID = "preset_id"   // e.g. "tr_universal", "tr_superonline_v1"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_CMD_ARGS = "cmd_args"
    const val EXTRA_SILENT = "silent"

    fun isApiEnabled(context: Context): Boolean =
        context.getPreferences().getBoolean("external_api_enabled", true)

    fun isTokenValid(context: Context, suppliedToken: String?): Boolean {
        val prefs = context.getPreferences()
        if (!prefs.getBoolean("external_api_require_token", false)) {
            return true
        }
        val expected = PersonalizationManager.getOrCreateApiToken(context)
        return !suppliedToken.isNullOrBlank() && suppliedToken == expected
    }

    fun applyOptionalOverrides(
        context: Context,
        modeParam: String?,
        presetId: String?,
        profileId: String?,
        cmdArgs: String?,
    ) {
        val prefs = context.getPreferences()
        if (!modeParam.isNullOrBlank()) {
            val normalized = if (modeParam.equals("proxy", ignoreCase = true)) "proxy" else "vpn"
            prefs.edit().putString("byedpi_mode", normalized).apply()
        }
        if (!profileId.isNullOrBlank()) {
            ProfileManager.activateProfile(context, profileId)
        }
        if (!presetId.isNullOrBlank()) {
            BuiltInPresets.findById(presetId)?.let {
                ProfileManager.applyPresetToActive(context, it)
            }
        }
        if (!cmdArgs.isNullOrBlank()) {
            ProfileManager.applyCustomCommandToActive(context, cmdArgs)
        }
    }

    fun cycleNextProfile(context: Context): String {
        val profiles = ProfileManager.getProfiles(context)
        if (profiles.isEmpty()) return ""
        val active = ProfileManager.getActiveProfile(context)
        val idx = profiles.indexOfFirst { it.id == active.id }
        val next = profiles[(idx + 1) % profiles.size]
        ProfileManager.activateProfile(context, next.id)
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            ServiceManager.restartIfRunning(context)
        }
        return next.name
    }

    fun buildStatusBundle(context: Context): Bundle {
        val (status, activeMode) = appStatus
        val prefs = context.getPreferences()
        val configuredMode = prefs.mode()
        val activeProfile = ProfileManager.getActiveProfile(context)
        val vpnConsentGranted = VpnService.prepare(context) == null
        val ip = prefs.getString("byedpi_proxy_ip", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getString("byedpi_proxy_port", "1080")?.toIntOrNull() ?: 1080
        val dns = prefs.getString("dns_ip", "1.1.1.1") ?: "1.1.1.1"

        return Bundle().apply {
            putBoolean("ok", true)
            putBoolean("running", status == AppStatus.Running)
            putString("status", if (status == AppStatus.Running) "RUNNING" else "STOPPED")
            putString(
                "mode",
                (if (status == AppStatus.Running) activeMode else configuredMode).name.lowercase()
            )
            putString("active_profile_id", activeProfile.id)
            putString("active_profile_name", activeProfile.name)
            putBoolean("vpn_consent_granted", vpnConsentGranted)
            putString("proxy_ip", ip)
            putInt("proxy_port", port)
            putString("dns_ip", dns)
        }
    }

    fun broadcastStateChange(context: Context) {
        if (!isApiEnabled(context)) return
        val statusBundle = buildStatusBundle(context)
        val eventIntent = Intent(EVENT_STATE_CHANGED).apply {
            putExtras(statusBundle)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        context.sendBroadcast(eventIntent)
    }

    fun launchGatewayActivityFromBackground(
        context: Context,
        action: String,
        extras: Bundle? = null,
    ) {
        val intent = Intent(context, ExternalApiActivity::class.java).apply {
            this.action = action
            putExtra("internal_trusted", true)
            if (extras != null) putExtras(extras)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        context.startActivity(intent)
    }

    fun getDeveloperIntegrationSnippet(context: Context): String {
        val prefs = context.getPreferences()
        val requireToken = prefs.getBoolean("external_api_require_token", false)
        val tokenLine = if (requireToken) {
            "\n    putExtra(\"api_token\", \"${PersonalizationManager.getOrCreateApiToken(context)}\")"
        } else {
            ""
        }
        return """
// 1) Başka Bir Android Uygulamasından (örn. GkteTok / NectarLink) VPN Başlatma:
val intent = Intent("com.sametgkte.gktedpi.api.START").apply {
    setPackage("com.sametgkte.gktedpi")
    putExtra("mode", "vpn") // veya "proxy"
    putExtra("preset_id", "tr_universal") // opsiyonel$tokenLine
}
context.startActivity(intent)

// 2) VPN Kapatma veya Aç/Kapat (Toggle):
context.startActivity(
    Intent("com.sametgkte.gktedpi.api.STOP").setPackage("com.sametgkte.gktedpi")
)

// 3) Deep Link ile Tek Satırda Açma:
// gktedpi://vpn/start?mode=vpn&preset=tr_universal

// 4) Anlık Durum Sorgulama (ContentProvider - 1ms Senkron):
val bundle = context.contentResolver.call(
    Uri.parse("content://com.sametgkte.gktedpi.api"),
    "status", null, null
)
val isRunning = bundle?.getBoolean("running") == true

// 5) ADB ile Test:
// adb shell am start -a com.sametgkte.gktedpi.api.START -n com.sametgkte.gktedpi/.api.ExternalApiActivity
        """.trimIndent()
    }
}
