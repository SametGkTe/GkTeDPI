package com.sametgkte.gktedpi.smart

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.sametgkte.gktedpi.services.GkTeAppWatcherService
import com.sametgkte.gktedpi.utility.getPreferences

/**
 * Uygulama Bazlı Otomatik Tetikleyici (App Watcher) Ayar Yöneticisi.
 *
 * Seçilen hedef uygulamalardan biri ön plana geldiğinde GkTeDPI bağlantısını
 * otomatik uyandırır; hedef uygulamadan çıkıldığında ayarlanan bekleme süresi
 * sonunda pil tasarrufu için bağlantıyı uyutur.
 */
object AppTriggerManager {

    const val PREF_ENABLED = "app_trigger_enabled"
    const val PREF_PACKAGES = "app_trigger_packages"
    const val PREF_STOP_ON_EXIT_SEC = "app_trigger_stop_delay_sec"
    const val PREF_TRIGGER_MODE = "app_trigger_connect_mode" // "vpn" | "proxy" | "shizuku"

    val SUGGESTED_PACKAGES = linkedMapOf(
        "com.discord" to "Discord",
        "com.roblox.client" to "Roblox",
        "com.sametgkte.gktetok" to "GkteTok",
        "wp.wattpad" to "Wattpad",
        "com.twitter.android" to "X (Twitter)",
        "com.instagram.android" to "Instagram",
        "com.brave.browser" to "Brave Browser",
        "com.android.chrome" to "Google Chrome",
        "org.mozilla.firefox" to "Firefox",
    )

    private val IGNORED_TRANSIENT_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.miui.securitycenter",
    )

    @Volatile
    var autoStartedCurrentSession: Boolean = false

    fun isEnabled(context: Context): Boolean =
        context.getPreferences().getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun getWatchedPackages(context: Context): Set<String> {
        val raw = context.getPreferences().getStringSet(PREF_PACKAGES, null)
        if (raw != null) return raw.toSet()
        // Varsayılan başlangıç önerisi: Discord, Roblox ve GkteTok
        return setOf("com.discord", "com.roblox.client", "com.sametgkte.gktetok")
    }

    fun setWatchedPackages(context: Context, packages: Set<String>) {
        context.getPreferences().edit().putStringSet(PREF_PACKAGES, packages.toSet()).apply()
    }

    fun getStopDelaySeconds(context: Context): Int =
        context.getPreferences().getInt(PREF_STOP_ON_EXIT_SEC, 45)

    fun setStopDelaySeconds(context: Context, seconds: Int) {
        context.getPreferences().edit().putInt(PREF_STOP_ON_EXIT_SEC, seconds).apply()
    }

    fun getTriggerConnectMode(context: Context): String =
        context.getPreferences().getString(PREF_TRIGGER_MODE, "vpn") ?: "vpn"

    fun setTriggerConnectMode(context: Context, mode: String) {
        context.getPreferences().edit().putString(PREF_TRIGGER_MODE, mode).apply()
    }

    fun isTransientSystemPackage(pkg: String): Boolean {
        if (pkg in IGNORED_TRANSIENT_PACKAGES) return true
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") || lower.contains("keyboard")
    }

    /** Erişilebilirlik tetikleyici servisinin sistem ayarlarından açık olup olmadığını kontrol eder. */
    fun isWatcherServiceAuthorized(context: Context): Boolean {
        val expected = ComponentName(context, GkTeAppWatcherService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val comp = ComponentName.unflattenFromString(splitter.next())
            if (comp != null && comp == expected) return true
        }
        return false
    }
}
