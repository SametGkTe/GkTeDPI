package com.sametgkte.gktedpi.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode
import com.sametgkte.gktedpi.widget.GkTeWidgetUpdater

/**
 * Synchronous RPC ContentProvider (`content://com.sametgkte.gktedpi.api`)
 * enabling external apps to query VPN status in <1ms or trigger start/stop/toggle.
 */
class GkTeApiProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply { putBoolean("ok", false) }
        if (!GkTeVpnApiBridge.isApiEnabled(ctx)) {
            return Bundle().apply {
                putBoolean("ok", false)
                putString("error", "API_DISABLED")
            }
        }

        val token = extras?.getString(GkTeVpnApiBridge.EXTRA_TOKEN) ?: arg
        if (!GkTeVpnApiBridge.isTokenValid(ctx, token)) {
            return Bundle().apply {
                putBoolean("ok", false)
                putString("error", "INVALID_API_TOKEN")
            }
        }

        when (method.lowercase()) {
            "status" -> return GkTeVpnApiBridge.buildStatusBundle(ctx)

            "presets" -> {
                return Bundle().apply {
                    putBoolean("ok", true)
                    putStringArrayList(
                        "preset_ids",
                        ArrayList(BuiltInPresets.ALL.map { it.id })
                    )
                    putStringArrayList(
                        "preset_names",
                        ArrayList(BuiltInPresets.ALL.map { it.nameTr })
                    )
                }
            }

            "profiles" -> {
                val list = ProfileManager.getProfiles(ctx)
                return Bundle().apply {
                    putBoolean("ok", true)
                    putStringArrayList("profile_ids", ArrayList(list.map { it.id }))
                    putStringArrayList("profile_names", ArrayList(list.map { it.name }))
                }
            }

            "start" -> {
                GkTeVpnApiBridge.applyOptionalOverrides(
                    ctx,
                    extras?.getString(GkTeVpnApiBridge.EXTRA_MODE),
                    extras?.getString(GkTeVpnApiBridge.EXTRA_PRESET_ID),
                    extras?.getString(GkTeVpnApiBridge.EXTRA_PROFILE_ID),
                    extras?.getString(GkTeVpnApiBridge.EXTRA_CMD_ARGS),
                )
                val targetMode = ctx.getPreferences().mode()
                if (targetMode == Mode.VPN && VpnService.prepare(ctx) != null) {
                    GkTeVpnApiBridge.launchGatewayActivityFromBackground(
                        ctx,
                        GkTeVpnApiBridge.ACTION_START,
                        extras
                    )
                    return GkTeVpnApiBridge.buildStatusBundle(ctx).apply {
                        putString("message", "PERMISSION_PROMPT_OPENED")
                    }
                }
                try {
                    ServiceManager.start(ctx, targetMode)
                    GkTeWidgetUpdater.updateAll(ctx)
                } catch (_: Exception) {
                    GkTeVpnApiBridge.launchGatewayActivityFromBackground(
                        ctx,
                        GkTeVpnApiBridge.ACTION_START,
                        extras
                    )
                }
                return GkTeVpnApiBridge.buildStatusBundle(ctx)
            }

            "stop" -> {
                val (status, _) = appStatus
                if (status == AppStatus.Running) {
                    ServiceManager.stop(ctx)
                    GkTeWidgetUpdater.updateAll(ctx)
                }
                return GkTeVpnApiBridge.buildStatusBundle(ctx)
            }

            "toggle" -> {
                val (status, _) = appStatus
                return if (status == AppStatus.Running) {
                    ServiceManager.stop(ctx)
                    GkTeWidgetUpdater.updateAll(ctx)
                    GkTeVpnApiBridge.buildStatusBundle(ctx)
                } else {
                    call("start", arg, extras)
                }
            }

            "next_profile" -> {
                val nextName = GkTeVpnApiBridge.cycleNextProfile(ctx)
                GkTeWidgetUpdater.updateAll(ctx)
                return GkTeVpnApiBridge.buildStatusBundle(ctx).apply {
                    putString("switched_to", nextName)
                }
            }

            else -> return Bundle().apply {
                putBoolean("ok", false)
                putString("error", "UNKNOWN_METHOD:$method")
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (!GkTeVpnApiBridge.isApiEnabled(ctx)) return null
        val b = GkTeVpnApiBridge.buildStatusBundle(ctx)
        val cursor = MatrixCursor(
            arrayOf("running", "mode", "active_profile_name", "proxy_ip", "proxy_port", "dns_ip")
        )
        cursor.addRow(
            arrayOf<Any?>(
                if (b.getBoolean("running")) 1 else 0,
                b.getString("mode"),
                b.getString("active_profile_name"),
                b.getString("proxy_ip"),
                b.getInt("proxy_port"),
                b.getString("dns_ip"),
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.sametgkte.gktedpi.status"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
