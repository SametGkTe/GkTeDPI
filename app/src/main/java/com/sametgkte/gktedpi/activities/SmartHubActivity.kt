package com.sametgkte.gktedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.databinding.ActivitySmartHubBinding
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.sdk.GkTeDpiClient
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.shizuku.ShizukuBridgeManager
import com.sametgkte.gktedpi.smart.AppTriggerManager
import com.sametgkte.gktedpi.smart.IspAutoSwitchManager
import com.sametgkte.gktedpi.smart.SmartSniEngine
import com.sametgkte.gktedpi.ui.LiquidGlassDrawable
import com.sametgkte.gktedpi.ui.LiquidGlassHelper
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.Locale

class SmartHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmartHubBinding
    private val isTr: Boolean get() = Locale.getDefault().language == "tr"

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refreshShizukuSection() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySmartHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.smartHubContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        applyLiquidSurfaces()

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        } catch (_: Throwable) {
        }

        binding.smartBackBtn.applyLiquidPressEffect()
        binding.smartBackBtn.setOnClickListener { finish() }

        setupAutoIspSection()
        setupShizukuSection()
        setupSmartSniSection()
        setupAppTriggerSection()
        setupSdkSection()

        val focus = intent?.getStringExtra("focus_section")
        if (focus == "shizuku") {
            binding.smartScroll.post {
                binding.smartScroll.smoothScrollTo(0, binding.cardShizukuBridge.top)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAutoIspSection()
        refreshShizukuSection()
        refreshSmartSniSection()
        refreshAppTriggerSection()
        refreshSdkSection()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun applyLiquidSurfaces() {
        LiquidGlassHelper.tagAndApply(binding.smartHeaderCard, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.smartBackBtn, LiquidGlassDrawable.Kind.PILL_ACTIVE)

        listOf(
            binding.cardAutoIsp,
            binding.cardShizukuBridge,
            binding.cardSmartSni,
            binding.cardAppTrigger,
            binding.cardSdkBridge,
        ).forEach { LiquidGlassHelper.tagAndApply(it, LiquidGlassDrawable.Kind.CARD) }

        listOf(
            binding.btnSlotWifi,
            binding.btnSlotTtMobile,
            binding.btnSlotTurkcell,
            binding.btnSlotVodafone,
            binding.btnShizukuPerm,
            binding.btnShizukuResetProxy,
            binding.btnSelectTriggerApps,
            binding.btnTriggerSleepDelay,
        ).forEach {
            LiquidGlassHelper.tagAndApply(it, LiquidGlassDrawable.Kind.PILL_MUTED)
            it.applyLiquidPressEffect()
        }

        listOf(
            binding.btnSaveSniDomains,
            binding.btnOpenAccessibility,
            binding.btnCopySdkSnippet,
        ).forEach {
            LiquidGlassHelper.tagAndApply(it, LiquidGlassDrawable.Kind.PILL_ACTIVE)
            it.applyLiquidPressEffect()
        }

        LiquidGlassHelper.tagAndApply(binding.btnShizukuDualVpnToggle, LiquidGlassDrawable.Kind.CTA_BUTTON)
        binding.btnShizukuDualVpnToggle.applyLiquidPressEffect()
        LiquidGlassHelper.tagAndApply(binding.editCustomDomains, LiquidGlassDrawable.Kind.CODE_SURFACE)
        LiquidGlassHelper.refreshTree(binding.smartHubContent)
    }

    // =========================================================================
    // 1. AUTO-ISP SMART SWITCH
    // =========================================================================
    private fun setupAutoIspSection() {
        val prefs = getPreferences()
        binding.switchAutoIsp.isChecked = IspAutoSwitchManager.isEnabled(this)
        binding.switchAutoStartWifi.isChecked = prefs.getBoolean(IspAutoSwitchManager.PREF_AUTO_START_WIFI, false)
        binding.switchAutoStartMobile.isChecked = prefs.getBoolean(IspAutoSwitchManager.PREF_AUTO_START_MOBILE, false)

        binding.switchAutoIsp.setOnCheckedChangeListener { _, checked ->
            IspAutoSwitchManager.setEnabled(this, checked)
            refreshAutoIspSection()
        }
        binding.switchAutoStartWifi.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IspAutoSwitchManager.PREF_AUTO_START_WIFI, checked).apply()
        }
        binding.switchAutoStartMobile.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IspAutoSwitchManager.PREF_AUTO_START_MOBILE, checked).apply()
        }

        binding.btnSlotWifi.setOnClickListener {
            showSlotTargetPicker(IspAutoSwitchManager.NetworkSlot.WIFI_FIBER, if (isTr) "Wi-Fi / Fiber Ağ" else "Wi-Fi / Fiber")
        }
        binding.btnSlotTtMobile.setOnClickListener {
            showSlotTargetPicker(IspAutoSwitchManager.NetworkSlot.TURK_TELEKOM_MOBILE, "Türk Telekom Mobil")
        }
        binding.btnSlotTurkcell.setOnClickListener {
            showSlotTargetPicker(IspAutoSwitchManager.NetworkSlot.TURKCELL_MOBILE, "Turkcell Mobil")
        }
        binding.btnSlotVodafone.setOnClickListener {
            showSlotTargetPicker(IspAutoSwitchManager.NetworkSlot.VODAFONE_MOBILE, "Vodafone Mobil")
        }
    }

    private fun refreshAutoIspSection() {
        val net = IspAutoSwitchManager.detectCurrentNetwork(this)
        val label = if (isTr) net.displayLabelTr else net.displayLabelEn
        binding.detectedNetworkText.text = getString(R.string.auto_isp_detected_fmt, label)

        binding.btnSlotWifi.text = "Wi-Fi / Fiber  →  " +
            IspAutoSwitchManager.formatTargetLabel(this, IspAutoSwitchManager.getSlotTarget(this, IspAutoSwitchManager.NetworkSlot.WIFI_FIBER), isTr)
        binding.btnSlotTtMobile.text = "Türk Telekom 4.5G/5G  →  " +
            IspAutoSwitchManager.formatTargetLabel(this, IspAutoSwitchManager.getSlotTarget(this, IspAutoSwitchManager.NetworkSlot.TURK_TELEKOM_MOBILE), isTr)
        binding.btnSlotTurkcell.text = "Turkcell 4.5G/5G  →  " +
            IspAutoSwitchManager.formatTargetLabel(this, IspAutoSwitchManager.getSlotTarget(this, IspAutoSwitchManager.NetworkSlot.TURKCELL_MOBILE), isTr)
        binding.btnSlotVodafone.text = "Vodafone 4.5G/5G  →  " +
            IspAutoSwitchManager.formatTargetLabel(this, IspAutoSwitchManager.getSlotTarget(this, IspAutoSwitchManager.NetworkSlot.VODAFONE_MOBILE), isTr)
    }

    private fun showSlotTargetPicker(slot: IspAutoSwitchManager.NetworkSlot, slotTitle: String) {
        val specs = mutableListOf<String>()
        val titles = mutableListOf<String>()

        specs.add("none")
        titles.add(if (isTr) "Mevcut Profili Koru (Değiştirme)" else "Keep Current Profile")

        ProfileManager.getProfiles(this).forEach { prof ->
            specs.add("profile:${prof.id}")
            titles.add("Profil • ${prof.name}")
        }
        BuiltInPresets.ALL.forEach { preset ->
            specs.add("preset:${preset.id}")
            titles.add(preset.localizedName(isTr))
        }

        val current = IspAutoSwitchManager.getSlotTarget(this, slot)
        val checkedIdx = specs.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(slotTitle)
            .setSingleChoiceItems(titles.toTypedArray(), checkedIdx) { dlg, which ->
                IspAutoSwitchManager.setSlotTarget(this, slot, specs[which])
                if (IspAutoSwitchManager.isEnabled(this)) {
                    IspAutoSwitchManager.evaluateAndApplyNow(this, allowAutoStart = false)
                }
                refreshAutoIspSection()
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // =========================================================================
    // 2. SHIZUKU DUAL-VPN & HOTSPOT SHARE BRIDGE
    // =========================================================================
    private fun setupShizukuSection() {
        binding.btnShizukuPerm.setOnClickListener {
            val ok = ShizukuBridgeManager.requestPermission()
            if (!ok) {
                Toast.makeText(this, R.string.shizuku_not_running_toast, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuResetProxy.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuBridgeManager.disableDualVpnSystemProxy(this@SmartHubActivity, alsoStopProxyService = true)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SmartHubActivity, R.string.shizuku_proxy_reset_ok, Toast.LENGTH_SHORT).show()
                    refreshShizukuSection()
                }
            }
        }

        binding.btnShizukuDualVpnToggle.setOnClickListener {
            val state = ShizukuBridgeManager.getState(this)
            lifecycleScope.launch(Dispatchers.IO) {
                if (state.systemProxyActive) {
                    ShizukuBridgeManager.disableDualVpnSystemProxy(this@SmartHubActivity, alsoStopProxyService = true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SmartHubActivity, R.string.shizuku_dual_stopped, Toast.LENGTH_SHORT).show()
                        refreshShizukuSection()
                    }
                } else {
                    val res = ShizukuBridgeManager.enableDualVpnSystemProxy(this@SmartHubActivity)
                    withContext(Dispatchers.Main) {
                        if (res.isSuccess) {
                            Toast.makeText(
                                this@SmartHubActivity,
                                getString(R.string.shizuku_dual_started_fmt, res.getOrNull() ?: "127.0.0.1:8118"),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@SmartHubActivity,
                                res.exceptionOrNull()?.message ?: getString(R.string.shizuku_not_running_toast),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        refreshShizukuSection()
                    }
                }
            }
        }

        binding.switchHotspotShare.setOnCheckedChangeListener { _, checked ->
            val current = getPreferences().getBoolean(ShizukuBridgeManager.PREF_HOTSPOT_SHARE_ACTIVE, false)
            if (checked != current) {
                val info = ShizukuBridgeManager.setHotspotLanShareEnabled(this, checked)
                if (checked) {
                    copyToClipboard("GkTeDPI Hotspot Proxy", info)
                }
                refreshShizukuSection()
            }
        }

        binding.hotspotAddressText.setOnClickListener {
            copyToClipboard("GkTeDPI LAN Proxy", binding.hotspotAddressText.text.toString())
        }
    }

    private fun refreshShizukuSection() {
        val st = ShizukuBridgeManager.getState(this)
        val statusMsg = when {
            !st.installed -> getString(R.string.shizuku_state_not_installed)
            !st.binderAlive -> getString(R.string.shizuku_state_not_running)
            !st.permissionGranted -> getString(R.string.shizuku_state_need_perm)
            st.isRootShizuku -> getString(R.string.shizuku_state_ready_root)
            else -> getString(R.string.shizuku_state_ready_adb)
        }
        binding.shizukuStatusText.text = statusMsg
        binding.btnShizukuDualVpnToggle.text = getString(
            if (st.systemProxyActive) R.string.shizuku_stop_dual_vpn else R.string.shizuku_start_dual_vpn
        )
        binding.switchHotspotShare.isChecked = st.hotspotShareActive

        val lanIp = st.lanIpAddresses.firstOrNull() ?: "192.168.43.1"
        val socksPort = getPreferences().getString("byedpi_proxy_port", "1080") ?: "1080"
        binding.hotspotAddressText.text = if (st.hotspotShareActive) {
            "HTTP $lanIp:8118  •  SOCKS5 $lanIp:$socksPort"
        } else {
            if (isTr) "Yerel Ağ IP: $lanIp (Paylaşım kapalı)" else "LAN IP: $lanIp (Disabled)"
        }
    }

    // =========================================================================
    // 3. SMART SNI & ZERO-PING GAMING SPLIT
    // =========================================================================
    private fun setupSmartSniSection() {
        binding.editCustomDomains.setText(SmartSniEngine.getCustomDomainsRaw(this))

        binding.btnSniOff.applyLiquidPressEffect()
        binding.btnSniZeroPing.applyLiquidPressEffect()
        binding.btnSniOnlyBlocked.applyLiquidPressEffect()

        binding.btnSniOff.setOnClickListener {
            SmartSniEngine.setMode(this, SmartSniEngine.Mode.DISABLED)
            ServiceManager.restartIfRunning(this)
            refreshSmartSniSection()
        }
        binding.btnSniZeroPing.setOnClickListener {
            SmartSniEngine.setMode(this, SmartSniEngine.Mode.BYPASS_SAFE_DOMAINS)
            ServiceManager.restartIfRunning(this)
            refreshSmartSniSection()
        }
        binding.btnSniOnlyBlocked.setOnClickListener {
            SmartSniEngine.setMode(this, SmartSniEngine.Mode.ONLY_BLOCKED_DOMAINS)
            ServiceManager.restartIfRunning(this)
            refreshSmartSniSection()
        }

        binding.switchPackGaming.isChecked = SmartSniEngine.isPackEnabled(this, SmartSniEngine.PREF_PACK_GAMING, true)
        binding.switchPackBanking.isChecked = SmartSniEngine.isPackEnabled(this, SmartSniEngine.PREF_PACK_BANKING_GOV, true)
        binding.switchPackDiscord.isChecked = SmartSniEngine.isPackEnabled(this, SmartSniEngine.PREF_PACK_DISCORD, true)
        binding.switchPackSocial.isChecked = SmartSniEngine.isPackEnabled(this, SmartSniEngine.PREF_PACK_SOCIAL_MEDIA, true)

        binding.switchPackGaming.setOnCheckedChangeListener { _, c ->
            SmartSniEngine.setPackEnabled(this, SmartSniEngine.PREF_PACK_GAMING, c)
            ServiceManager.restartIfRunning(this)
        }
        binding.switchPackBanking.setOnCheckedChangeListener { _, c ->
            SmartSniEngine.setPackEnabled(this, SmartSniEngine.PREF_PACK_BANKING_GOV, c)
            ServiceManager.restartIfRunning(this)
        }
        binding.switchPackDiscord.setOnCheckedChangeListener { _, c ->
            SmartSniEngine.setPackEnabled(this, SmartSniEngine.PREF_PACK_DISCORD, c)
            ServiceManager.restartIfRunning(this)
        }
        binding.switchPackSocial.setOnCheckedChangeListener { _, c ->
            SmartSniEngine.setPackEnabled(this, SmartSniEngine.PREF_PACK_SOCIAL_MEDIA, c)
            ServiceManager.restartIfRunning(this)
        }

        binding.btnSaveSniDomains.setOnClickListener {
            SmartSniEngine.setCustomDomainsRaw(this, binding.editCustomDomains.text.toString())
            ServiceManager.restartIfRunning(this)
            val total = SmartSniEngine.buildActiveDomainList(this).size
            Toast.makeText(
                this,
                getString(R.string.sni_saved_toast_fmt, total),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshSmartSniSection() {
        val m = SmartSniEngine.getMode(this)
        LiquidGlassHelper.tagAndApply(
            binding.btnSniOff,
            if (m == SmartSniEngine.Mode.DISABLED) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        LiquidGlassHelper.tagAndApply(
            binding.btnSniZeroPing,
            if (m == SmartSniEngine.Mode.BYPASS_SAFE_DOMAINS) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        LiquidGlassHelper.tagAndApply(
            binding.btnSniOnlyBlocked,
            if (m == SmartSniEngine.Mode.ONLY_BLOCKED_DOMAINS) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        LiquidGlassHelper.refreshTree(binding.cardSmartSni)
    }

    // =========================================================================
    // 4. APP-BASED AUTO TRIGGER (APP WATCHER)
    // =========================================================================
    private fun setupAppTriggerSection() {
        binding.switchAppTrigger.isChecked = AppTriggerManager.isEnabled(this)
        binding.switchAppTrigger.setOnCheckedChangeListener { _, checked ->
            AppTriggerManager.setEnabled(this, checked)
            if (checked && !AppTriggerManager.isWatcherServiceAuthorized(this)) {
                Toast.makeText(this, R.string.app_trigger_enable_accessibility_hint, Toast.LENGTH_LONG).show()
            }
            refreshAppTriggerSection()
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnSelectTriggerApps.setOnClickListener {
            showTriggerAppsPicker()
        }

        binding.btnTriggerSleepDelay.setOnClickListener {
            val values = intArrayOf(0, 15, 45, 180)
            val labels = if (isTr) {
                arrayOf(
                    "Uygulamadan Çıkınca Kapatma (Açık Kalsın)",
                    "15 saniye sonra otomatik uyut",
                    "45 saniye sonra otomatik uyut (Önerilen)",
                    "3 dakika sonra otomatik uyut",
                )
            } else {
                arrayOf(
                    "Keep running after leaving app",
                    "Auto-sleep after 15 seconds",
                    "Auto-sleep after 45 seconds (Recommended)",
                    "Auto-sleep after 3 minutes",
                )
            }
            val current = AppTriggerManager.getStopDelaySeconds(this)
            val idx = values.indexOf(current).coerceAtLeast(2)
            AlertDialog.Builder(this)
                .setTitle(R.string.app_trigger_title)
                .setSingleChoiceItems(labels, idx) { dlg, which ->
                    AppTriggerManager.setStopDelaySeconds(this, values[which])
                    refreshAppTriggerSection()
                    dlg.dismiss()
                }
                .show()
        }
    }

    private fun refreshAppTriggerSection() {
        val authorized = AppTriggerManager.isWatcherServiceAuthorized(this)
        val count = AppTriggerManager.getWatchedPackages(this).size
        binding.appTriggerStatusText.text = if (authorized) {
            getString(R.string.app_trigger_ready_fmt, count)
        } else {
            getString(R.string.app_trigger_need_service_fmt, count)
        }
        val sec = AppTriggerManager.getStopDelaySeconds(this)
        binding.btnTriggerSleepDelay.text = if (sec <= 0) {
            if (isTr) "Otomatik Uyutma: Kapalı" else "Auto-Sleep: Disabled"
        } else {
            if (isTr) "Uygulamadan Çıkınca Otomatik Uyutma: ${sec} sn" else "Auto-Sleep Delay: ${sec}s"
        }
    }

    private fun showTriggerAppsPicker() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .filter { it.packageName != packageName }

            val merged = linkedMapOf<String, String>()
            AppTriggerManager.SUGGESTED_PACKAGES.forEach { (pkg, title) ->
                merged[pkg] = title
            }
            installed.forEach { app ->
                if (!merged.containsKey(app.packageName)) {
                    val lbl = app.loadLabel(pm).toString().trim()
                    if (lbl.isNotEmpty()) merged[app.packageName] = lbl
                }
            }

            val sortedEntries = merged.entries.sortedWith(
                compareByDescending<Map.Entry<String, String>> { AppTriggerManager.SUGGESTED_PACKAGES.containsKey(it.key) }
                    .thenBy { it.value.lowercase(Locale.getDefault()) }
            )

            val pkgArray = sortedEntries.map { it.key }.toTypedArray()
            val labelArray = sortedEntries.map { "${it.value} (${it.key})" }.toTypedArray()
            val currentSet = AppTriggerManager.getWatchedPackages(this@SmartHubActivity).toMutableSet()
            val checkedArray = BooleanArray(pkgArray.size) { idx -> pkgArray[idx] in currentSet }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@SmartHubActivity)
                    .setTitle(R.string.app_trigger_pick_apps_btn)
                    .setMultiChoiceItems(labelArray, checkedArray) { _, which, isChecked ->
                        if (isChecked) currentSet.add(pkgArray[which]) else currentSet.remove(pkgArray[which])
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        AppTriggerManager.setWatchedPackages(this@SmartHubActivity, currentSet)
                        refreshAppTriggerSection()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // =========================================================================
    // 5. GKTECONNECT MINI SDK LIVE BRIDGE
    // =========================================================================
    private fun setupSdkSection() {
        binding.btnCopySdkSnippet.setOnClickListener {
            val snippet = """
                // GkTeConnect Mini SDK (GkteTok / NectarLink / LM Playground)
                import com.sametgkte.gktedpi.sdk.GkTeDpiClient

                // 1) Uygulama açılışında tek satırla tüneli hazırla:
                val outcome = GkTeDpiClient.ensureConnected(
                    context = this,
                    mode = "vpn",
                    profileId = "tr_universal"
                )

                // 2) Sadece OkHttp/WebSocket trafiğini SOCKS5 üzerinden geçir:
                val client = OkHttpClient.Builder()
                    .proxy(GkTeDpiClient.createSocksProxy(context))
                    .build()
            """.trimIndent()
            copyToClipboard("GkTeConnect SDK", snippet)
        }
    }

    private fun refreshSdkSection() {
        val status = GkTeDpiClient.getStatus(this)
        binding.sdkLiveStatusText.text =
            "GkTeDpiClient.getStatus() → running=${status.running} • mode=${status.mode} • ${status.activeProfileName} (${status.proxyHost}:${status.proxyPort})"
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
