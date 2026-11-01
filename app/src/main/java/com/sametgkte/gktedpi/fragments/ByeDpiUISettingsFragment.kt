package com.sametgkte.gktedpi.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.core.ByeDpiProxyUIPreferences
import com.sametgkte.gktedpi.core.ByeDpiProxyUIPreferences.DesyncMethod
import com.sametgkte.gktedpi.core.ByeDpiProxyUIPreferences.HostsMode
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.utility.checkIp
import com.sametgkte.gktedpi.utility.findPreferenceNotNull
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.isValidPortFilter
import com.sametgkte.gktedpi.utility.setEditTestPreferenceListenerInt
import com.sametgkte.gktedpi.utility.setEditTestPreferenceListenerPort
import com.sametgkte.gktedpi.utility.setEditTextPreferenceListener
import com.sametgkte.gktedpi.utility.sharedPreferences

class ByeDpiUISettingsFragment : PreferenceFragmentCompat() {

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.byedpi_ui_settings, rootKey)

        setEditTextPreferenceListener("byedpi_proxy_ip") { checkIp(it) }
        setEditTextPreferenceListener("byedpi_conn_ip") { it.isBlank() || checkIp(it) }
        setEditTestPreferenceListenerPort("byedpi_proxy_port")
        setEditTestPreferenceListenerInt("byedpi_max_connections", 1, 32767)
        setEditTestPreferenceListenerInt("byedpi_buffer_size", 512, 1048576)
        setEditTestPreferenceListenerInt("byedpi_default_ttl", 0, 255)
        setEditTestPreferenceListenerInt("byedpi_sfdelay", 0, 999)
        setEditTestPreferenceListenerInt("byedpi_cache_ttl", 1, 604800)
        setEditTextPreferenceListener("byedpi_timeout") {
            it.isBlank() || (it.toFloatOrNull()?.let { v -> v > 0f && v <= 120f } == true)
        }
        setEditTextPreferenceListener("byedpi_port_filter") { isValidPortFilter(it) }
        setEditTestPreferenceListenerInt("byedpi_split_position", -65535, 65535)
        setEditTestPreferenceListenerInt("byedpi_second_split_position", -65535, 65535)
        setEditTestPreferenceListenerInt("byedpi_fake_ttl", 1, 255)
        setEditTestPreferenceListenerInt("byedpi_fake_offset", 0, 65535)
        setEditTestPreferenceListenerInt("byedpi_tlsrec_position", -65535, 65535)
        setEditTestPreferenceListenerInt("byedpi_second_tlsrec_position", -65535, 65535)
        setEditTestPreferenceListenerInt("byedpi_udp_fake_count", 0, 64)
        setEditTextPreferenceListener("byedpi_oob_data") { it.trim().isNotEmpty() }

        findPreferenceNotNull<Preference>("byedpi_live_cli_preview")
            .setOnPreferenceClickListener { pref ->
                val cmd = pref.summary?.toString().orEmpty()
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GkTeDPI CLI", cmd))
                Toast.makeText(requireContext(), R.string.command_copied, Toast.LENGTH_SHORT).show()
                true
            }

        findPreferenceNotNull<Preference>("byedpi_export_to_cmd")
            .setOnPreferenceClickListener {
                val ctx = requireContext()
                val prefs = ctx.getPreferences()
                val generatedArgs = ByeDpiProxyUIPreferences(prefs)
                    .toCommandArgs()
                    .drop(1) // strip leading "ciadpi"
                    .joinToString(" ")
                ProfileManager.applyCustomCommandToActive(ctx, generatedArgs)
                Toast.makeText(ctx, R.string.command_copied, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
                true
            }

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        updatePreferences()
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun updatePreferences() {
        val ctx = context ?: return
        val uiPrefs = ByeDpiProxyUIPreferences(ctx.getPreferences())
        findPreferenceNotNull<Preference>("byedpi_live_cli_preview").summary = uiPrefs.toDisplayCommand()

        val autoEnabled = findPreferenceNotNull<SwitchPreference>("byedpi_auto_enabled").isChecked
        findPreferenceNotNull<CheckBoxPreference>("byedpi_auto_torst").isVisible = autoEnabled
        findPreferenceNotNull<CheckBoxPreference>("byedpi_auto_redirect").isVisible = autoEnabled
        findPreferenceNotNull<CheckBoxPreference>("byedpi_auto_ssl_err").isVisible = autoEnabled
        findPreferenceNotNull<CheckBoxPreference>("byedpi_auto_none").isVisible = autoEnabled
        findPreferenceNotNull<EditTextPreference>("byedpi_timeout").isVisible = autoEnabled
        findPreferenceNotNull<EditTextPreference>("byedpi_cache_ttl").isVisible = autoEnabled

        val hostsMode = HostsMode.fromName(
            findPreferenceNotNull<DropDownPreference>("byedpi_hosts_mode").value
        )
        findPreferenceNotNull<EditTextPreference>("byedpi_hosts_blacklist").isVisible =
            hostsMode == HostsMode.Blacklist
        findPreferenceNotNull<EditTextPreference>("byedpi_hosts_whitelist").isVisible =
            hostsMode == HostsMode.Whitelist

        val primaryMethod = DesyncMethod.fromName(
            findPreferenceNotNull<DropDownPreference>("byedpi_desync_method").value
        )
        val desyncActive = primaryMethod != DesyncMethod.None
        findPreferenceNotNull<EditTextPreference>("byedpi_split_position").isVisible = desyncActive
        findPreferenceNotNull<DropDownPreference>("byedpi_offset_flag").isVisible = desyncActive

        val secondSplitSwitch = findPreferenceNotNull<SwitchPreference>("byedpi_second_split_enabled")
        val secondActive = secondSplitSwitch.isChecked
        val secondMethodPref = findPreferenceNotNull<DropDownPreference>("byedpi_second_split_method")
        secondMethodPref.isVisible = secondActive
        findPreferenceNotNull<EditTextPreference>("byedpi_second_split_position").isVisible = secondActive
        findPreferenceNotNull<DropDownPreference>("byedpi_second_offset_flag").isVisible = secondActive

        val secondMethod = DesyncMethod.fromName(secondMethodPref.value)
        val usesFake = primaryMethod == DesyncMethod.Fake || (secondActive && secondMethod == DesyncMethod.Fake)
        findPreferenceNotNull<EditTextPreference>("byedpi_fake_ttl").isVisible = usesFake
        findPreferenceNotNull<CheckBoxPreference>("byedpi_md5sig").isVisible = usesFake
        findPreferenceNotNull<EditTextPreference>("byedpi_fake_offset").isVisible = usesFake
        findPreferenceNotNull<EditTextPreference>("byedpi_fake_sni").isVisible = usesFake
        findPreferenceNotNull<EditTextPreference>("byedpi_fake_data").isVisible = usesFake
        findPreferenceNotNull<EditTextPreference>("byedpi_ip_options").isVisible = usesFake

        val usesOob = primaryMethod == DesyncMethod.OOB || primaryMethod == DesyncMethod.DISOOB ||
            (secondActive && (secondMethod == DesyncMethod.OOB || secondMethod == DesyncMethod.DISOOB))
        findPreferenceNotNull<EditTextPreference>("byedpi_oob_data").isVisible = usesOob

        val tlsRec1 = findPreferenceNotNull<CheckBoxPreference>("byedpi_tlsrec_enabled").isChecked
        findPreferenceNotNull<EditTextPreference>("byedpi_tlsrec_position").isEnabled = tlsRec1
        findPreferenceNotNull<CheckBoxPreference>("byedpi_tlsrec_at_sni").isEnabled = tlsRec1
        val tlsRec2Pref = findPreferenceNotNull<CheckBoxPreference>("byedpi_second_tlsrec_enabled")
        tlsRec2Pref.isEnabled = tlsRec1
        val tlsRec2 = tlsRec1 && tlsRec2Pref.isChecked
        findPreferenceNotNull<EditTextPreference>("byedpi_second_tlsrec_position").isVisible = tlsRec2
        findPreferenceNotNull<CheckBoxPreference>("byedpi_second_tlsrec_at_sni").isVisible = tlsRec2
    }
}
