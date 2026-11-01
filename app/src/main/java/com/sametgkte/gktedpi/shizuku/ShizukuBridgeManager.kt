package com.sametgkte.gktedpi.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.utility.getPreferences
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shizuku Köprü Yöneticisi (`ShizukuBridgeManager`)
 *
 * 1) VPN İkonsuz / Çift VPN Modu (Shizuku ADB UID 2000 veya Root UID 0):
 *    - Android'in tekli `VpnService` slotunu kullanmadan `ByeDpiProxyService` +
 *      `LocalHttpToSocksBridge (127.0.0.1:8118)` başlatır ve Shizuku üzerinden
 *      sistem global HTTP proxy ayarını (`settings put global http_proxy 127.0.0.1:8118`)
 *      yazar. Böylece WireGuard / ProtonVPN / Kurumsal VPN açıkken bile çakışmasız
 *      çift katmanlı DPI aşma çalışır!
 *
 * 2) Hotspot / LAN Paylaşım Modu:
 *    - `0.0.0.0:1080` (SOCKS5) ve `0.0.0.0:8118` (HTTP CONNECT) portlarını yerel ağa açar;
 *      Hotspot/Wi-Fi IP adresini otomatik bulur ki PC, PS5, Xbox, iOS ve TV cihazları
 *      doğrudan telefon üzerinden DPI engelsiz internete çıkabilsin.
 */
object ShizukuBridgeManager {

    private const val TAG = "ShizukuBridgeManager"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    const val PREF_HTTP_BRIDGE_ENABLED = "shizuku_http_bridge_enabled"
    const val PREF_HTTP_BRIDGE_PORT = "shizuku_http_bridge_port"
    const val PREF_SYSTEM_PROXY_ACTIVE = "shizuku_system_proxy_active"
    const val PREF_HOTSPOT_SHARE_ACTIVE = "shizuku_hotspot_share_active"

    const val DEFAULT_HTTP_BRIDGE_PORT = 8118

    data class ShizukuState(
        val installed: Boolean,
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val uid: Int,
        val systemProxyActive: Boolean,
        val hotspotShareActive: Boolean,
        val lanIpAddresses: List<String>,
    ) {
        val isRootShizuku: Boolean get() = binderAlive && uid == 0
    }

    fun getState(context: Context): ShizukuState {
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }

        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        val granted = if (alive) {
            try {
                !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else false

        val uid = if (alive && granted) {
            try { Shizuku.getUid() } catch (_: Throwable) { -1 }
        } else -1

        val prefs = context.getPreferences()
        return ShizukuState(
            installed = installed,
            binderAlive = alive,
            permissionGranted = granted,
            uid = uid,
            systemProxyActive = prefs.getBoolean(PREF_SYSTEM_PROXY_ACTIVE, false),
            hotspotShareActive = prefs.getBoolean(PREF_HOTSPOT_SHARE_ACTIVE, false),
            lanIpAddresses = detectLocalIpv4Addresses(),
        )
    }

    fun requestPermission(requestCode: Int = 4201): Boolean = try {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            Shizuku.requestPermission(requestCode)
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Shizuku ile "VPN İkonsuz / Çift VPN" sistem proxy köprüsünü başlatır.
     */
    fun enableDualVpnSystemProxy(context: Context): Result<String> {
        val state = getState(context)
        if (!state.binderAlive) {
            return Result.failure(IllegalStateException("Shizuku servisi çalışmıyor"))
        }
        if (!state.permissionGranted) {
            return Result.failure(SecurityException("Shizuku izni verilmedi"))
        }

        val prefs = context.getPreferences()
        val bridgePort = prefs.getString(PREF_HTTP_BRIDGE_PORT, null)?.toIntOrNull()
            ?: DEFAULT_HTTP_BRIDGE_PORT

        prefs.edit()
            .putString("gktedpi_mode", "proxy")
            .putBoolean(PREF_HTTP_BRIDGE_ENABLED, true)
            .putBoolean(PREF_SYSTEM_PROXY_ACTIVE, true)
            .apply()

        val cmd = "settings put global http_proxy 127.0.0.1:$bridgePort && " +
            "settings put global global_http_proxy_host 127.0.0.1 && " +
            "settings put global global_http_proxy_port $bridgePort && " +
            "settings put global global_http_proxy_exclusion_list \"localhost,127.0.0.1,10.*,192.168.*\""

        val exec = runShizukuShell(cmd)
        if (exec.isFailure) {
            prefs.edit().putBoolean(PREF_SYSTEM_PROXY_ACTIVE, false).apply()
            return exec
        }

        ServiceManager.start(context, Mode.Proxy)
        return Result.success("127.0.0.1:$bridgePort")
    }

    /**
     * Sistem proxy ayarını temizleyip normal ağ davranışına döner.
     */
    fun disableDualVpnSystemProxy(context: Context, alsoStopProxyService: Boolean = true): Result<String> {
        val prefs = context.getPreferences()
        prefs.edit()
            .putBoolean(PREF_SYSTEM_PROXY_ACTIVE, false)
            .apply()

        val resetCmd = "settings put global http_proxy :0 ; " +
            "settings delete global http_proxy ; " +
            "settings delete global global_http_proxy_host ; " +
            "settings delete global global_http_proxy_port ; " +
            "settings delete global global_http_proxy_exclusion_list"

        val res = if (getState(context).let { it.binderAlive && it.permissionGranted }) {
            runShizukuShell(resetCmd)
        } else {
            Result.success("skipped")
        }

        if (alsoStopProxyService) {
            ServiceManager.stop(context)
        }
        return res
    }

    /**
     * Hotspot / Yerel Ağ (LAN) paylaşım köprüsünü açar veya kapatır (`0.0.0.0:1080` SOCKS5 + `0.0.0.0:8118` HTTP).
     */
    fun setHotspotLanShareEnabled(context: Context, enable: Boolean): String {
        val prefs = context.getPreferences()
        if (enable) {
            prefs.edit()
                .putString("byedpi_proxy_ip", "0.0.0.0")
                .putBoolean(PREF_HTTP_BRIDGE_ENABLED, true)
                .putBoolean(PREF_HOTSPOT_SHARE_ACTIVE, true)
                .apply()
            ServiceManager.restartIfRunning(context)
            val ip = detectLocalIpv4Addresses().firstOrNull() ?: "192.168.43.1"
            val socksPort = prefs.getString("byedpi_proxy_port", "1080") ?: "1080"
            return "HTTP: $ip:$DEFAULT_HTTP_BRIDGE_PORT  •  SOCKS5: $ip:$socksPort"
        } else {
            prefs.edit()
                .putString("byedpi_proxy_ip", "127.0.0.1")
                .putBoolean(PREF_HOTSPOT_SHARE_ACTIVE, false)
                .apply()
            ServiceManager.restartIfRunning(context)
            return "127.0.0.1"
        }
    }

    /** Shizuku ayrıcalıklı kabuğu (`sh`) üzerinden komut çalıştırır. */
    fun runShizukuShell(command: String): Result<String> = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val proc = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null,
        ) as Process

        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code == 0) {
            Result.success(stdout.trim())
        } else {
            Log.w(TAG, "Shizuku cmd exit=$code stderr=$stderr")
            Result.failure(RuntimeException(stderr.ifBlank { "Exit code $code" }))
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Shizuku shell execution failed", t)
        Result.failure(t)
    }

    fun detectLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) result.add(ip)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.distinct()
    }
}
