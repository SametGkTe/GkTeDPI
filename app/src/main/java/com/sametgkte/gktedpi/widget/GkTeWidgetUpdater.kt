package com.sametgkte.gktedpi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.activities.MainActivity
import com.sametgkte.gktedpi.api.ExternalApiActivity
import com.sametgkte.gktedpi.api.GkTeVpnApiBridge
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode
import com.sametgkte.gktedpi.vpn.TunnelEngine
import com.sametgkte.gktedpi.vpn.VpnCountries
import com.sametgkte.gktedpi.vpn.VpnEngineState
import java.util.Locale

object GkTeWidgetUpdater {

    private fun activeLabel(context: Context): String {
        val isTr = Locale.getDefault().language == "tr"
        return if (VpnEngineState.getEngine(context) == TunnelEngine.REAL_VPN) {
            val code = if (VpnEngineState.snapshot.connected) {
                VpnEngineState.snapshot.countryCode
            } else {
                VpnEngineState.getSelectedCountryCode(context)
            }
            val c = VpnCountries.byCode(code)
            "${c.flag} ${c.localizedName(isTr)}"
        } else {
            ProfileManager.getActiveProfile(context).name
        }
    }

    private fun immutableFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, 700, intent, immutableFlags())
    }

    private fun apiActionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(context, ExternalApiActivity::class.java).apply {
            this.action = action
            putExtra("internal_trusted", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        return PendingIntent.getActivity(context, reqCode, intent, immutableFlags())
    }

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val compactIds = mgr.getAppWidgetIds(ComponentName(context, GkTeCompactWidgetProvider::class.java))
        if (compactIds.isNotEmpty()) {
            updateCompactWidgets(context, mgr, compactIds)
        }
        val controlIds = mgr.getAppWidgetIds(ComponentName(context, GkTeControlWidgetProvider::class.java))
        if (controlIds.isNotEmpty()) {
            updateControlWidgets(context, mgr, controlIds)
        }
    }

    fun updateCompactWidgets(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val (status, activeMode) = appStatus
        val prefs = context.getPreferences()
        val mode = if (status == AppStatus.Running) activeMode else prefs.mode()
        val label = activeLabel(context)
        val running = status == AppStatus.Running

        val btnLabel = when {
            running && mode == Mode.VPN -> context.getString(R.string.vpn_disconnect)
            running -> context.getString(R.string.proxy_stop)
            mode == Mode.VPN -> context.getString(R.string.vpn_connect)
            else -> context.getString(R.string.proxy_start)
        }

        for (widgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_gkte_compact)
            rv.setTextViewText(R.id.widgetCompactProfile, label)
            rv.setTextViewText(R.id.widgetCompactToggleBtn, btnLabel)
            rv.setInt(
                R.id.widgetCompactToggleBtn,
                "setBackgroundResource",
                if (running) R.drawable.bg_widget_btn_idle else R.drawable.bg_widget_btn_active
            )
            rv.setOnClickPendingIntent(R.id.widgetCompactRoot, openAppPendingIntent(context))
            rv.setOnClickPendingIntent(
                R.id.widgetCompactToggleBtn,
                apiActionPendingIntent(context, GkTeVpnApiBridge.ACTION_TOGGLE, 701)
            )
            mgr.updateAppWidget(widgetId, rv)
        }
    }

    fun updateControlWidgets(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val (status, activeMode) = appStatus
        val prefs = context.getPreferences()
        val mode = if (status == AppStatus.Running) activeMode else prefs.mode()
        val label = activeLabel(context)
        val dns = prefs.getString("dns_ip", "1.1.1.1")?.ifBlank { "1.1.1.1" } ?: "1.1.1.1"
        val running = status == AppStatus.Running

        val stateText = if (running) {
            context.getString(R.string.vpn_connected)
        } else {
            context.getString(R.string.vpn_disconnected)
        }
        val stateColor = if (running) Color.parseColor("#4ADE80") else Color.parseColor("#E2E8F0")
        val modeLabel = when {
            mode != Mode.VPN -> "SOCKS5"
            VpnEngineState.getEngine(context) == TunnelEngine.REAL_VPN -> "VPN"
            else -> "DPI"
        }
        val summary = "$modeLabel • $label • DNS $dns"

        val toggleText = when {
            running && mode == Mode.VPN -> context.getString(R.string.vpn_disconnect)
            running -> context.getString(R.string.proxy_stop)
            mode == Mode.VPN -> context.getString(R.string.vpn_connect)
            else -> context.getString(R.string.proxy_start)
        }

        for (widgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_gkte_control)
            rv.setTextViewText(R.id.widgetControlState, stateText)
            rv.setTextColor(R.id.widgetControlState, stateColor)
            rv.setTextViewText(R.id.widgetControlSummary, summary)
            rv.setTextViewText(R.id.widgetControlToggleBtn, toggleText)
            rv.setInt(
                R.id.widgetControlToggleBtn,
                "setBackgroundResource",
                if (running) R.drawable.bg_widget_btn_idle else R.drawable.bg_widget_btn_active
            )

            rv.setOnClickPendingIntent(R.id.widgetControlRoot, openAppPendingIntent(context))
            rv.setOnClickPendingIntent(
                R.id.widgetNextProfileBtn,
                apiActionPendingIntent(context, GkTeVpnApiBridge.ACTION_NEXT_PROFILE, 702)
            )
            rv.setOnClickPendingIntent(
                R.id.widgetControlToggleBtn,
                apiActionPendingIntent(context, GkTeVpnApiBridge.ACTION_TOGGLE, 703)
            )
            mgr.updateAppWidget(widgetId, rv)
        }
    }
}
