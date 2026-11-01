package com.sametgkte.gktedpi.fragments

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.utility.findPreferenceNotNull
import java.util.Locale

class ByeDpiCommandLineSettingsFragment : PreferenceFragmentCompat() {

    private val quickSnippets = listOf(
        "--disorder 1 (-d 1)" to "-d 1",
        "--disorder 1+s (-d 1+s)" to "-d 1+s",
        "--split 1+s --disorder 3+s" to "-s 1+s -d 3+s",
        "--tlsrec 1+s (-r 1+s)" to "-r 1+s",
        "--fake -1 --md5sig --ttl 8" to "-f -1 -S -t 8",
        "--auto=torst,redirect,ssl_err -T 2.5" to "-A t,r,s -T 2.5",
        "--mod-http=hcsmix,dcsmix (-M h,d)" to "-M h,d",
        "--udp-fake 4 (-a 4)" to "-a 4",
        "--drop-sack (-Y)" to "-Y",
        "--tfo (-F)" to "-F",
        "--disoob 1+s (-q 1+s)" to "-q 1+s",
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.byedpi_cmd_settings, rootKey)

        val cmdPref = findPreferenceNotNull<EditTextPreference>("byedpi_cmd_args")
        cmdPref.setOnPreferenceChangeListener { _, newValue ->
            val str = (newValue as? String).orEmpty()
            ProfileManager.addCommandHistory(requireContext(), str)
            true
        }

        findPreferenceNotNull<Preference>("cmd_load_preset")
            .setOnPreferenceClickListener {
                val ctx = requireContext()
                val isTr = Locale.getDefault().language == "tr"
                val labels = BuiltInPresets.ALL.map {
                    "${it.localizedName(isTr)}\n${it.commandArgs}"
                }.toTypedArray()

                AlertDialog.Builder(ctx)
                    .setTitle(R.string.load_isp_preset_title)
                    .setItems(labels) { _, which ->
                        val chosen = BuiltInPresets.ALL[which]
                        cmdPref.text = chosen.commandArgs
                        ProfileManager.addCommandHistory(ctx, chosen.commandArgs)
                        Toast.makeText(
                            ctx,
                            getString(R.string.preset_applied_toast, chosen.localizedName(isTr)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(R.string.cancel_button, null)
                    .show()
                true
            }

        findPreferenceNotNull<Preference>("cmd_insert_flag")
            .setOnPreferenceClickListener {
                val labels = quickSnippets.map { it.first }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.cmd_insert_flag_title)
                    .setItems(labels) { _, which ->
                        val snippet = quickSnippets[which].second
                        val current = cmdPref.text?.trim().orEmpty()
                        val updated = if (current.isEmpty()) snippet else "$current $snippet"
                        cmdPref.text = updated
                        ProfileManager.addCommandHistory(requireContext(), updated)
                    }
                    .setNegativeButton(R.string.cancel_button, null)
                    .show()
                true
            }

        findPreferenceNotNull<Preference>("cmd_history_pick")
            .setOnPreferenceClickListener {
                val ctx = requireContext()
                val history = ProfileManager.getCommandHistory(ctx)
                if (history.isEmpty()) {
                    Toast.makeText(ctx, "Henüz kayıtlı komut geçmişi yok", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.cmd_history_title)
                        .setItems(history.toTypedArray()) { _, which ->
                            cmdPref.text = history[which]
                        }
                        .setNegativeButton(R.string.cancel_button, null)
                        .show()
                }
                true
            }
    }
}
