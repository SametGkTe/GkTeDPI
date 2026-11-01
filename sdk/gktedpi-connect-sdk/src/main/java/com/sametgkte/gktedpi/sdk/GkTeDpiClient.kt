package com.sametgkte.gktedpi.sdk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * GkTeConnect Mini SDK (`com.sametgkte.gktedpi.sdk.GkTeDpiClient`)
 *
 * GkteTok, NectarLink, LM Playground veya herhangi bir Android uygulamasından
 * tek satır çağrıyla GkTeDPI VPN/Proxy tünelini kontrol etmeyi, durumunu okumayı,
 * OkHttp/Socket için yerel SOCKS5 proxy nesnesi üretmeyi ve durum değişimlerini
 * canlı dinlemeyi sağlar.
 */
object GkTeDpiClient {

    const val GKTEDPI_PACKAGE = "com.sametgkte.gktedpi"
    const val AUTHORITY = "com.sametgkte.gktedpi.api"
    val PROVIDER_URI: Uri = Uri.parse("content://$AUTHORITY")

    const val ACTION_START = "com.sametgkte.gktedpi.api.START"
    const val ACTION_STOP = "com.sametgkte.gktedpi.api.STOP"
    const val ACTION_TOGGLE = "com.sametgkte.gktedpi.api.TOGGLE"
    const val ACTION_NEXT_PROFILE = "com.sametgkte.gktedpi.api.NEXT_PROFILE"
    const val ACTION_STATE_CHANGED = "com.sametgkte.gktedpi.api.STATE_CHANGED"

    data class TunnelStatus(
        val installed: Boolean,
        val apiEnabled: Boolean,
        val running: Boolean,
        val status: String,
        val mode: String,
        val activeProfileId: String,
        val activeProfileName: String,
        val proxyHost: String,
        val proxyPort: Int,
        val error: String? = null,
    )

    enum class EnsureOutcome {
        ALREADY_CONNECTED,
        STARTED_IN_BACKGROUND,
        LAUNCHED_CONSENT_BRIDGE,
        NOT_INSTALLED,
        API_DISABLED_OR_UNAUTHORIZED,
        FAILED,
    }

    data class ProfileSummary(
        val id: String,
        val name: String,
    )

    /** Cihazda GkTeDPI kurulu mu kontrol eder. */
    fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                GKTEDPI_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(GKTEDPI_PACKAGE, 0)
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Ekranda hiçbir pencere açmadan anlık tünel durumunu sorgular. */
    fun getStatus(context: Context): TunnelStatus {
        if (!isInstalled(context)) {
            return TunnelStatus(
                installed = false,
                apiEnabled = false,
                running = false,
                status = "not_installed",
                mode = "vpn",
                activeProfileId = "",
                activeProfileName = "",
                proxyHost = "127.0.0.1",
                proxyPort = 1080,
                error = "not_installed",
            )
        }
        return try {
            val res = context.contentResolver.call(PROVIDER_URI, "get_status", null, null)
            if (res == null) {
                TunnelStatus(
                    installed = true,
                    apiEnabled = false,
                    running = false,
                    status = "unavailable",
                    mode = "vpn",
                    activeProfileId = "",
                    activeProfileName = "",
                    proxyHost = "127.0.0.1",
                    proxyPort = 1080,
                    error = "null_provider_response",
                )
            } else {
                val ok = res.getBoolean("ok", true)
                val err = res.getString("error")
                TunnelStatus(
                    installed = true,
                    apiEnabled = ok || err != "api_disabled",
                    running = res.getBoolean("running", false),
                    status = res.getString("status") ?: "halted",
                    mode = res.getString("mode") ?: "vpn",
                    activeProfileId = res.getString("active_profile_id") ?: "",
                    activeProfileName = res.getString("active_profile_name") ?: "",
                    proxyHost = res.getString("proxy_ip") ?: "127.0.0.1",
                    proxyPort = res.getInt("proxy_port", 1080),
                    error = err,
                )
            }
        } catch (t: Throwable) {
            TunnelStatus(
                installed = true,
                apiEnabled = false,
                running = false,
                status = "error",
                mode = "vpn",
                activeProfileId = "",
                activeProfileName = "",
                proxyHost = "127.0.0.1",
                proxyPort = 1080,
                error = t.message,
            )
        }
    }

    /**
     * Tek çağrıyla GkTeDPI bağlantısını hazır hale getirir:
     * 1) Zaten bağlıysa hiçbir şey yapmadan `ALREADY_CONNECTED` döner.
     * 2) VPN izni daha önce onaylanmışsa arka planda sessizce başlatır (`STARTED_IN_BACKGROUND`).
     * 3) İlk kez VPN izni gerekiyorsa görünmez şeffaf köprüyü (`ExternalApiActivity`) açar (`LAUNCHED_CONSENT_BRIDGE`).
     */
    fun ensureConnected(
        context: Context,
        mode: String = "vpn",
        profileId: String? = null,
        cmdArgs: String? = null,
        token: String? = null,
    ): EnsureOutcome {
        if (!isInstalled(context)) return EnsureOutcome.NOT_INSTALLED

        val current = getStatus(context)
        if (current.running && profileId.isNullOrBlank() && cmdArgs.isNullOrBlank()) {
            return EnsureOutcome.ALREADY_CONNECTED
        }

        val extras = Bundle().apply {
            putString("mode", mode)
            if (!profileId.isNullOrBlank()) putString("profile_id", profileId)
            if (!cmdArgs.isNullOrBlank()) putString("cmd_args", cmdArgs)
            if (!token.isNullOrBlank()) putString("token", token)
        }

        try {
            val res = context.contentResolver.call(PROVIDER_URI, "start", null, extras)
            if (res != null) {
                if (res.getBoolean("ok", false)) {
                    return EnsureOutcome.STARTED_IN_BACKGROUND
                }
                val err = res.getString("error")
                if (err == "api_disabled" || err == "invalid_token") {
                    return EnsureOutcome.API_DISABLED_OR_UNAUTHORIZED
                }
                if (res.getBoolean("needs_vpn_consent", false) || err == "vpn_consent_required") {
                    launchBridgeIntent(context, ACTION_START, mode, profileId, cmdArgs, token)
                    return EnsureOutcome.LAUNCHED_CONSENT_BRIDGE
                }
            }
        } catch (_: Throwable) {
            // ContentProvider kısıtlandıysa şeffaf Activity köprüsüne düş
        }

        return try {
            launchBridgeIntent(context, ACTION_START, mode, profileId, cmdArgs, token)
            EnsureOutcome.LAUNCHED_CONSENT_BRIDGE
        } catch (_: Throwable) {
            EnsureOutcome.FAILED
        }
    }

    /** GkTeDPI VPN veya Proxy bağlantısını durdurur. */
    fun disconnect(context: Context, token: String? = null): Boolean {
        if (!isInstalled(context)) return false
        val extras = Bundle().apply {
            if (!token.isNullOrBlank()) putString("token", token)
        }
        return try {
            val res = context.contentResolver.call(PROVIDER_URI, "stop", null, extras)
            if (res?.getBoolean("ok", false) == true) {
                true
            } else {
                launchBridgeIntent(context, ACTION_STOP, null, null, null, token)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Bağlantıyı aç/kapat (Toggle). */
    fun toggle(context: Context, mode: String = "vpn", token: String? = null): EnsureOutcome {
        val st = getStatus(context)
        return if (st.running) {
            if (disconnect(context, token)) EnsureOutcome.STARTED_IN_BACKGROUND else EnsureOutcome.FAILED
        } else {
            ensureConnected(context, mode = mode, token = token)
        }
    }

    /** Sonraki kayıtlı DPI profiline geçer. */
    fun switchNextProfile(context: Context, token: String? = null): String? {
        if (!isInstalled(context)) return null
        val extras = Bundle().apply {
            if (!token.isNullOrBlank()) putString("token", token)
        }
        return try {
            val res = context.contentResolver.call(PROVIDER_URI, "next_profile", null, extras)
            res?.getString("active_profile_name")
        } catch (_: Throwable) {
            null
        }
    }

    /** GkTeDPI içindeki tüm kayıtlı ve hazır profilleri listeler. */
    fun listProfiles(context: Context): List<ProfileSummary> {
        if (!isInstalled(context)) return emptyList()
        return try {
            val res = context.contentResolver.call(PROVIDER_URI, "list_profiles", null, null)
            val ids = res?.getStringArrayList("profile_ids").orEmpty()
            val names = res?.getStringArrayList("profile_names").orEmpty()
            ids.zip(names) { id, name -> ProfileSummary(id, name) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * OkHttpClient / Ktor / Socket bağlantılarında doğrudan kullanmak üzere
     * GkTeDPI yerel SOCKS5 proxy nesnesini üretir (`OkHttpClient.Builder().proxy(...)`).
     */
    fun createSocksProxy(context: Context): Proxy {
        val st = getStatus(context)
        val host = if (st.proxyHost == "0.0.0.0" || st.proxyHost.isBlank()) "127.0.0.1" else st.proxyHost
        val port = st.proxyPort.coerceIn(1, 65535)
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    /**
     * GkTeDPI durum değişimlerini canlı olarak dinler.
     * Döndürülen `Closeable.close()` çağrıldığında alıcı (BroadcastReceiver) otomatik kaldırılır.
     */
    fun observeState(
        context: Context,
        onStateChanged: (TunnelStatus) -> Unit,
    ): Closeable {
        val appCtx = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STATE_CHANGED) {
                    val live = TunnelStatus(
                        installed = true,
                        apiEnabled = true,
                        running = intent.getBooleanExtra("running", false),
                        status = intent.getStringExtra("status") ?: "halted",
                        mode = intent.getStringExtra("mode") ?: "vpn",
                        activeProfileId = intent.getStringExtra("active_profile_id") ?: "",
                        activeProfileName = intent.getStringExtra("active_profile_name") ?: "",
                        proxyHost = intent.getStringExtra("proxy_ip") ?: "127.0.0.1",
                        proxyPort = intent.getIntExtra("proxy_port", 1080),
                    )
                    onStateChanged(live)
                }
            }
        }
        ContextCompat.registerReceiver(
            appCtx,
            receiver,
            IntentFilter(ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return Closeable {
            try {
                appCtx.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** Tek satırlık Deep Link URI üretici (`gktedpi://vpn/start?...`). */
    fun buildDeepLinkUri(
        command: String = "start",
        mode: String = "vpn",
        profileId: String? = null,
        cmdArgs: String? = null,
        token: String? = null,
    ): Uri = Uri.Builder()
        .scheme("gktedpi")
        .authority("vpn")
        .appendPath(command)
        .appendQueryParameter("mode", mode)
        .apply {
            if (!profileId.isNullOrBlank()) appendQueryParameter("profile", profileId)
            if (!cmdArgs.isNullOrBlank()) appendQueryParameter("cmd", cmdArgs)
            if (!token.isNullOrBlank()) appendQueryParameter("token", token)
        }
        .build()

    private fun launchBridgeIntent(
        context: Context,
        action: String,
        mode: String?,
        profileId: String?,
        cmdArgs: String?,
        token: String?,
    ) {
        val intent = Intent(action).apply {
            setPackage(GKTEDPI_PACKAGE)
            if (!mode.isNullOrBlank()) putExtra("mode", mode)
            if (!profileId.isNullOrBlank()) putExtra("profile_id", profileId)
            if (!cmdArgs.isNullOrBlank()) putExtra("cmd_args", cmdArgs)
            if (!token.isNullOrBlank()) putExtra("token", token)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
