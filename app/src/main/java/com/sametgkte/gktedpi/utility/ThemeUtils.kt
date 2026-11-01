package com.sametgkte.gktedpi.utility

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.sametgkte.gktedpi.ui.PersonalizationManager

object ThemeUtils {
    fun applyTheme(themeKey: String?) {
        val mode = when (PersonalizationManager.ThemeMode.fromKey(themeKey)) {
            PersonalizationManager.ThemeMode.LIGHT_GLASS -> AppCompatDelegate.MODE_NIGHT_NO
            PersonalizationManager.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            PersonalizationManager.ThemeMode.AMOLED_GLASS,
            PersonalizationManager.ThemeMode.DARK_GLASS -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applySavedTheme(context: Context) {
        val cfg = PersonalizationManager.getConfig(context)
        applyTheme(cfg.themeMode.key)
    }
}
