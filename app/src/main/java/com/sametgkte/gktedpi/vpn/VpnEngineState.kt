package com.sametgkte.gktedpi.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sametgkte.gktedpi.utility.getPreferences
import java.util.concurrent.CopyOnWriteArraySet

enum class TunnelEngine(val prefValue: String) {
    LOCAL_DPI("local_dpi"),
    REAL_VPN("real_vpn");

    companion object {
        fun fromPref(value: String?): TunnelEngine =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: LOCAL_DPI
    }
}

data class RealVpnRuntimeSnapshot(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val countryCode: String = "NL",
    val serverLabel: String = "",
    val exitIp: String = "",
    val latencyMs: Long = -1L,
    val protocolBadge: String = "",
    val standbyCount: Int = 0,
    val statusNote: String = "",
    val fatihStealthActive: Boolean = false,
)

object VpnEngineState {
    const val PREF_TUNNEL_ENGINE = "gkte_tunnel_engine"
    const val PREF_SELECTED_COUNTRY = "gkte_vpn_country_code"
    const val PREF_SELECTED_CUSTOM_ID = "gkte_vpn_custom_endpoint_id"
    const val PREF_FATIH_STEALTH = "gkte_vpn_fatih_stealth"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var snapshot: RealVpnRuntimeSnapshot = RealVpnRuntimeSnapshot()
        private set

    fun getEngine(context: Context): TunnelEngine =
        TunnelEngine.fromPref(context.getPreferences().getString(PREF_TUNNEL_ENGINE, TunnelEngine.LOCAL_DPI.prefValue))

    fun setEngine(context: Context, engine: TunnelEngine) {
        context.getPreferences().edit().putString(PREF_TUNNEL_ENGINE, engine.prefValue).apply()
        notifyListeners()
    }

    fun getSelectedCountryCode(context: Context): String =
        context.getPreferences().getString(PREF_SELECTED_COUNTRY, "NL")?.uppercase()?.ifBlank { "NL" } ?: "NL"

    fun setSelectedCountryCode(context: Context, countryCode: String) {
        context.getPreferences().edit()
            .putString(PREF_SELECTED_COUNTRY, countryCode.uppercase())
            .remove(PREF_SELECTED_CUSTOM_ID)
            .apply()
        notifyListeners()
    }

    fun getSelectedCustomEndpointId(context: Context): String? =
        context.getPreferences().getString(PREF_SELECTED_CUSTOM_ID, null)?.takeIf { it.isNotBlank() }

    fun setSelectedCustomEndpointId(context: Context, endpointId: String?) {
        val editor = context.getPreferences().edit()
        if (endpointId.isNullOrBlank()) {
            editor.remove(PREF_SELECTED_CUSTOM_ID)
        } else {
            editor.putString(PREF_SELECTED_CUSTOM_ID, endpointId)
        }
        editor.apply()
        notifyListeners()
    }

    fun isFatihStealthEnabled(context: Context): Boolean =
        context.getPreferences().getBoolean(PREF_FATIH_STEALTH, false)

    fun setFatihStealthEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit()
            .putBoolean(PREF_FATIH_STEALTH, enabled)
            .apply()
        notifyListeners()
    }

    fun updateSnapshot(newSnapshot: RealVpnRuntimeSnapshot) {
        snapshot = newSnapshot
        notifyListeners()
    }

    fun resetSnapshot(countryCode: String = snapshot.countryCode) {
        snapshot = RealVpnRuntimeSnapshot(countryCode = countryCode)
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it.invoke() }
        } else {
            mainHandler.post { listeners.forEach { it.invoke() } }
        }
    }
}
