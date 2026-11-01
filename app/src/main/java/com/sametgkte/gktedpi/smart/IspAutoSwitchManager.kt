package com.sametgkte.gktedpi.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.widget.GkTeWidgetUpdater

/**
 * Ağ / Operatör Bazlı Otomatik Profil Motoru (Auto-ISP Smart Switch).
 *
 * Fiziksel ağı (`NET_CAPABILITY_NOT_VPN`) canlı izler; Wi-Fi <-> Mobil Veri (4.5G/5G)
 * geçişlerinde MCC/MNC operatör kodunu (`28601` Turkcell, `28602` Vodafone, `28603` Türk Telekom)
 * ve LinkProperties bilgisini hiçbir konum/telefon izni istemeden tespit edip
 * ilgili İSS stratejisini otomatik uygular.
 */
object IspAutoSwitchManager {

    private const val TAG = "IspAutoSwitchManager"

    const val PREF_ENABLED = "smart_isp_auto_enabled"
    const val PREF_AUTO_START_WIFI = "smart_isp_auto_start_wifi"
    const val PREF_AUTO_START_MOBILE = "smart_isp_auto_start_mobile"

    enum class NetworkSlot(
        val key: String,
        val defaultTarget: String,
    ) {
        WIFI_FIBER("smart_slot_wifi", "preset:tr_universal"),
        TURK_TELEKOM_MOBILE("smart_slot_tt_mobile", "preset:tr_mobile_45g"),
        TURKCELL_MOBILE("smart_slot_turkcell_mobile", "preset:tr_mobile_45g"),
        VODAFONE_MOBILE("smart_slot_vodafone_mobile", "preset:tr_mobile_45g"),
        OTHER_MOBILE("smart_slot_other_mobile", "preset:tr_universal"),
    }

    data class DetectedNetworkInfo(
        val connected: Boolean,
        val isWifiOrEthernet: Boolean,
        val slot: NetworkSlot,
        val displayLabelTr: String,
        val displayLabelEn: String,
        val operatorCode: String,
    )

    @Volatile
    private var lastAppliedSignature: String = ""

    @Volatile
    private var callbackRegistered = false

    fun isEnabled(context: Context): Boolean =
        context.getPreferences().getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (enabled) {
            lastAppliedSignature = ""
            evaluateAndApplyNow(context, allowAutoStart = false)
        }
    }

    fun getSlotTarget(context: Context, slot: NetworkSlot): String =
        context.getPreferences().getString(slot.key, slot.defaultTarget) ?: slot.defaultTarget

    fun setSlotTarget(context: Context, slot: NetworkSlot, targetSpec: String) {
        context.getPreferences().edit().putString(slot.key, targetSpec).apply()
    }

    fun formatTargetLabel(context: Context, targetSpec: String, isTr: Boolean): String {
        if (targetSpec == "none") return if (isTr) "Değiştirme" else "Keep Current"
        if (targetSpec.startsWith("preset:")) {
            val id = targetSpec.removePrefix("preset:")
            val p = BuiltInPresets.findById(id)
            if (p != null) return p.localizedName(isTr)
        }
        if (targetSpec.startsWith("profile:")) {
            val id = targetSpec.removePrefix("profile:")
            val prof = ProfileManager.getProfiles(context).firstOrNull { it.id == id }
            if (prof != null) return prof.name
        }
        return targetSpec
    }

    /** Mevcut fiziksel ağı (`NET_CAPABILITY_NOT_VPN`) tespit eder. */
    fun detectCurrentNetwork(context: Context): DetectedNetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DetectedNetworkInfo(false, false, NetworkSlot.OTHER_MOBILE, "Çevrimdışı", "Offline", "")

        val physicalNet = findUnderlyingPhysicalNetwork(cm)
        val caps = physicalNet?.let { cm.getNetworkCapabilities(it) }
            ?: return DetectedNetworkInfo(false, false, NetworkSlot.OTHER_MOBILE, "Çevrimdışı", "Offline", "")

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (isWifi) {
            val link = cm.getLinkProperties(physicalNet)
            val domainHint = link?.domains?.lowercase().orEmpty()
            val subLabelTr = when {
                "superonline" in domainHint -> "Wi-Fi • Superonline"
                "ttnet" in domainHint || "turktelekom" in domainHint -> "Wi-Fi • Türk Telekom"
                "turk.net" in domainHint || "turknet" in domainHint -> "Wi-Fi • TürkNet"
                "kablonet" in domainHint || "turksat" in domainHint -> "Wi-Fi • Kablonet"
                else -> "Wi-Fi / Fiber Ağ"
            }
            val subLabelEn = when {
                "superonline" in domainHint -> "Wi-Fi • Superonline"
                "ttnet" in domainHint || "turktelekom" in domainHint -> "Wi-Fi • Turk Telekom"
                "turk.net" in domainHint || "turknet" in domainHint -> "Wi-Fi • TurkNet"
                "kablonet" in domainHint || "turksat" in domainHint -> "Wi-Fi • Kablonet"
                else -> "Wi-Fi / Fiber Network"
            }
            return DetectedNetworkInfo(
                connected = true,
                isWifiOrEthernet = true,
                slot = NetworkSlot.WIFI_FIBER,
                displayLabelTr = subLabelTr,
                displayLabelEn = subLabelEn,
                operatorCode = "WIFI:$domainHint",
            )
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val mccmnc = tm?.networkOperator?.trim().orEmpty()
        val opName = tm?.networkOperatorName?.trim().orEmpty()
        val combined = "$mccmnc ${opName.lowercase()}"

        return when {
            mccmnc == "28603" || mccmnc == "28604" || "telekom" in combined || "avea" in combined ->
                DetectedNetworkInfo(
                    connected = true,
                    isWifiOrEthernet = false,
                    slot = NetworkSlot.TURK_TELEKOM_MOBILE,
                    displayLabelTr = "Mobil Veri • Türk Telekom",
                    displayLabelEn = "Mobile Data • Turk Telekom",
                    operatorCode = mccmnc.ifEmpty { "TT" },
                )

            mccmnc == "28601" || "turkcell" in combined ->
                DetectedNetworkInfo(
                    connected = true,
                    isWifiOrEthernet = false,
                    slot = NetworkSlot.TURKCELL_MOBILE,
                    displayLabelTr = "Mobil Veri • Turkcell",
                    displayLabelEn = "Mobile Data • Turkcell",
                    operatorCode = mccmnc.ifEmpty { "TCELL" },
                )

            mccmnc == "28602" || "vodafone" in combined ->
                DetectedNetworkInfo(
                    connected = true,
                    isWifiOrEthernet = false,
                    slot = NetworkSlot.VODAFONE_MOBILE,
                    displayLabelTr = "Mobil Veri • Vodafone TR",
                    displayLabelEn = "Mobile Data • Vodafone TR",
                    operatorCode = mccmnc.ifEmpty { "VF" },
                )

            else -> {
                val label = if (opName.isNotBlank()) "Mobil • $opName" else "Mobil Veri"
                DetectedNetworkInfo(
                    connected = true,
                    isWifiOrEthernet = false,
                    slot = NetworkSlot.OTHER_MOBILE,
                    displayLabelTr = label,
                    displayLabelEn = if (opName.isNotBlank()) "Mobile • $opName" else "Mobile Data",
                    operatorCode = mccmnc.ifEmpty { "CELL" },
                )
            }
        }
    }

    private fun findUnderlyingPhysicalNetwork(cm: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        val all = cm.allNetworks
        var fallback: Network? = null
        for (net in all) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (notVpn && hasInternet) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    return net
                }
                if (fallback == null) fallback = net
            }
        }
        return fallback ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork else null
    }

    /** Uygulama başlangıcında arka plan fiziksel ağ dinleyicisini kaydeder. */
    fun registerMonitor(context: Context) {
        if (callbackRegistered) return
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(
                req,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        evaluateAndApplyNow(appCtx, allowAutoStart = true)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        evaluateAndApplyNow(appCtx, allowAutoStart = false)
                    }
                }
            )
            callbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "NetworkCallback registration warning", e)
        }
    }

    fun evaluateAndApplyNow(context: Context, allowAutoStart: Boolean): Boolean {
        if (!isEnabled(context)) return false
        val info = detectCurrentNetwork(context)
        if (!info.connected) return false

        val targetSpec = getSlotTarget(context, info.slot)
        val signature = "${info.slot.name}|${info.operatorCode}|$targetSpec"
        if (signature == lastAppliedSignature) return false
        lastAppliedSignature = signature

        val changed = applyTargetSpec(context, targetSpec)
        if (changed) {
            Log.i(TAG, "Auto-ISP switched profile for ${info.displayLabelTr} -> $targetSpec")
            GkTeWidgetUpdater.updateAll(context)
            if (appStatus.first == AppStatus.Running) {
                ServiceManager.restartIfRunning(context)
            } else if (allowAutoStart) {
                val prefs = context.getPreferences()
                val shouldStart = if (info.isWifiOrEthernet) {
                    prefs.getBoolean(PREF_AUTO_START_WIFI, false)
                } else {
                    prefs.getBoolean(PREF_AUTO_START_MOBILE, false)
                }
                if (shouldStart && android.net.VpnService.prepare(context) == null) {
                    ServiceManager.start(context, com.sametgkte.gktedpi.data.Mode.VPN)
                }
            }
        }
        return changed
    }

    private fun applyTargetSpec(context: Context, spec: String): Boolean {
        if (spec == "none" || spec.isBlank()) return false
        return when {
            spec.startsWith("preset:") -> {
                val id = spec.removePrefix("preset:")
                val preset = BuiltInPresets.findById(id) ?: return false
                ProfileManager.applyPresetToActive(context, preset)
                true
            }
            spec.startsWith("profile:") -> {
                val id = spec.removePrefix("profile:")
                ProfileManager.activateProfile(context, id) != null
            }
            else -> false
        }
    }
}
