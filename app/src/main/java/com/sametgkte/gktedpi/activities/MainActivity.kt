package com.sametgkte.gktedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.BuildConfig
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.api.GkTeVpnApiBridge
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.FAILED_BROADCAST
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.data.STARTED_BROADCAST
import com.sametgkte.gktedpi.data.STOPPED_BROADCAST
import com.sametgkte.gktedpi.data.SplitTunnelMode
import com.sametgkte.gktedpi.databinding.ActivityMainBinding
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.services.connectedSinceElapsedMs
import com.sametgkte.gktedpi.services.currentTrafficStats
import com.sametgkte.gktedpi.ui.LiquidGlassDrawable
import com.sametgkte.gktedpi.ui.LiquidGlassHelper
import com.sametgkte.gktedpi.ui.PersonalizationManager
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.updater.GitHubAppUpdater
import com.sametgkte.gktedpi.utility.PublishEnhancements
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode
import com.sametgkte.gktedpi.utility.splitTunnelApps
import com.sametgkte.gktedpi.utility.splitTunnelMode
import com.sametgkte.gktedpi.vpn.RealVpnNodeManager
import com.sametgkte.gktedpi.vpn.TunnelEngine
import com.sametgkte.gktedpi.vpn.VpnCountries
import com.sametgkte.gktedpi.vpn.VpnEngineState
import com.sametgkte.gktedpi.widget.GkTeWidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var telemetryJob: Job? = null
    private var activeTabIndex: Int = 0
    private var suppressSwitchCallbacks: Boolean = false

    companion object {
        private var sessionStartedElapsedMs: Long = 0L
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                STARTED_BROADCAST -> {
                    if (sessionStartedElapsedMs == 0L) {
                        sessionStartedElapsedMs = SystemClock.elapsedRealtime()
                    }
                    updateStatus()
                }

                STOPPED_BROADCAST -> {
                    sessionStartedElapsedMs = 0L
                    updateStatus()
                }

                FAILED_BROADCAST -> {
                    sessionStartedElapsedMs = 0L
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, intent.getStringExtra("sender")),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }
            }
        }
    }

    private val personalizationListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                syncSystemBars()
                LiquidGlassHelper.refreshTree(binding.main)
                binding.ambientBackground.invalidate()
                binding.powerOrb.invalidate()
                binding.bottomGlassNav.invalidate()
                refreshModeSelectorUi()
                updateStatus()
            }
        }
    }

    private val vpnRuntimeListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                refreshModeSelectorUi()
                updateStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        PersonalizationManager.addListener(personalizationListener)
        VpnEngineState.addListener(vpnRuntimeListener)
        applyLiquidSurfaces()
        syncSystemBars()

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        setupBottomNavigation()
        setupHomeInteractions()
        setupUtilitiesInteractions()
        setupElectronSettingsInteractions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (appStatus.first == AppStatus.Running && sessionStartedElapsedMs == 0L) {
            sessionStartedElapsedMs = SystemClock.elapsedRealtime()
        }

        binding.settingsHeaderVersion.text = "v${BuildConfig.VERSION_NAME}"
        GkTeWidgetUpdater.updateAll(this)

        PublishEnhancements.maybeShowFirstLaunchOnboarding(this)
        GitHubAppUpdater.checkOnStartupIfEnabled(this, lifecycleScope)
    }

    private fun applyLiquidSurfaces() {
        LiquidGlassHelper.tagAndApply(binding.headerGlassBar, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.btnQuickAppearance, LiquidGlassDrawable.Kind.ICON_SQUIRCLE)
        LiquidGlassHelper.tagAndApply(binding.modesTopCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.heroGlassCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.statusButton, LiquidGlassDrawable.Kind.CTA_BUTTON)
        LiquidGlassHelper.tagAndApply(binding.btnPingTestPill, LiquidGlassDrawable.Kind.PILL_MUTED)
        LiquidGlassHelper.tagAndApply(binding.bentoDownCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.bentoUpCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.serversSelectorCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.serversSelectorIconBox, LiquidGlassDrawable.Kind.ICON_SQUIRCLE)
        LiquidGlassHelper.tagAndApply(binding.proxyEndpointCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.proxyEndpointIconBox, LiquidGlassDrawable.Kind.ICON_SQUIRCLE)

        // Utilities Tab
        LiquidGlassHelper.tagAndApply(binding.utilitiesHeroCard, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.cardNectarLink, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardLmPlayground, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardShizuku, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardSmartHub, LiquidGlassDrawable.Kind.HERO_ACCENT_CARD)

        // Electron-Style Settings Tab
        LiquidGlassHelper.tagAndApply(binding.settingsHeaderCard, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.bentoAppearanceHub, LiquidGlassDrawable.Kind.HERO_ACCENT_CARD)
        LiquidGlassHelper.tagAndApply(binding.bentoSmartCenter, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardSettingsNetwork, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardSettingsEngine, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardSettingsSystem, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardSettingsUpdatesAbout, LiquidGlassDrawable.Kind.CARD)

        listOf(
            binding.iconBoxDns,
            binding.iconBoxSplit,
            binding.iconBoxMtu,
            binding.iconBoxParams,
            binding.iconBoxStrategy,
            binding.iconBoxBattery,
            binding.iconBoxApi,
            binding.iconBoxLogs,
            binding.iconBoxUpdate,
            binding.iconBoxAbout,
        ).forEach { LiquidGlassHelper.tagAndApply(it, LiquidGlassDrawable.Kind.ICON_SQUIRCLE) }
    }

    private fun syncSystemBars() {
        val isLight = PersonalizationManager.getConfig(this).isLightResolved(this)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
    }

    private fun setupBottomNavigation() {
        binding.bottomGlassNav.bindBackdropSource(binding.ambientBackground)
        binding.bottomGlassNav.onTabSelected = { index ->
            switchTab(index)
        }
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        activeTabIndex = index
        binding.tabHomeScroll.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.tabUtilitiesScroll.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.tabSettingsScroll.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.bottomGlassNav.selectTab(index, notify = false)
        if (index == 2) {
            refreshElectronSettingsUi()
        }
    }

    private fun setupHomeInteractions() {
        val toggleAction = View.OnClickListener {
            binding.powerOrb.isClickable = false
            binding.statusButton.isClickable = false
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
            }
            binding.powerOrb.postDelayed({
                binding.powerOrb.isClickable = true
                binding.statusButton.isClickable = true
            }, 350L)
        }

        binding.powerOrb.applyLiquidPressEffect()
        binding.statusButton.applyLiquidPressEffect()
        binding.powerOrb.setOnClickListener(toggleAction)
        binding.statusButton.setOnClickListener(toggleAction)

        binding.btnQuickAppearance.applyLiquidPressEffect()
        binding.btnQuickAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        binding.modeChipRealVpn.applyLiquidPressEffect()
        binding.modeChipVpn.applyLiquidPressEffect()
        binding.modeChipProxy.applyLiquidPressEffect()
        binding.modeChipRealVpn.setOnClickListener { selectTopEngineOption(Mode.VPN, TunnelEngine.REAL_VPN) }
        binding.modeChipVpn.setOnClickListener { selectTopEngineOption(Mode.VPN, TunnelEngine.LOCAL_DPI) }
        binding.modeChipProxy.setOnClickListener { selectTopEngineOption(Mode.Proxy, VpnEngineState.getEngine(this)) }

        // Dedicated "Sunucular" compartment when VPN / DPI is selected!
        binding.serversSelectorCard.applyLiquidPressEffect()
        binding.serversSelectorCard.setOnClickListener {
            val tab = if (VpnEngineState.getEngine(this) == TunnelEngine.REAL_VPN) "vpn" else "dpi"
            startActivity(Intent(this, ServersActivity::class.java).putExtra("initial_tab", tab))
        }

        // 1-Tap FATİH / School Firewall Bypass Quick Pill on Home Screen
        LiquidGlassHelper.tagAndApply(binding.btnFatihStealthQuickPill, LiquidGlassDrawable.Kind.PILL_MUTED)
        binding.btnFatihStealthQuickPill.applyLiquidPressEffect()
        binding.btnFatihStealthQuickPill.setOnClickListener {
            val next = !VpnEngineState.isFatihStealthEnabled(this)
            VpnEngineState.setFatihStealthEnabled(this, next)
            Toast.makeText(
                this,
                getString(if (next) R.string.fatih_stealth_enabled_toast else R.string.fatih_stealth_disabled_toast),
                Toast.LENGTH_SHORT
            ).show()
            refreshModeSelectorUi()
            if (appStatus.first == AppStatus.Running && VpnEngineState.getEngine(this) == TunnelEngine.REAL_VPN) {
                ServiceManager.restartIfRunning(this)
            }
        }

        // Proxy quick parameter sheet when Proxy is selected
        binding.proxyEndpointCard.applyLiquidPressEffect()
        binding.proxyEndpointCard.setOnClickListener {
            showMtuPortDialog()
        }

        // Live Connectivity & Ping Verification Pill
        binding.btnPingTestPill.applyLiquidPressEffect()
        binding.btnPingTestPill.setOnClickListener {
            binding.btnPingTestPill.isEnabled = false
            binding.btnPingTestPill.setText(R.string.ping_test_btn_running)
            lifecycleScope.launch {
                val res = PublishEnhancements.runLiveConnectivityTest()
                binding.btnPingTestPill.isEnabled = true
                when {
                    res.reachable && res.discordReachable -> {
                        LiquidGlassHelper.tagAndApply(binding.btnPingTestPill, LiquidGlassDrawable.Kind.PILL_ACTIVE)
                        binding.btnPingTestPill.setTextColor(Color.WHITE)
                        if (VpnEngineState.getEngine(this@MainActivity) == TunnelEngine.REAL_VPN &&
                            res.detectedCountry.isNotBlank()
                        ) {
                            val ipTag = if (res.detectedIp.isNotBlank()) " (${res.detectedIp})" else ""
                            binding.btnPingTestPill.text = "✓ ${res.detectedCountry}$ipTag • ${res.bestLatencyMs} ms"
                        } else {
                            binding.btnPingTestPill.text = getString(R.string.ping_test_ok_all, res.bestLatencyMs)
                        }
                    }
                    res.reachable -> {
                        LiquidGlassHelper.tagAndApply(binding.btnPingTestPill, LiquidGlassDrawable.Kind.PILL_MUTED)
                        binding.btnPingTestPill.setTextColor(
                            PersonalizationManager.getConfig(this@MainActivity).primaryTextColor(this@MainActivity)
                        )
                        binding.btnPingTestPill.text = getString(R.string.ping_test_ok_partial, res.bestLatencyMs)
                    }
                    else -> {
                        LiquidGlassHelper.tagAndApply(binding.btnPingTestPill, LiquidGlassDrawable.Kind.PILL_MUTED)
                        binding.btnPingTestPill.setText(R.string.ping_test_offline)
                    }
                }
            }
        }
    }

    private fun selectTopEngineOption(targetMode: Mode, targetEngine: TunnelEngine) {
        val prefs = getPreferences()
        val currentMode = prefs.mode()
        val currentEngine = VpnEngineState.getEngine(this)
        if (currentMode == targetMode && (targetMode == Mode.Proxy || currentEngine == targetEngine)) {
            return
        }
        prefs.edit(commit = true) {
            putString("byedpi_mode", targetMode.toString().lowercase())
        }
        if (targetMode == Mode.VPN) {
            VpnEngineState.setEngine(this, targetEngine)
        }
        ProfileManager.syncCurrentToActiveProfile(this)
        refreshModeSelectorUi()
        updateStatus()
        if (appStatus.first == AppStatus.Running) {
            if (currentMode == Mode.VPN && targetMode == Mode.VPN) {
                ServiceManager.restartIfRunning(this)
            } else {
                ServiceManager.stop(this)
                binding.root.postDelayed({ start() }, 350L)
            }
        }
    }

    private fun refreshModeSelectorUi() {
        val cfg = PersonalizationManager.getConfig(this)
        val prefs = getPreferences()
        val currentMode = prefs.mode()
        val currentEngine = VpnEngineState.getEngine(this)
        val isRealVpn = currentMode == Mode.VPN && currentEngine == TunnelEngine.REAL_VPN
        val isLocalDpi = currentMode == Mode.VPN && currentEngine == TunnelEngine.LOCAL_DPI
        val isProxy = currentMode == Mode.Proxy

        LiquidGlassHelper.tagAndApply(
            binding.modeChipRealVpn,
            if (isRealVpn) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        LiquidGlassHelper.tagAndApply(
            binding.modeChipVpn,
            if (isLocalDpi) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        LiquidGlassHelper.tagAndApply(
            binding.modeChipProxy,
            if (isProxy) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        binding.modeChipRealVpn.setTextColor(if (isRealVpn) Color.WHITE else cfg.primaryTextColor(this))
        binding.modeChipVpn.setTextColor(if (isLocalDpi) Color.WHITE else cfg.primaryTextColor(this))
        binding.modeChipProxy.setTextColor(if (isProxy) Color.WHITE else cfg.primaryTextColor(this))

        binding.serversSelectorCard.visibility = if (!isProxy) View.VISIBLE else View.GONE
        binding.btnFatihStealthQuickPill.visibility = if (isRealVpn) View.VISIBLE else View.GONE
        binding.proxyEndpointCard.visibility = if (isProxy) View.VISIBLE else View.GONE

        val isTr = Locale.getDefault().language == "tr"
        if (isRealVpn) {
            val snap = VpnEngineState.snapshot
            val stealthActive = VpnEngineState.isFatihStealthEnabled(this) || (snap.connected && snap.fatihStealthActive)
            LiquidGlassHelper.tagAndApply(
                binding.btnFatihStealthQuickPill,
                if (stealthActive) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
            )
            binding.btnFatihStealthQuickPill.setText(
                if (stealthActive) R.string.fatih_stealth_home_pill_on else R.string.fatih_stealth_home_pill_off
            )
            binding.btnFatihStealthQuickPill.setTextColor(
                if (stealthActive) Color.WHITE else cfg.primaryTextColor(this)
            )

            val customId = VpnEngineState.getSelectedCustomEndpointId(this)
            val customEp = customId?.let { id -> RealVpnNodeManager.getCustomEndpoints(this).firstOrNull { it.id == id } }
            val activeCode = if (snap.connected && snap.countryCode.isNotBlank()) {
                snap.countryCode
            } else {
                VpnEngineState.getSelectedCountryCode(this)
            }
            val country = VpnCountries.byCode(activeCode)

            binding.serversSelectorFlagText.visibility = View.VISIBLE
            binding.serversSelectorIconImg.visibility = View.GONE
            binding.serversSelectorFlagText.text = if (customEp != null) "🔐" else country.flag

            binding.serversCardKicker.setText(R.string.vpn_location_kicker)
            binding.activeServerTitle.text = customEp?.label ?: if (country.code == "AUTO") {
                country.localizedName(isTr)
            } else {
                "${country.localizedName(isTr)} • ${country.localizedCity(isTr)}"
            }
            val stealthSuffix = if (stealthActive) " • 🏫 Port 443" else ""
            binding.activeServerSub.text = when {
                snap.connecting -> getString(R.string.vpn_connecting_fastest_sub) + stealthSuffix
                snap.connected && snap.exitIp.isNotBlank() ->
                    "${getString(R.string.vpn_exit_ip_short, snap.exitIp)} • ${snap.latencyMs} ms • ${snap.protocolBadge}$stealthSuffix"
                else -> getString(R.string.vpn_ready_encrypted_sub) + stealthSuffix
            }
        } else {
            binding.serversSelectorFlagText.visibility = View.GONE
            binding.serversSelectorIconImg.visibility = View.VISIBLE
            binding.serversCardKicker.setText(R.string.servers_card_kicker)

            val activeProfile = ProfileManager.getActiveProfile(this)
            val cmd = prefs.getString("byedpi_cmd_args", "")?.trim().orEmpty()
            val matchingPreset = BuiltInPresets.ALL.firstOrNull { it.commandArgs.trim() == cmd }

            binding.activeServerTitle.text = matchingPreset?.localizedName(isTr) ?: activeProfile.name
            val dns = prefs.getString("dns_ip", "1.1.1.1").orEmpty().ifBlank { "1.1.1.1" }
            binding.activeServerSub.text = getString(R.string.dpi_home_clean_sub, dns)
        }

        val ip = prefs.getString("byedpi_proxy_ip", "127.0.0.1").orEmpty().ifBlank { "127.0.0.1" }
        val port = prefs.getString("byedpi_proxy_port", "1080").orEmpty().ifBlank { "1080" }
        binding.proxyEndpointSub.text = "SOCKS5 $ip:$port"
    }

    private fun setupUtilitiesInteractions() {
        binding.cardNectarLink.applyLiquidPressEffect()
        binding.cardLmPlayground.applyLiquidPressEffect()
        binding.cardShizuku.applyLiquidPressEffect()
        binding.cardSmartHub.applyLiquidPressEffect()

        binding.cardNectarLink.setOnClickListener {
            PublishEnhancements.handleConnectEmbedTap(this, "NectarLink")
        }
        binding.cardLmPlayground.setOnClickListener {
            PublishEnhancements.handleConnectEmbedTap(this, "LM Playground")
        }
        binding.cardShizuku.setOnClickListener {
            startActivity(Intent(this, SmartHubActivity::class.java).putExtra("focus_section", "shizuku"))
        }
        binding.cardSmartHub.setOnClickListener {
            startActivity(Intent(this, SmartHubActivity::class.java))
        }
    }

    private fun setupElectronSettingsInteractions() {
        binding.bentoAppearanceHub.applyLiquidPressEffect()
        binding.bentoAppearanceHub.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        binding.bentoSmartCenter.applyLiquidPressEffect()
        binding.bentoSmartCenter.setOnClickListener {
            startActivity(Intent(this, SmartHubActivity::class.java))
        }

        binding.rowDnsSetting.applyLiquidPressEffect()
        binding.rowDnsSetting.setOnClickListener { showDnsPickerDialog() }

        binding.rowSplitTunnel.applyLiquidPressEffect()
        binding.rowSplitTunnel.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        binding.rowMtuPort.applyLiquidPressEffect()
        binding.rowMtuPort.setOnClickListener { showMtuPortDialog() }

        binding.rowEditDpiParams.applyLiquidPressEffect()
        binding.rowEditDpiParams.setOnClickListener {
            val useCli = getPreferences().getBoolean("byedpi_enable_cmd_settings", true)
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("open_fragment", if (useCli) "cmd" else "ui")
            )
        }

        binding.rowStrategyTest.applyLiquidPressEffect()
        binding.rowStrategyTest.setOnClickListener {
            startActivity(Intent(this, StrategyTesterActivity::class.java))
        }

        binding.rowExternalApi.applyLiquidPressEffect()
        binding.rowExternalApi.setOnClickListener { showExternalApiDialog() }

        binding.rowLogsBackup.applyLiquidPressEffect()
        binding.rowLogsBackup.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        binding.rowResetAll.applyLiquidPressEffect()
        binding.rowResetAll.setOnClickListener { confirmResetAllSettings() }

        binding.rowBatteryGuard.applyLiquidPressEffect()
        binding.rowBatteryGuard.setOnClickListener {
            PublishEnhancements.requestIgnoreBatteryOptimization(this)
        }

        binding.rowCheckUpdatesNow.applyLiquidPressEffect()
        binding.rowCheckUpdatesNow.setOnClickListener {
            GitHubAppUpdater.checkManuallyWithUi(this, lifecycleScope)
        }

        binding.rowAboutScreen.applyLiquidPressEffect()
        binding.rowAboutScreen.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            GitHubAppUpdater.setAutoCheckEnabled(this, checked)
        }

        binding.switchLanBypass.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            getPreferences().edit(commit = true) { putBoolean("lan_bypass", checked) }
            reloadTunnelIfRunning()
        }
        binding.switchIpv6.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            getPreferences().edit(commit = true) { putBoolean("ipv6_enable", checked) }
            reloadTunnelIfRunning()
        }
        binding.switchCliMode.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            getPreferences().edit(commit = true) { putBoolean("byedpi_enable_cmd_settings", checked) }
            refreshElectronSettingsUi()
            reloadTunnelIfRunning()
        }
        binding.switchBootStart.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            getPreferences().edit(commit = true) { putBoolean("auto_start_boot", checked) }
        }
    }

    private fun refreshElectronSettingsUi() {
        val prefs = getPreferences()
        val dns = prefs.getString("dns_ip", "1.1.1.1").orEmpty().ifBlank { "1.1.1.1" }
        val dnsName = when (dns) {
            "1.1.1.1", "1.0.0.1" -> "$dns • Cloudflare"
            "8.8.8.8", "8.8.4.4" -> "$dns • Google DNS"
            "9.9.9.9" -> "$dns • Quad9"
            "94.140.14.14" -> "$dns • AdGuard DNS"
            else -> dns
        }
        binding.valDnsSummary.text = dnsName

        val splitMode = prefs.splitTunnelMode()
        val splitCount = prefs.splitTunnelApps().size
        binding.valSplitSummary.text = when (splitMode) {
            SplitTunnelMode.Whitelist -> getString(R.string.split_tunnel_whitelist_count, splitCount)
            SplitTunnelMode.Blacklist -> getString(R.string.split_tunnel_blacklist_count, splitCount)
            SplitTunnelMode.Disabled -> getString(R.string.split_tunnel_desc)
        }

        val mtu = prefs.getString("vpn_mtu", "8500").orEmpty().ifBlank { "8500" }
        val port = prefs.getString("byedpi_proxy_port", "1080").orEmpty().ifBlank { "1080" }
        binding.valMtuPortSummary.text = "MTU $mtu • Port $port"

        val useCli = prefs.getBoolean("byedpi_enable_cmd_settings", true)
        val cmd = prefs.getString("byedpi_cmd_args", "-d 1 -r 1+s -M h,d -a 2").orEmpty()
        binding.valDpiCmdSummary.text = if (useCli) cmd else getString(R.string.ui_editor)

        val batteryProtected = PublishEnhancements.isBatteryOptimizationIgnored(this)
        binding.valBatteryGuardSummary.text = getString(
            if (batteryProtected) R.string.settings_battery_guard_on else R.string.settings_battery_guard_off
        )

        suppressSwitchCallbacks = true
        binding.switchLanBypass.isChecked = prefs.getBoolean("lan_bypass", true)
        binding.switchIpv6.isChecked = prefs.getBoolean("ipv6_enable", false)
        binding.switchCliMode.isChecked = useCli
        binding.switchBootStart.isChecked = prefs.getBoolean("auto_start_boot", false)
        binding.switchAutoUpdate.isChecked = GitHubAppUpdater.isAutoCheckEnabled(this)
        suppressSwitchCallbacks = false
    }

    private fun reloadTunnelIfRunning() {
        ProfileManager.syncCurrentToActiveProfile(this)
        refreshModeSelectorUi()
        if (appStatus.first == AppStatus.Running) {
            ServiceManager.restartIfRunning(this)
        }
    }

    private fun showDnsPickerDialog() {
        val options = arrayOf(
            "1.1.1.1 — Cloudflare",
            "8.8.8.8 — Google DNS",
            "9.9.9.9 — Quad9 DNS",
            "94.140.14.14 — AdGuard DNS",
            "Özel DNS IP..."
        )
        val ips = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.14")
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_dns_title)
            .setItems(options) { _, which ->
                if (which < ips.size) {
                    getPreferences().edit(commit = true) { putString("dns_ip", ips[which]) }
                    refreshElectronSettingsUi()
                    reloadTunnelIfRunning()
                } else {
                    val input = EditText(this).apply {
                        setText(getPreferences().getString("dns_ip", "1.1.1.1"))
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_dns_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val v = input.text?.toString()?.trim().orEmpty()
                            if (v.isNotEmpty()) {
                                getPreferences().edit(commit = true) { putString("dns_ip", v) }
                                refreshElectronSettingsUi()
                                reloadTunnelIfRunning()
                            }
                        }
                        .show()
                }
            }
            .show()
    }

    private fun showMtuPortDialog() {
        val dp = resources.displayMetrics.density
        val prefs = getPreferences()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }
        val mtuEdit = EditText(this).apply {
            hint = "VPN MTU (8500 / 1400)"
            setText(prefs.getString("vpn_mtu", "8500"))
        }
        val portEdit = EditText(this).apply {
            hint = "SOCKS5 Port (1080)"
            setText(prefs.getString("byedpi_proxy_port", "1080"))
        }
        wrap.addView(mtuEdit)
        wrap.addView(portEdit)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_mtu_port_title)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mtu = mtuEdit.text?.toString()?.trim().orEmpty().ifBlank { "8500" }
                val port = portEdit.text?.toString()?.trim().orEmpty().ifBlank { "1080" }
                prefs.edit(commit = true) {
                    putString("vpn_mtu", mtu)
                    putString("byedpi_proxy_port", port)
                }
                refreshElectronSettingsUi()
                reloadTunnelIfRunning()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun confirmResetAllSettings() {
        val msg = if (Locale.getDefault().language == "tr") {
            "Tüm ayarlar varsayılan değerlere sıfırlansın mı?"
        } else {
            "Reset all settings to defaults?"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_settings)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                getPreferences().edit(commit = true) { clear() }
                ProfileManager.getActiveProfile(this)
                refreshModeSelectorUi()
                refreshElectronSettingsUi()
                LiquidGlassHelper.refreshTree(binding.main)
                binding.ambientBackground.invalidate()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun showExternalApiDialog() {
        val token = PersonalizationManager.getOrCreateApiToken(this)
        val snippet = GkTeVpnApiBridge.getDeveloperIntegrationSnippet(this)
        val fullMessage = "Token: $token\n\n$snippet"

        AlertDialog.Builder(this)
            .setTitle(R.string.external_api_docs_title)
            .setMessage(fullMessage)
            .setPositiveButton(R.string.copy_short) { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("GkTeDPI External API", snippet))
                Toast.makeText(this, R.string.external_api_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.external_api_new_token) { _, _ ->
                PersonalizationManager.regenerateApiToken(this)
                showExternalApiDialog()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        ProfileManager.getActiveProfile(this)
        refreshModeSelectorUi()
        if (activeTabIndex == 2) refreshElectronSettingsUi()
        updateStatus()
        startTelemetryLoop()
        GkTeWidgetUpdater.updateAll(this)
        GitHubAppUpdater.resumePendingInstallIfPermitted(this)
    }

    override fun onPause() {
        super.onPause()
        telemetryJob?.cancel()
    }

    override fun onDestroy() {
        PersonalizationManager.removeListener(personalizationListener)
        VpnEngineState.removeListener(vpnRuntimeListener)
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = lifecycleScope.launch {
            while (isActive) {
                val (status, _) = appStatus
                val snap = currentTrafficStats
                if (status == AppStatus.Running) {
                    binding.downSpeedText.text = "↓ ${formatBytes(snap.rxSpeedBps)}/s"
                    binding.upSpeedText.text = "↑ ${formatBytes(snap.txSpeedBps)}/s"
                    binding.downTotalText.text = formatBytes(snap.rxBytes)
                    binding.upTotalText.text = formatBytes(snap.txBytes)
                    val since = if (connectedSinceElapsedMs > 0L) connectedSinceElapsedMs else sessionStartedElapsedMs
                    val elapsedSec = if (since > 0L) {
                        ((SystemClock.elapsedRealtime() - since) / 1000L).coerceAtLeast(0L)
                    } else 0L
                    binding.sessionTimerText.text = formatDuration(elapsedSec)
                } else {
                    binding.downSpeedText.text = "↓ 0 B/s"
                    binding.upSpeedText.text = "↑ 0 B/s"
                    binding.downTotalText.text = "0 B"
                    binding.upTotalText.text = "0 B"
                    binding.sessionTimerText.text = "00:00:00"
                }
                delay(1000L)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
            b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
            b >= 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            else -> "$b B"
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun start() {
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }
            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, mode) = appStatus
        val preferences = getPreferences()
        val proxyIp = preferences.getString("byedpi_proxy_ip", null) ?: "127.0.0.1"
        val proxyPort = preferences.getString("byedpi_proxy_port", null) ?: "1080"
        val dnsIp = preferences.getString("dns_ip", "1.1.1.1") ?: "1.1.1.1"
        val isRealVpn = preferences.mode() == Mode.VPN && VpnEngineState.getEngine(this) == TunnelEngine.REAL_VPN
        val snap = VpnEngineState.snapshot

        binding.proxyAddress.text = when {
            isRealVpn && snap.connecting -> getString(R.string.vpn_connecting_fastest_sub)
            isRealVpn && snap.connected && snap.exitIp.isNotBlank() -> {
                val c = VpnCountries.byCode(snap.countryCode)
                "${c.flag} ${getString(R.string.vpn_exit_ip_short, snap.exitIp)} • ${snap.latencyMs} ms"
            }
            isRealVpn -> {
                val c = VpnCountries.byCode(VpnEngineState.getSelectedCountryCode(this))
                val isTr = Locale.getDefault().language == "tr"
                "${c.flag} ${c.localizedName(isTr)} • ${getString(R.string.vpn_encrypted_badge)}"
            }
            else -> getString(R.string.endpoint_summary, proxyIp, proxyPort, dnsIp)
        }

        when (status) {
            AppStatus.Halted -> {
                binding.powerOrb.isConnectedState = false
                if (isRealVpn && snap.connecting) {
                    binding.statusButton.setText(R.string.vpn_connecting_btn)
                } else {
                    when (preferences.mode()) {
                        Mode.VPN -> binding.statusButton.setText(R.string.vpn_connect)
                        Mode.Proxy -> binding.statusButton.setText(R.string.proxy_start)
                    }
                }
            }

            AppStatus.Running -> {
                binding.powerOrb.isConnectedState = true
                when (mode) {
                    Mode.VPN -> binding.statusButton.setText(R.string.vpn_disconnect)
                    Mode.Proxy -> binding.statusButton.setText(R.string.proxy_stop)
                }
            }
        }
        GkTeWidgetUpdater.updateAll(this)
    }
}
