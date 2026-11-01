package com.sametgkte.gktedpi.activities

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.fragments.ByeDpiCommandLineSettingsFragment
import com.sametgkte.gktedpi.fragments.ByeDpiUISettingsFragment
import com.sametgkte.gktedpi.fragments.MainSettingsFragment
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.utility.getPreferences

class SettingsActivity : AppCompatActivity() {

    private var settingsDirty = false

    private val changeTracker = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key != "app_theme") {
            settingsDirty = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        com.sametgkte.gktedpi.ui.LiquidGlassHelper.upgradeScreen(findViewById(R.id.settingsRootLinear))

        val openFragment = intent.getStringExtra("open_fragment")
        if (savedInstanceState == null) {
            when (openFragment) {
                "cmd" -> supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, ByeDpiCommandLineSettingsFragment())
                    .commit()

                "ui" -> supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, ByeDpiUISettingsFragment())
                    .commit()

                else -> supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, MainSettingsFragment())
                    .commit()
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        getPreferences().registerOnSharedPreferenceChangeListener(changeTracker)
    }

    override fun onStop() {
        super.onStop()
        ProfileManager.syncCurrentToActiveProfile(this)
        if (settingsDirty) {
            settingsDirty = false
            if (appStatus.first == AppStatus.Running) {
                ServiceManager.restartIfRunning(this)
                Toast.makeText(this, R.string.settings_reloaded_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        try {
            getPreferences().unregisterOnSharedPreferenceChangeListener(changeTracker)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_settings, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        R.id.action_reset_settings -> {
            getPreferences().edit().clear().apply()
            settingsDirty = true
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, MainSettingsFragment())
                .commit()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
