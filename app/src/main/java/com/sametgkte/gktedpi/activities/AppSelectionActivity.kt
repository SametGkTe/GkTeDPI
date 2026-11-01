package com.sametgkte.gktedpi.activities

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.adapters.AppEntry
import com.sametgkte.gktedpi.adapters.AppListAdapter
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.SplitTunnelMode
import com.sametgkte.gktedpi.databinding.ActivityAppSelectionBinding
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.setSplitTunnelApps
import com.sametgkte.gktedpi.utility.splitTunnelApps
import com.sametgkte.gktedpi.utility.splitTunnelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private lateinit var adapter: AppListAdapter
    private val selectedPackages = mutableSetOf<String>()
    private var allInstalledApps: List<AppEntry> = emptyList()
    private var currentMode: SplitTunnelMode = SplitTunnelMode.Disabled
    private var modified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.sametgkte.gktedpi.ui.LiquidGlassHelper.upgradeScreen(binding.appSelectContent)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appSelectContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        val prefs = getPreferences()
        currentMode = prefs.splitTunnelMode()
        selectedPackages.addAll(prefs.splitTunnelApps())

        adapter = AppListAdapter(selectedPackages) {
            modified = true
            prefs.setSplitTunnelApps(selectedPackages)
        }
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.appsRecyclerView.adapter = adapter

        binding.backButton.applyLiquidPressEffect()
        binding.backButton.setOnClickListener { finish() }

        binding.clearAppsButton.applyLiquidPressEffect()
        binding.clearAppsButton.setOnClickListener {
            selectedPackages.clear()
            modified = true
            prefs.setSplitTunnelApps(selectedPackages)
            filterAndSubmit()
        }

        binding.modeDisabledBtn.setOnClickListener { selectMode(SplitTunnelMode.Disabled) }
        binding.modeWhitelistBtn.setOnClickListener { selectMode(SplitTunnelMode.Whitelist) }
        binding.modeBlacklistBtn.setOnClickListener { selectMode(SplitTunnelMode.Blacklist) }
        updateModeButtons()

        binding.showSystemAppsSwitch.setOnCheckedChangeListener { _, _ ->
            filterAndSubmit()
        }

        binding.searchAppsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterAndSubmit()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadAppsAsync()
    }

    private fun selectMode(mode: SplitTunnelMode) {
        currentMode = mode
        modified = true
        getPreferences().edit().putString("split_tunnel_mode", mode.value).apply()
        updateModeButtons()
    }

    private fun updateModeButtons() {
        binding.modeDisabledBtn.setBackgroundResource(
            if (currentMode == SplitTunnelMode.Disabled) R.drawable.bg_liquid_glass_pill_active
            else R.drawable.bg_liquid_glass_pill
        )
        binding.modeWhitelistBtn.setBackgroundResource(
            if (currentMode == SplitTunnelMode.Whitelist) R.drawable.bg_liquid_glass_pill_active
            else R.drawable.bg_liquid_glass_pill
        )
        binding.modeBlacklistBtn.setBackgroundResource(
            if (currentMode == SplitTunnelMode.Blacklist) R.drawable.bg_liquid_glass_pill_active
            else R.drawable.bg_liquid_glass_pill
        )
    }

    private fun loadAppsAsync() {
        binding.loadingProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pm = packageManager
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != packageName }
                    .map { info ->
                        val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        AppEntry(
                            label = pm.getApplicationLabel(info).toString(),
                            packageName = info.packageName,
                            icon = try { pm.getApplicationIcon(info) } catch (_: Exception) { null },
                            isSystemApp = isSys,
                        )
                    }
                    .sortedWith(
                        compareByDescending<AppEntry> { it.packageName in selectedPackages }
                            .thenBy { it.label.lowercase(Locale.getDefault()) }
                    )
                    .toList()
            }
            allInstalledApps = loaded
            binding.loadingProgress.visibility = View.GONE
            filterAndSubmit()
        }
    }

    private fun filterAndSubmit() {
        val query = binding.searchAppsInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val showSys = binding.showSystemAppsSwitch.isChecked
        val filtered = allInstalledApps.filter { app ->
            val sysOk = showSys || !app.isSystemApp || app.packageName in selectedPackages
            val queryOk = query.isEmpty() ||
                app.label.lowercase(Locale.getDefault()).contains(query) ||
                app.packageName.lowercase(Locale.getDefault()).contains(query)
            sysOk && queryOk
        }
        adapter.submitList(filtered)
    }

    override fun onStop() {
        super.onStop()
        if (modified && appStatus.first == AppStatus.Running) {
            modified = false
            ServiceManager.restartIfRunning(this)
            Toast.makeText(this, R.string.settings_reloaded_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
