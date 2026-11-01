package com.sametgkte.gktedpi.utility

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.data.SplitTunnelMode

val PreferenceFragmentCompat.sharedPreferences
    get() = preferenceScreen.sharedPreferences

fun Context.getPreferences(): SharedPreferences =
    PreferenceManager.getDefaultSharedPreferences(this)

fun SharedPreferences.getStringNotNull(key: String, defValue: String): String =
    getString(key, defValue) ?: defValue

fun SharedPreferences.mode(): Mode =
    Mode.fromString(getStringNotNull("byedpi_mode", "vpn"))

fun SharedPreferences.splitTunnelMode(): SplitTunnelMode =
    SplitTunnelMode.fromString(getString("split_tunnel_mode", "disabled"))

fun SharedPreferences.splitTunnelApps(): Set<String> =
    getStringSet("split_tunnel_apps", emptySet())?.toSet() ?: emptySet()

fun SharedPreferences.setSplitTunnelApps(apps: Set<String>) {
    edit().putStringSet("split_tunnel_apps", HashSet(apps)).apply()
}

fun <T : Preference> PreferenceFragmentCompat.findPreferenceNotNull(key: CharSequence): T =
    findPreference(key) ?: throw IllegalStateException("Preference $key not found")
