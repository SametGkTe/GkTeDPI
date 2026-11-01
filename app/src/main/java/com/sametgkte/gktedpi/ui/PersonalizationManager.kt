package com.sametgkte.gktedpi.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.sametgkte.gktedpi.utility.getPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

object PersonalizationManager {

    enum class ThemeMode(val key: String, val labelTr: String, val labelEn: String) {
        AMOLED_GLASS("amoled", "Saf Siyah (AMOLED)", "Pure Black (AMOLED)"),
        DARK_GLASS("dark", "Gece Camı", "Night Glass"),
        LIGHT_GLASS("light", "Kristal Açık", "Crystal Light"),
        SYSTEM("system", "Sistem", "System");

        companion object {
            fun fromKey(key: String?): ThemeMode =
                entries.firstOrNull { it.key == key } ?: AMOLED_GLASS
        }
    }

    enum class WallpaperStyle(
        val key: String,
        val labelTr: String,
        val labelEn: String,
    ) {
        PURE_BLACK("pure_black", "Saf Siyah (#000000)", "Pure Black (#000000)"),
        EMERALD_AURORA("emerald_aurora", "Zümrüt Aurora", "Emerald Aurora"),
        COSMIC_NEBULA("cosmic_nebula", "Kozmik Nebula", "Cosmic Nebula"),
        CRIMSON_OBSIDIAN("crimson_obsidian", "Kızıl Obsidyen", "Crimson Obsidian"),
        CARBON_MATRIX("carbon_matrix", "Karbon Mat Doku", "Carbon Matrix"),
        CUSTOM_PHOTO("custom_photo", "Özel Duvar Kağıdı", "Custom Wallpaper");

        companion object {
            fun fromKey(key: String?): WallpaperStyle =
                entries.firstOrNull { it.key == key } ?: PURE_BLACK
        }
    }

    enum class AccentPreset(
        val key: String,
        val labelTr: String,
        val primaryHex: Int,
        val secondaryHex: Int,
        val glowHex: Int,
    ) {
        EMERALD(
            "emerald",
            "Zümrüt",
            Color.parseColor("#10B981"),
            Color.parseColor("#059669"),
            Color.parseColor("#34D399"),
        ),
        CYAN(
            "cyan",
            "Buzul",
            Color.parseColor("#06B6D4"),
            Color.parseColor("#0284C7"),
            Color.parseColor("#38BDF8"),
        ),
        SAPPHIRE(
            "sapphire",
            "Safir",
            Color.parseColor("#3B82F6"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#60A5FA"),
        ),
        VIOLET(
            "violet",
            "Prizma",
            Color.parseColor("#8B5CF6"),
            Color.parseColor("#7C3AED"),
            Color.parseColor("#A78BFA"),
        ),
        AMBER(
            "amber",
            "Kehribar",
            Color.parseColor("#F59E0B"),
            Color.parseColor("#D97706"),
            Color.parseColor("#FBBF24"),
        ),
        ROSE(
            "rose",
            "Mercan",
            Color.parseColor("#F43F5E"),
            Color.parseColor("#E11D48"),
            Color.parseColor("#FB7185"),
        );

        companion object {
            fun fromKey(key: String?): AccentPreset =
                entries.firstOrNull { it.key == key } ?: EMERALD
        }
    }

    data class Config(
        val themeMode: ThemeMode,
        val wallpaperStyle: WallpaperStyle,
        val wallpaperDimLevel: Int,   // 0 = Hafif (%25), 1 = Dengeli (%48), 2 = Koyu (%70)
        val accent: AccentPreset,
        val frostLevel: Int,          // 0 = Şeffaf Kristal, 1 = Dengeli, 2 = Yoğun Buzlu
        val refractionLevel: Int,     // 0 = Yumuşak, 1 = Standart, 2 = Ultra Prizmatik
        val cornerRadiusDp: Float,    // 18f, 24f, 30f
        val animatedAmbient: Boolean,
        val hapticEnabled: Boolean,
    ) {
        fun isLightResolved(context: Context): Boolean = when (themeMode) {
            ThemeMode.LIGHT_GLASS -> true
            ThemeMode.AMOLED_GLASS, ThemeMode.DARK_GLASS -> false
            ThemeMode.SYSTEM -> {
                val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMask != Configuration.UI_MODE_NIGHT_YES
            }
        }

        fun primaryTextColor(context: Context): Int =
            if (isLightResolved(context) && wallpaperStyle == WallpaperStyle.PURE_BLACK) {
                Color.parseColor("#0B1220")
            } else {
                Color.WHITE
            }

        fun secondaryTextColor(context: Context): Int =
            if (isLightResolved(context) && wallpaperStyle == WallpaperStyle.PURE_BLACK) {
                Color.parseColor("#334155")
            } else {
                Color.parseColor("#B6C2D2")
            }
    }

    private const val KEY_THEME = "app_theme"
    private const val KEY_WALLPAPER = "glass_wallpaper_style"
    private const val KEY_WALLPAPER_DIM = "glass_wallpaper_dim"
    private const val KEY_ACCENT = "glass_accent_preset"
    private const val KEY_FROST = "glass_frost_level"
    private const val KEY_REFRACTION = "glass_refraction_level"
    private const val KEY_CORNER_DP = "glass_corner_dp"
    private const val KEY_ANIMATED_BG = "glass_animated_bg"
    private const val KEY_HAPTIC = "glass_haptic_enabled"
    private const val CUSTOM_WALLPAPER_FILENAME = "gkte_custom_wallpaper.jpg"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var cachedWallpaperBitmap: Bitmap? = null

    @Volatile
    private var cachedWallpaperStamp: Long = -1L

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun notifyChanged() {
        val dispatch = Runnable {
            listeners.forEach { listener ->
                try {
                    listener.invoke()
                } catch (_: Throwable) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch.run()
        } else {
            mainHandler.post(dispatch)
        }
    }

    fun getConfig(context: Context): Config {
        val prefs = context.getPreferences()
        return Config(
            themeMode = ThemeMode.fromKey(prefs.getString(KEY_THEME, ThemeMode.AMOLED_GLASS.key)),
            wallpaperStyle = WallpaperStyle.fromKey(prefs.getString(KEY_WALLPAPER, WallpaperStyle.PURE_BLACK.key)),
            wallpaperDimLevel = prefs.getInt(KEY_WALLPAPER_DIM, 1).coerceIn(0, 2),
            accent = AccentPreset.fromKey(prefs.getString(KEY_ACCENT, AccentPreset.EMERALD.key)),
            frostLevel = prefs.getInt(KEY_FROST, 1).coerceIn(0, 2),
            refractionLevel = prefs.getInt(KEY_REFRACTION, 2).coerceIn(0, 2),
            cornerRadiusDp = prefs.getFloat(KEY_CORNER_DP, 24f).coerceIn(16f, 32f),
            animatedAmbient = prefs.getBoolean(KEY_ANIMATED_BG, true),
            hapticEnabled = prefs.getBoolean(KEY_HAPTIC, true),
        )
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getPreferences().edit().putString(KEY_THEME, mode.key).apply()
        notifyChanged()
    }

    fun setWallpaperStyle(context: Context, style: WallpaperStyle) {
        context.getPreferences().edit().putString(KEY_WALLPAPER, style.key).apply()
        notifyChanged()
    }

    fun setWallpaperDimLevel(context: Context, level: Int) {
        context.getPreferences().edit().putInt(KEY_WALLPAPER_DIM, level.coerceIn(0, 2)).apply()
        notifyChanged()
    }

    fun saveCustomWallpaperFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val maxDim = max(boundsOpts.outWidth.coerceAtLeast(1), boundsOpts.outHeight.coerceAtLeast(1))
            var sample = 1
            while (maxDim / sample > 1600) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return false

            val rotationDeg = queryUriOrientationDegrees(context, uri)
            val bmp = if (rotationDeg != 0) {
                val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
            } else {
                rawBmp
            }

            val outFile = File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
            }
            cachedWallpaperBitmap = bmp
            cachedWallpaperStamp = outFile.lastModified()
            context.getPreferences()
                .edit()
                .putString(KEY_WALLPAPER, WallpaperStyle.CUSTOM_PHOTO.key)
                .commit()
            notifyChanged()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun queryUriOrientationDegrees(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            } ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    fun getCustomWallpaperBitmap(context: Context): Bitmap? {
        val f = File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
        if (!f.exists()) return null
        val mod = f.lastModified()
        val cached = cachedWallpaperBitmap
        if (cached != null && !cached.isRecycled && cachedWallpaperStamp == mod) {
            return cached
        }
        return try {
            val loaded = BitmapFactory.decodeFile(f.absolutePath)
            cachedWallpaperBitmap = loaded
            cachedWallpaperStamp = mod
            loaded
        } catch (_: Exception) {
            null
        }
    }

    fun hasCustomWallpaperFile(context: Context): Boolean =
        File(context.filesDir, CUSTOM_WALLPAPER_FILENAME).exists()

    fun setAccent(context: Context, accent: AccentPreset) {
        context.getPreferences().edit().putString(KEY_ACCENT, accent.key).apply()
        notifyChanged()
    }

    fun setFrostLevel(context: Context, level: Int) {
        context.getPreferences().edit().putInt(KEY_FROST, level.coerceIn(0, 2)).apply()
        notifyChanged()
    }

    fun setRefractionLevel(context: Context, level: Int) {
        context.getPreferences().edit().putInt(KEY_REFRACTION, level.coerceIn(0, 2)).apply()
        notifyChanged()
    }

    fun setCornerRadiusDp(context: Context, dp: Float) {
        context.getPreferences().edit().putFloat(KEY_CORNER_DP, dp).apply()
        notifyChanged()
    }

    fun setAnimatedAmbient(context: Context, enabled: Boolean) {
        context.getPreferences().edit().putBoolean(KEY_ANIMATED_BG, enabled).apply()
        notifyChanged()
    }

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit().putBoolean(KEY_HAPTIC, enabled).apply()
        notifyChanged()
    }

    fun getOrCreateApiToken(context: Context): String {
        val prefs = context.getPreferences()
        val existing = prefs.getString("external_api_token", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "gkte_" + UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("external_api_token", generated).apply()
        return generated
    }

    fun regenerateApiToken(context: Context): String {
        val generated = "gkte_" + UUID.randomUUID().toString().replace("-", "").take(16)
        context.getPreferences().edit().putString("external_api_token", generated).apply()
        return generated
    }
}
