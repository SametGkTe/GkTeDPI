package com.sametgkte.gktedpi.utility

import android.net.InetAddresses
import android.os.Build
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.sametgkte.gktedpi.R

private const val TAG = "ValidateUtils"

fun checkIp(ip: String): Boolean {
    val trimmed = ip.trim()
    if (trimmed.isEmpty()) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        InetAddresses.isNumericAddress(trimmed)
    } else {
        @Suppress("DEPRECATION")
        Patterns.IP_ADDRESS.matcher(trimmed).matches() || trimmed.contains(":")
    }
}

fun checkNotLocalIp(ip: String): Boolean {
    val trimmed = ip.trim()
    if (trimmed.isEmpty()) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        InetAddresses.isNumericAddress(trimmed) && InetAddresses.parseNumericAddress(trimmed).let {
            !it.isAnyLocalAddress && !it.isLoopbackAddress
        }
    } else {
        checkIp(trimmed) && trimmed != "127.0.0.1" && trimmed != "0.0.0.0" && trimmed != "::1"
    }
}

fun PreferenceFragmentCompat.setEditTestPreferenceListenerPort(key: String) {
    setEditTestPreferenceListenerInt(key, 1, 65535)
}

fun PreferenceFragmentCompat.setEditTestPreferenceListenerInt(
    key: String,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE
) {
    setEditTextPreferenceListener(key) { value ->
        value.trim().toIntOrNull()?.let { it in min..max } ?: false
    }
}

fun PreferenceFragmentCompat.setEditTextPreferenceListener(
    key: String,
    check: (String) -> Boolean
) {
    findPreferenceNotNull<EditTextPreference>(key)
        .setOnPreferenceChangeListener { preference, newValue ->
            when (newValue) {
                is String -> {
                    val valid = check(newValue)
                    if (!valid) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.invalid_preference_value, preference.title.toString(), newValue),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    valid
                }

                else -> {
                    Log.w(TAG, "Invalid type for ${preference.key}: $newValue")
                    false
                }
            }
        }
}
