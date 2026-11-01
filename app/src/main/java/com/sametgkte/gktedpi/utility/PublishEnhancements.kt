package com.sametgkte.gktedpi.utility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.activities.AboutActivity
import com.sametgkte.gktedpi.profiles.DpiProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object PublishEnhancements {

    private const val PREF_ONBOARDING_ACCEPTED = "gkte_onboarding_accepted_v5"
    const val SHARE_PREFIX = "gktedpi://profile?data="

    private val GKTE_PATCHER_PACKAGES = listOf(
        "com.sametgkte.gktearchive",
        "com.sametgkte.gktepatcher",
        "com.gkte.archive",
        "com.gkte.patcher",
        "com.sametgkte.archivetool",
    )

    fun findInstalledGkTeToolPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in GKTE_PATCHER_PACKAGES) {
            val installed = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: Throwable) {
                false
            }
            if (installed) return pkg
        }
        return null
    }

    /**
     * Triggered when user taps NectarLink or LM Playground in GkTe Connect Tool.
     * Shows the exact required message when GkTe Archive / GkTe Patcher is missing.
     */
    fun handleConnectEmbedTap(activity: Activity, targetAppName: String) {
        val installedTool = findInstalledGkTeToolPackage(activity)
        if (installedTool != null) {
            val launch = activity.packageManager.getLaunchIntentForPackage(installedTool)
            if (launch != null) {
                launch.putExtra("embed_target", targetAppName)
                activity.startActivity(launch)
                return
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(targetAppName)
            .setMessage(R.string.gkte_archive_patcher_missing_msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } catch (_: Throwable) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    fun maybeShowFirstLaunchOnboarding(activity: Activity) {
        val prefs = activity.getPreferences()
        if (prefs.getBoolean(PREF_ONBOARDING_ACCEPTED, false)) return

        AlertDialog.Builder(activity)
            .setTitle(R.string.onboarding_welcome_title)
            .setMessage(R.string.onboarding_welcome_body)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_accept_btn) { _, _ ->
                prefs.edit().putBoolean(PREF_ONBOARDING_ACCEPTED, true).apply()
                if (!isBatteryOptimizationIgnored(activity)) {
                    requestIgnoreBatteryOptimization(activity)
                }
            }
            .setNeutralButton(R.string.about_screen_title) { _, _ ->
                activity.startActivity(Intent(activity, AboutActivity::class.java))
            }
            .show()
    }

    data class PingResult(
        val reachable: Boolean,
        val discordReachable: Boolean,
        val bestLatencyMs: Long,
        val detectedIp: String = "",
        val detectedCountry: String = "",
    )

    suspend fun runLiveConnectivityTest(): PingResult = withContext(Dispatchers.IO) {
        val trace = probeCloudflareTrace(4500)
        val gMs = probeHttpsUrl("https://connectivitycheck.gstatic.com/generate_204", 4500)
        val dcMs = probeHttpsUrl("https://discord.com/api/v9/Gateway", 5000)

        val validLatencies = listOf(trace.first, gMs, dcMs).filter { it >= 0L }
        val best = validLatencies.minOrNull() ?: -1L
        PingResult(
            reachable = trace.first >= 0L || gMs >= 0L,
            discordReachable = dcMs >= 0L,
            bestLatencyMs = best,
            detectedIp = trace.second,
            detectedCountry = trace.third,
        )
    }

    private fun probeCloudflareTrace(timeoutMs: Int): Triple<Long, String, String> {
        var conn: HttpURLConnection? = null
        return try {
            val t0 = SystemClock.elapsedRealtime()
            conn = (URL("https://1.1.1.1/cdn-cgi/trace").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GkTeDPI-Check")
            }
            val code = conn.responseCode
            val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                var ip = ""
                var loc = ""
                for (line in body.lineSequence()) {
                    val t = line.trim()
                    if (t.startsWith("ip=")) ip = t.removePrefix("ip=").trim()
                    else if (t.startsWith("loc=")) loc = t.removePrefix("loc=").trim()
                }
                Triple(dt, ip, loc)
            } else {
                Triple(-1L, "", "")
            }
        } catch (_: Throwable) {
            Triple(-1L, "", "")
        } finally {
            conn?.disconnect()
        }
    }

    private fun probeHttpsUrl(urlStr: String, timeoutMs: Int): Long {
        var conn: HttpURLConnection? = null
        return try {
            val t0 = SystemClock.elapsedRealtime()
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GkTeDPI-Check")
            }
            val code = conn.responseCode
            val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            if (code in 200..499) dt else -1L
        } catch (_: Throwable) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private const val B64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal fun base64UrlEncode(input: ByteArray): String {
        val sb = StringBuilder((input.size * 4 + 2) / 3)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else 0
            sb.append(B64_ALPHABET[b0 ushr 2])
            sb.append(B64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < input.size) sb.append(B64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (i + 2 < input.size) sb.append(B64_ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    internal fun base64UrlDecode(text: String): ByteArray {
        val clean = text.trim().trimEnd('=')
        val out = ByteArray((clean.length * 3) / 4)
        var outIdx = 0
        var buf = 0
        var bits = 0
        for (ch in clean) {
            val v = B64_ALPHABET.indexOf(ch)
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outIdx < out.size) {
                    out[outIdx++] = ((buf ushr bits) and 0xFF).toByte()
                }
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    /**
     * Encodes a DpiProfile into a shareable gktedpi://profile?data=... string.
     */
    fun encodeProfileShareCode(profile: DpiProfile): String {
        val json = JSONObject().apply {
            put("n", profile.name)
            put("c", profile.commandArgs)
            put("d", profile.dnsIp)
            put("m", profile.vpnMtu)
        }.toString()
        val encoded = base64UrlEncode(json.toByteArray(StandardCharsets.UTF_8))
        return "$SHARE_PREFIX$encoded"
    }

    data class SharedProfilePayload(
        val name: String,
        val commandArgs: String,
        val dnsIp: String,
        val vpnMtu: Int,
    )

    fun decodeProfileShareCode(codeText: String): SharedProfilePayload? {
        return try {
            val trimmed = codeText.trim()
            val b64 = if (trimmed.contains("data=")) {
                trimmed.substringAfter("data=").substringBefore("&")
            } else {
                trimmed
            }
            if (b64.isBlank()) return null
            val bytes = base64UrlDecode(b64)
            val obj = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val name = obj.optString("n", "").trim().ifBlank { return null }
            val cmd = obj.optString("c", "").trim().ifBlank { return null }
            val dns = obj.optString("d", "1.1.1.1").trim().ifBlank { "1.1.1.1" }
            val mtu = obj.optInt("m", 8500).coerceIn(1280, 9000)
            SharedProfilePayload(name, cmd, dns, mtu)
        } catch (_: Throwable) {
            null
        }
    }
}
