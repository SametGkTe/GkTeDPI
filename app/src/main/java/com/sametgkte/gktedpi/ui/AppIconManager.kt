package com.sametgkte.gktedpi.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.sametgkte.gktedpi.utility.getPreferences

/**
 * Uygulama Ana Ekran Simgesi (Launcher Icon) Değiştirici.
 * Android `<activity-alias>` mekanizması ile kurulu uygulamanın ana ekran simgesini değiştirir.
 */
object AppIconManager {

    private const val PREF_ACTIVE_ICON = "gkte_launcher_icon_variant"

    enum class IconVariant(
        val key: String,
        val aliasClassSuffix: String,
        val labelTr: String,
        val labelEn: String,
        val previewBgColor: Int,
    ) {
        DEFAULT(
            "default",
            ".activities.MainActivity",
            "Klasik Cam",
            "Classic Glass",
            0xFF0A141E.toInt()
        ),
        AMOLED(
            "amoled",
            ".IconAliasAmoled",
            "Saf Siyah",
            "Pure Black",
            0xFF000000.toInt()
        ),
        OCEAN(
            "ocean",
            ".IconAliasOcean",
            "Okyanus Cam",
            "Ocean Glass",
            0xFF04263D.toInt()
        ),
        CRYSTAL(
            "crystal",
            ".IconAliasCrystal",
            "Kristal Beyaz",
            "Crystal Light",
            0xFFF1F5F9.toInt()
        );

        companion object {
            fun fromKey(k: String?): IconVariant = entries.firstOrNull { it.key == k } ?: DEFAULT
        }
    }

    fun getCurrentVariant(context: Context): IconVariant =
        IconVariant.fromKey(context.getPreferences().getString(PREF_ACTIVE_ICON, IconVariant.DEFAULT.key))

    fun applyVariant(context: Context, chosen: IconVariant) {
        val pm = context.packageManager
        val pkg = context.packageName
        context.getPreferences().edit().putString(PREF_ACTIVE_ICON, chosen.key).apply()

        IconVariant.entries.forEach { variant ->
            val comp = ComponentName(pkg, pkg + variant.aliasClassSuffix)
            val newState = if (variant == chosen) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    comp,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
            }
        }
    }
}
