package com.sametgkte.gktedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.databinding.ActivityServersBinding
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.DpiProfile
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.smart.IspAutoSwitchManager
import com.sametgkte.gktedpi.ui.LiquidGlassDrawable
import com.sametgkte.gktedpi.ui.LiquidGlassHelper
import com.sametgkte.gktedpi.ui.PersonalizationManager
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.utility.PublishEnhancements
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.vpn.RealVpnNodeManager
import com.sametgkte.gktedpi.vpn.TunnelEngine
import com.sametgkte.gktedpi.vpn.VpnCountries
import com.sametgkte.gktedpi.vpn.VpnEndpoint
import com.sametgkte.gktedpi.vpn.VpnEngineState
import com.sametgkte.gktedpi.widget.GkTeWidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class ServersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServersBinding
    private val isTr: Boolean get() = Locale.getDefault().language == "tr"
    private var showingRealVpnTab: Boolean = true
    private var pingScanJob: Job? = null
    private var suppressStealthSwitch: Boolean = false

    private val personalizationListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                LiquidGlassHelper.refreshTree(binding.serversContent)
                binding.serversAmbientBg.invalidate()
                renderAll()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityServersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.serversContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        val initialTab = intent?.getStringExtra("initial_tab")
        showingRealVpnTab = when (initialTab) {
            "dpi" -> false
            "vpn" -> true
            else -> VpnEngineState.getEngine(this) == TunnelEngine.REAL_VPN
        }

        PersonalizationManager.addListener(personalizationListener)

        LiquidGlassHelper.tagAndApply(binding.serversHeaderCard, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.serversBackBtn, LiquidGlassDrawable.Kind.PILL_MUTED)
        LiquidGlassHelper.tagAndApply(binding.btnAddCustomServer, LiquidGlassDrawable.Kind.PILL_ACTIVE)
        LiquidGlassHelper.tagAndApply(binding.serversTabBar, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.cardFatihStealth, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.bentoQuickConnectVpn, LiquidGlassDrawable.Kind.HERO_ACCENT_CARD)
        LiquidGlassHelper.tagAndApply(binding.bentoRefreshCountryPings, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.bentoAutoBenchmark, LiquidGlassDrawable.Kind.HERO_ACCENT_CARD)
        LiquidGlassHelper.tagAndApply(binding.bentoAutoIspQuick, LiquidGlassDrawable.Kind.CARD)

        binding.cardFatihStealth.applyLiquidPressEffect()
        binding.cardFatihStealth.setOnClickListener {
            applyFatihStealthToggle(!VpnEngineState.isFatihStealthEnabled(this))
        }
        binding.switchFatihStealth.setOnCheckedChangeListener { _, checked ->
            if (!suppressStealthSwitch) {
                applyFatihStealthToggle(checked)
            }
        }

        binding.serversBackBtn.applyLiquidPressEffect()
        binding.serversBackBtn.setOnClickListener { finish() }

        binding.tabBtnRealVpn.applyLiquidPressEffect()
        binding.tabBtnSimpleDpi.applyLiquidPressEffect()
        binding.tabBtnRealVpn.setOnClickListener { switchTab(true) }
        binding.tabBtnSimpleDpi.setOnClickListener { switchTab(false) }

        binding.btnAddCustomServer.applyLiquidPressEffect()
        binding.btnAddCustomServer.setOnClickListener {
            if (showingRealVpnTab) {
                showImportCustomVpnUriDialog()
            } else {
                showCreateCustomModeDialog()
            }
        }

        binding.bentoQuickConnectVpn.applyLiquidPressEffect()
        binding.bentoQuickConnectVpn.setOnClickListener {
            selectRealVpnCountry("AUTO", VpnCountries.byCode("AUTO").displayTitle(isTr))
        }

        binding.bentoRefreshCountryPings.applyLiquidPressEffect()
        binding.bentoRefreshCountryPings.setOnClickListener {
            triggerCountryPingScan()
        }

        binding.bentoAutoBenchmark.applyLiquidPressEffect()
        binding.bentoAutoBenchmark.setOnClickListener {
            startActivity(Intent(this, StrategyTesterActivity::class.java))
        }

        binding.bentoAutoIspQuick.applyLiquidPressEffect()
        binding.bentoAutoIspQuick.setOnClickListener {
            startActivity(Intent(this, SmartHubActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    override fun onDestroy() {
        pingScanJob?.cancel()
        PersonalizationManager.removeListener(personalizationListener)
        super.onDestroy()
    }

    private fun switchTab(realVpn: Boolean) {
        showingRealVpnTab = realVpn
        renderAll()
    }

    private fun renderAll() {
        val cfg = PersonalizationManager.getConfig(this)

        LiquidGlassHelper.tagAndApply(
            binding.tabBtnRealVpn,
            if (showingRealVpnTab) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        LiquidGlassHelper.tagAndApply(
            binding.tabBtnSimpleDpi,
            if (!showingRealVpnTab) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
        )
        binding.tabBtnRealVpn.setTextColor(if (showingRealVpnTab) Color.WHITE else cfg.primaryTextColor(this))
        binding.tabBtnSimpleDpi.setTextColor(if (!showingRealVpnTab) Color.WHITE else cfg.primaryTextColor(this))

        binding.btnAddCustomServer.setText(
            if (showingRealVpnTab) R.string.vpn_add_custom_btn else R.string.manage_profiles_button
        )

        binding.sectionRealVpnWrap.visibility = if (showingRealVpnTab) View.VISIBLE else View.GONE
        binding.sectionSimpleDpiWrap.visibility = if (!showingRealVpnTab) View.VISIBLE else View.GONE

        if (showingRealVpnTab) {
            renderRealVpnList()
        } else {
            renderSimpleDpiLists()
        }

        LiquidGlassHelper.refreshTree(binding.serversContent)
    }

    private fun applyFatihStealthToggle(enabled: Boolean) {
        VpnEngineState.setFatihStealthEnabled(this, enabled)
        VpnEngineState.setEngine(this, TunnelEngine.REAL_VPN)
        getPreferences().edit(commit = true) { putString("byedpi_mode", "vpn") }
        Toast.makeText(
            this,
            getString(if (enabled) R.string.fatih_stealth_enabled_toast else R.string.fatih_stealth_disabled_toast),
            Toast.LENGTH_SHORT
        ).show()
        if (appStatus.first == AppStatus.Running) {
            ServiceManager.restartIfRunning(this)
        }
        renderAll()
    }

    private fun renderRealVpnList() {
        val isRealEngineActive = VpnEngineState.getEngine(this) == TunnelEngine.REAL_VPN
        val selectedCountry = VpnEngineState.getSelectedCountryCode(this)
        val selectedCustomId = VpnEngineState.getSelectedCustomEndpointId(this)
        val snap = VpnEngineState.snapshot
        val stealthOn = VpnEngineState.isFatihStealthEnabled(this)

        suppressStealthSwitch = true
        binding.switchFatihStealth.isChecked = stealthOn
        suppressStealthSwitch = false
        binding.tvFatihStealthSub.setText(
            if (stealthOn) R.string.fatih_stealth_sub_on else R.string.fatih_stealth_sub_off
        )
        LiquidGlassHelper.tagAndApply(
            binding.cardFatihStealth,
            if (stealthOn) LiquidGlassDrawable.Kind.HERO_ACCENT_CARD else LiquidGlassDrawable.Kind.CARD
        )

        // 1. Custom imported VPN servers (if any)
        val customs = RealVpnNodeManager.getCustomEndpoints(this)
        binding.customVpnContainer.removeAllViews()
        if (customs.isNotEmpty()) {
            binding.customVpnSectionTitle.visibility = View.VISIBLE
            binding.customVpnContainer.visibility = View.VISIBLE
            customs.forEach { ep ->
                val isSelected = isRealEngineActive && selectedCustomId == ep.id
                val card = buildVisualCard(
                    badgeEmoji = "🔐",
                    iconRes = null,
                    title = ep.label,
                    subtitle = "${ep.protocol.badge} • ${ep.host}:${ep.port}",
                    detail = if (isSelected && snap.exitIp.isNotBlank()) {
                        "● IP: ${snap.exitIp} • ${snap.latencyMs} ms"
                    } else {
                        getString(R.string.vpn_custom_server_tag)
                    },
                    isSelected = isSelected,
                    onClick = {
                        VpnEngineState.setSelectedCustomEndpointId(this, ep.id)
                        VpnEngineState.setEngine(this, TunnelEngine.REAL_VPN)
                        getPreferences().edit(commit = true) { putString("byedpi_mode", "vpn") }
                        onSelectionApplied(ep.label)
                    },
                    onLongClick = {
                        showCustomEndpointActions(ep)
                    }
                )
                binding.customVpnContainer.addView(card)
            }
        } else {
            binding.customVpnSectionTitle.visibility = View.GONE
            binding.customVpnContainer.visibility = View.GONE
        }

        // 2. Proton-style Country VPN cards
        binding.vpnCountriesContainer.removeAllViews()
        VpnCountries.ALL.forEach { country ->
            val isSelected = isRealEngineActive && selectedCustomId == null &&
                selectedCountry.equals(country.code, ignoreCase = true)
            val cachedMs = RealVpnNodeManager.getCachedCountryPing(this, country.code)
            val liveConnectedHere = isSelected && snap.connected && snap.countryCode.equals(country.code, ignoreCase = true)

            val detailLine = when {
                liveConnectedHere && snap.exitIp.isNotBlank() ->
                    "● ${getString(R.string.vpn_exit_ip_short, snap.exitIp)} • ${snap.latencyMs} ms"
                cachedMs > 0L ->
                    "● $cachedMs ms • ${getString(R.string.vpn_encrypted_badge)}"
                country.recommended ->
                    getString(R.string.vpn_recommended_country_badge)
                else ->
                    getString(R.string.vpn_encrypted_badge)
            }

            val card = buildVisualCard(
                badgeEmoji = country.flag,
                iconRes = null,
                title = if (country.code == "AUTO") {
                    country.localizedName(isTr)
                } else {
                    "${country.localizedName(isTr)} • ${country.localizedCity(isTr)}"
                },
                subtitle = country.localizedDesc(isTr),
                detail = detailLine,
                isSelected = isSelected,
                onClick = {
                    selectRealVpnCountry(country.code, "${country.flag} ${country.localizedName(isTr)}")
                },
                onLongClick = null,
            )
            binding.vpnCountriesContainer.addView(card)
        }
    }

    private fun selectRealVpnCountry(countryCode: String, toastLabel: String) {
        VpnEngineState.setSelectedCountryCode(this, countryCode)
        VpnEngineState.setEngine(this, TunnelEngine.REAL_VPN)
        getPreferences().edit(commit = true) {
            putString("byedpi_mode", "vpn")
        }
        onSelectionApplied(toastLabel)
    }

    private fun triggerCountryPingScan() {
        if (pingScanJob?.isActive == true) return
        binding.vpnRefreshPingSub.setText(R.string.vpn_scanning_pings_progress)
        pingScanJob = lifecycleScope.launch {
            val codes = VpnCountries.ALL.map { it.code }.filter { it != "AUTO" }
            RealVpnNodeManager.measureCountryLatencies(
                context = this@ServersActivity,
                countryCodes = codes,
                protectSocket = null,
                onEachUpdated = { _, _ ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && showingRealVpnTab) {
                            renderRealVpnList()
                            LiquidGlassHelper.refreshTree(binding.vpnCountriesContainer)
                        }
                    }
                }
            )
            if (!isFinishing && !isDestroyed) {
                binding.vpnRefreshPingSub.setText(R.string.vpn_scan_pings_done)
                renderRealVpnList()
                LiquidGlassHelper.refreshTree(binding.vpnCountriesContainer)
            }
        }
    }

    private fun renderSimpleDpiLists() {
        val net = IspAutoSwitchManager.detectCurrentNetwork(this)
        val autoOn = IspAutoSwitchManager.isEnabled(this)
        binding.serversAutoIspSub.text = if (autoOn) {
            (if (isTr) "Aktif • " else "Active • ") + (if (isTr) net.displayLabelTr else net.displayLabelEn)
        } else {
            if (isTr) net.displayLabelTr else net.displayLabelEn
        }

        val isLocalDpiActive = VpnEngineState.getEngine(this) == TunnelEngine.LOCAL_DPI
        val activeProfile = ProfileManager.getActiveProfile(this)
        val currentCmd = getPreferences().getString("byedpi_cmd_args", "")?.trim().orEmpty()

        // 1. Simplified 5 Everyday DPI Modes (clean, zero raw CLI clutter)
        binding.builtinModesContainer.removeAllViews()
        BuiltInPresets.ALL.forEach { preset ->
            val matchesCmd = isLocalDpiActive && currentCmd == preset.commandArgs.trim()
            val card = buildVisualCard(
                badgeEmoji = null,
                iconRes = R.drawable.ic_vec_bolt,
                title = preset.localizedName(isTr),
                subtitle = preset.localizedDescription(isTr),
                detail = preset.localizedTag(isTr),
                isSelected = matchesCmd,
                onClick = {
                    VpnEngineState.setEngine(this, TunnelEngine.LOCAL_DPI)
                    ProfileManager.applyPresetToActive(this, preset)
                    ProfileManager.renameProfile(this, activeProfile.id, preset.localizedName(isTr))
                    onSelectionApplied(preset.localizedName(isTr))
                },
                onLongClick = null
            )
            binding.builtinModesContainer.addView(card)
        }

        // 2. Saved / User Profiles (clean human summary instead of raw CLI flags)
        binding.savedProfilesContainer.removeAllViews()
        val profiles = ProfileManager.getProfiles(this)
        profiles.forEach { profile ->
            val selected = isLocalDpiActive && profile.id == activeProfile.id
            val card = buildVisualCard(
                badgeEmoji = null,
                iconRes = R.drawable.ic_vec_servers,
                title = profile.name,
                subtitle = getString(R.string.dpi_profile_clean_sub, profile.dnsIp),
                detail = if (isTr) "YEREL DPI • SIFIR HIZ KAYBI" else "LOCAL DPI • ZERO SPEED LOSS",
                isSelected = selected,
                onClick = {
                    VpnEngineState.setEngine(this, TunnelEngine.LOCAL_DPI)
                    ProfileManager.activateProfile(this, profile.id)
                    onSelectionApplied(profile.name)
                },
                onLongClick = {
                    showProfileActionsDialog(profile)
                }
            )
            binding.savedProfilesContainer.addView(card)
        }
    }

    private fun buildVisualCard(
        badgeEmoji: String?,
        iconRes: Int?,
        title: String,
        subtitle: String,
        detail: String,
        isSelected: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
    ): LinearLayout {
        val dp = resources.displayMetrics.density
        val cfg = PersonalizationManager.getConfig(this)
        val extraBold = ResourcesCompat.getFont(this, R.font.pjs_extrabold)
        val medium = ResourcesCompat.getFont(this, R.font.pjs_medium)
        val bold = ResourcesCompat.getFont(this, R.font.pjs_bold)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (9 * dp).toInt()
            }
            LiquidGlassHelper.tagAndApply(
                this,
                if (isSelected) LiquidGlassDrawable.Kind.HERO_ACCENT_CARD else LiquidGlassDrawable.Kind.CARD
            )
            applyLiquidPressEffect()
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }

        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply {
                marginEnd = (13 * dp).toInt()
            }
            LiquidGlassHelper.tagAndApply(this, LiquidGlassDrawable.Kind.ICON_SQUIRCLE)
        }

        if (badgeEmoji != null) {
            val flagTv = TextView(this).apply {
                text = badgeEmoji
                textSize = 20f
                gravity = Gravity.CENTER
            }
            iconBox.addView(flagTv)
        } else if (iconRes != null) {
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
            }
            iconBox.addView(icon)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 14.5f
            typeface = extraBold
            setTextColor(if (isSelected) Color.WHITE else cfg.primaryTextColor(this@ServersActivity))
        }
        val subTv = TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            typeface = medium
            maxLines = 2
            setTextColor(if (isSelected) Color.parseColor("#E6FFFFFF") else cfg.secondaryTextColor(this@ServersActivity))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        val detailTv = TextView(this).apply {
            text = detail
            textSize = 10.5f
            typeface = bold
            maxLines = 1
            setTextColor(if (isSelected) Color.WHITE else cfg.accent.glowHex)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        textCol.addView(titleTv)
        textCol.addView(subTv)
        textCol.addView(detailTv)

        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_vec_chevron)
            alpha = if (isSelected) 1.0f else 0.55f
            layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                marginStart = (8 * dp).toInt()
            }
        }

        row.addView(iconBox)
        row.addView(textCol)
        row.addView(chevron)
        return row
    }

    private fun onSelectionApplied(modeLabel: String) {
        GkTeWidgetUpdater.updateAll(this)
        if (appStatus.first == AppStatus.Running) {
            ServiceManager.restartIfRunning(this)
        }
        Toast.makeText(
            this,
            getString(R.string.preset_applied_toast, modeLabel),
            Toast.LENGTH_SHORT
        ).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun showImportCustomVpnUriDialog() {
        val dp = resources.displayMetrics.density
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.vpn_custom_name_hint)
        }
        val uriInput = EditText(this).apply {
            hint = "ss://, trojan://, vless://, socks5://"
        }
        wrap.addView(labelInput)
        wrap.addView(uriInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_add_custom_dialog_title)
            .setView(wrap)
            .setPositiveButton(R.string.create_button) { _, _ ->
                val uri = uriInput.text?.toString()?.trim().orEmpty()
                val added = RealVpnNodeManager.addCustomEndpointFromUri(
                    this,
                    uriText = uri,
                    customLabel = labelInput.text?.toString()?.trim(),
                )
                if (added != null) {
                    VpnEngineState.setSelectedCustomEndpointId(this, added.id)
                    VpnEngineState.setEngine(this, TunnelEngine.REAL_VPN)
                    getPreferences().edit(commit = true) { putString("byedpi_mode", "vpn") }
                    onSelectionApplied(added.label)
                } else {
                    Toast.makeText(this, R.string.vpn_invalid_uri_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.vpn_paste_clipboard_btn) { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cb?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                val added = RealVpnNodeManager.addCustomEndpointFromUri(
                    this,
                    uriText = clip,
                    customLabel = labelInput.text?.toString()?.trim(),
                )
                if (added != null) {
                    VpnEngineState.setSelectedCustomEndpointId(this, added.id)
                    VpnEngineState.setEngine(this, TunnelEngine.REAL_VPN)
                    getPreferences().edit(commit = true) { putString("byedpi_mode", "vpn") }
                    onSelectionApplied(added.label)
                } else {
                    Toast.makeText(this, R.string.vpn_invalid_uri_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun showCustomEndpointActions(ep: VpnEndpoint) {
        val opts = if (isTr) arrayOf("Sil") else arrayOf("Delete")
        AlertDialog.Builder(this)
            .setTitle(ep.label)
            .setItems(opts) { _, _ ->
                RealVpnNodeManager.deleteCustomEndpoint(this, ep.id)
                renderAll()
            }
            .show()
    }

    private fun showCreateCustomModeDialog() {
        val dp = resources.displayMetrics.density
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.new_profile_hint)
        }
        val cmdInput = EditText(this).apply {
            hint = "-d 1 -r 1+s -M h,d -a 2"
            setText(getPreferences().getString("byedpi_cmd_args", "-d 1 -r 1+s -M h,d -a 2"))
        }
        wrap.addView(nameInput)
        wrap.addView(cmdInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.new_profile_dialog_title)
            .setView(wrap)
            .setPositiveButton(R.string.create_button) { _, _ ->
                VpnEngineState.setEngine(this, TunnelEngine.LOCAL_DPI)
                val created = ProfileManager.createNewProfile(
                    this,
                    nameInput.text?.toString()?.trim().orEmpty().ifBlank { "Özel Mod" }
                )
                val customCmd = cmdInput.text?.toString()?.trim().orEmpty()
                if (customCmd.isNotEmpty()) {
                    ProfileManager.applyCustomCommandToActive(this, customCmd)
                }
                onSelectionApplied(created.name)
            }
            .setNeutralButton(R.string.import_share_code_btn) { _, _ ->
                showImportShareCodeDialog()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun showImportShareCodeDialog() {
        val input = EditText(this).apply {
            hint = "gktedpi://profile?data=..."
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_share_code_btn)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val decoded = PublishEnhancements.decodeProfileShareCode(input.text?.toString().orEmpty())
                if (decoded != null) {
                    VpnEngineState.setEngine(this, TunnelEngine.LOCAL_DPI)
                    val created = ProfileManager.createNewProfile(this, decoded.name)
                    getPreferences().edit(commit = true) {
                        putString("dns_ip", decoded.dnsIp)
                        putString("vpn_mtu", decoded.vpnMtu.toString())
                    }
                    ProfileManager.applyCustomCommandToActive(this, decoded.commandArgs)
                    onSelectionApplied(created.name)
                } else {
                    Toast.makeText(this, R.string.share_code_invalid_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun showProfileActionsDialog(profile: DpiProfile) {
        val opts = if (isTr) {
            arrayOf("Paylaşım Kodu Kopyala", "Yeniden Adlandır", "Sil")
        } else {
            arrayOf("Copy Share Code", "Rename", "Delete")
        }
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        val code = PublishEnhancements.encodeProfileShareCode(profile)
                        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("GkTeDPI Profile", code))
                        Toast.makeText(this, R.string.share_code_copied_toast, Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val input = EditText(this).apply { setText(profile.name) }
                        AlertDialog.Builder(this)
                            .setTitle(opts[1])
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                ProfileManager.renameProfile(this, profile.id, input.text.toString())
                                renderAll()
                            }
                            .show()
                    }
                    2 -> {
                        if (ProfileManager.deleteProfile(this, profile.id)) {
                            renderAll()
                        }
                    }
                }
            }
            .show()
    }
}
