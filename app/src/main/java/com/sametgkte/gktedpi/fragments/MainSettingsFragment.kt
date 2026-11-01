package com.sametgkte.gktedpi.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.sametgkte.gktedpi.BuildConfig
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.activities.AppSelectionActivity
import com.sametgkte.gktedpi.api.GkTeVpnApiBridge
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.ui.PersonalizationManager
import com.sametgkte.gktedpi.utility.RoutingUtils
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.utility.checkIp
import com.sametgkte.gktedpi.utility.checkNotLocalIp
import com.sametgkte.gktedpi.utility.findPreferenceNotNull
import com.sametgkte.gktedpi.utility.setEditTestPreferenceListenerInt
import com.sametgkte.gktedpi.utility.setEditTextPreferenceListener
import com.sametgkte.gktedpi.utility.sharedPreferences
import java.util.Locale

class MainSettingsFragment : PreferenceFragmentCompat() {

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        setEditTextPreferenceListener("dns_ip") {
            it.isBlank() || checkNotLocalIp(it)
        }
        setEditTextPreferenceListener("dns_ip_secondary") {
            it.isBlank() || checkNotLocalIp(it)
        }
        setEditTextPreferenceListener("dns_ipv6") {
            it.isBlank() || checkIp(it)
        }
        setEditTextPreferenceListener("tun_ipv4") {
            checkIp(it) && !it.contains(":")
        }
        setEditTestPreferenceListenerInt("vpn_mtu", 1280, 9000)
        setEditTestPreferenceListenerInt("tun2socks_stack_size", 16384, 524288)

        findPreferenceNotNull<DropDownPreference>("app_theme")
            .setOnPreferenceChangeListener { _, newValue ->
                val key = newValue as String
                PersonalizationManager.setThemeMode(
                    requireContext(),
                    PersonalizationManager.ThemeMode.fromKey(key)
                )
                ThemeUtils.applyTheme(key)
                true
            }

        findPreferenceNotNull<DropDownPreference>("dns_preset")
            .setOnPreferenceChangeListener { _, newValue ->
                val key = newValue as String
                RoutingUtils.DNS_PRESETS[key]?.let { (primary, secondary) ->
                    findPreferenceNotNull<EditTextPreference>("dns_ip").text = primary
                    findPreferenceNotNull<EditTextPreference>("dns_ip_secondary").text = secondary
                }
                true
            }

        findPreferenceNotNull<Preference>("open_split_tunnel")
            .setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AppSelectionActivity::class.java))
                true
            }

        findPreferenceNotNull<Preference>("open_smart_hub")
            .setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), com.sametgkte.gktedpi.activities.SmartHubActivity::class.java))
                true
            }

        findPreferenceNotNull<Preference>("load_isp_preset")
            .setOnPreferenceClickListener {
                showPresetPickerDialog()
                true
            }

        findPreferenceNotNull<Preference>("open_api_docs")
            .setOnPreferenceClickListener {
                showExternalApiDialog()
                true
            }

        val switchCmd = findPreferenceNotNull<SwitchPreference>("byedpi_enable_cmd_settings")
        val uiSettings = findPreferenceNotNull<Preference>("byedpi_ui_settings")
        val cmdSettings = findPreferenceNotNull<Preference>("byedpi_cmd_settings")

        val setSettingsMode = { enableCmd: Boolean ->
            uiSettings.isEnabled = !enableCmd
            cmdSettings.isEnabled = enableCmd
        }
        setSettingsMode(switchCmd.isChecked)

        switchCmd.setOnPreferenceChangeListener { _, newValue ->
            setSettingsMode(newValue as Boolean)
            true
        }

        findPreferenceNotNull<Preference>("version").summary =
            "${BuildConfig.VERSION_NAME} (com.sametgkte.gktedpi)"

        updatePreferences()
    }

    private fun showExternalApiDialog() {
        val ctx = requireContext()
        val token = PersonalizationManager.getOrCreateApiToken(ctx)
        val snippet = GkTeVpnApiBridge.getDeveloperIntegrationSnippet(ctx)
        val fullMessage = "Token: $token\n\n$snippet"

        AlertDialog.Builder(ctx)
            .setTitle(R.string.external_api_docs_title)
            .setMessage(fullMessage)
            .setPositiveButton(R.string.copy_short) { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GkTeDPI API", snippet))
                Toast.makeText(ctx, R.string.external_api_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.external_api_new_token) { _, _ ->
                PersonalizationManager.regenerateApiToken(ctx)
                showExternalApiDialog()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun showPresetPickerDialog() {
        val ctx = requireContext()
        val isTr = Locale.getDefault().language == "tr"
        val items = BuiltInPresets.ALL.map { preset ->
            "${preset.localizedName(isTr)}\n${preset.commandArgs}"
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle(R.string.load_isp_preset_title)
            .setItems(items) { _, which ->
                val chosen = BuiltInPresets.ALL[which]
                ProfileManager.applyPresetToActive(ctx, chosen)
                findPreferenceNotNull<SwitchPreference>("byedpi_enable_cmd_settings").isChecked = true
                findPreferenceNotNull<Preference>("byedpi_ui_settings").isEnabled = false
                findPreferenceNotNull<Preference>("byedpi_cmd_settings").isEnabled = true
                findPreferenceNotNull<EditTextPreference>("dns_ip").text = chosen.recommendedDns
                findPreferenceNotNull<EditTextPreference>("vpn_mtu").text = chosen.recommendedMtu.toString()
                Toast.makeText(
                    ctx,
                    getString(R.string.preset_applied_toast, chosen.localizedName(isTr)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
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
        val modeVal = findPreferenceNotNull<ListPreference>("byedpi_mode").value ?: "vpn"
        val mode = Mode.fromString(modeVal)
        val vpnCat = findPreferenceNotNull<PreferenceCategory>("vpn_network_category")
        val ipv6Enabled = findPreferenceNotNull<SwitchPreference>("ipv6_enable").isChecked
        val dnsIpv6 = findPreferenceNotNull<EditTextPreference>("dns_ipv6")

        vpnCat.isVisible = (mode == Mode.VPN)
        dnsIpv6.isVisible = (mode == Mode.VPN && ipv6Enabled)
    }
}
