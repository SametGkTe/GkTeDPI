package com.sametgkte.gktedpi.activities

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.databinding.ActivityAppearanceBinding
import com.sametgkte.gktedpi.ui.AppIconManager
import com.sametgkte.gktedpi.ui.LiquidGlassDrawable
import com.sametgkte.gktedpi.ui.LiquidGlassHelper
import com.sametgkte.gktedpi.ui.PersonalizationManager
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.utility.ThemeUtils
import com.sametgkte.gktedpi.widget.GkTeCompactWidgetProvider
import com.sametgkte.gktedpi.widget.GkTeControlWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding
    private val isTr: Boolean get() = Locale.getDefault().language == "tr"

    private val pickWallpaperLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = PersonalizationManager.saveCustomWallpaperFromUri(this@AppearanceActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            binding.appearanceAmbientBg.invalidate()
                            Toast.makeText(
                                this@AppearanceActivity,
                                R.string.wallpaper_applied_ok,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@AppearanceActivity,
                                R.string.wallpaper_failed_toast,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        renderAllControls()
                    }
                }
            }
        }

    private val personalizationListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                syncSystemBars()
                binding.appearanceAmbientBg.invalidate()
                LiquidGlassHelper.refreshTree(binding.appearanceContent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appearanceContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        PersonalizationManager.addListener(personalizationListener)
        applyLiquidSurfaces()
        syncSystemBars()

        binding.appearanceBackBtn.applyLiquidPressEffect()
        binding.appearanceBackBtn.setOnClickListener { finish() }

        binding.btnPickGalleryWallpaper.applyLiquidPressEffect()
        binding.btnPickGalleryWallpaper.setOnClickListener {
            pickWallpaperLauncher.launch("image/*")
        }

        binding.btnPinCompactWidget.applyLiquidPressEffect()
        binding.btnPinCompactWidget.setOnClickListener {
            pinWidget(ComponentName(this, GkTeCompactWidgetProvider::class.java))
        }

        binding.btnPinControlWidget.applyLiquidPressEffect()
        binding.btnPinControlWidget.setOnClickListener {
            pinWidget(ComponentName(this, GkTeControlWidgetProvider::class.java))
        }

        val cfg = PersonalizationManager.getConfig(this)
        binding.switchPrismRefraction.isChecked = cfg.refractionLevel >= 2
        binding.switchHaptics.isChecked = cfg.hapticEnabled

        binding.switchPrismRefraction.setOnCheckedChangeListener { _, checked ->
            PersonalizationManager.setRefractionLevel(this, if (checked) 2 else 1)
        }
        binding.switchHaptics.setOnCheckedChangeListener { _, checked ->
            PersonalizationManager.setHapticEnabled(this, checked)
        }

        renderAllControls()
    }

    override fun onDestroy() {
        PersonalizationManager.removeListener(personalizationListener)
        super.onDestroy()
    }

    private fun applyLiquidSurfaces() {
        LiquidGlassHelper.tagAndApply(binding.appearanceHeaderCard, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.appearanceBackBtn, LiquidGlassDrawable.Kind.PILL_MUTED)
        listOf(
            binding.cardAppIcons,
            binding.cardWallpapers,
            binding.cardThemeAccent,
            binding.cardLiquidOptics,
            binding.cardWidgets,
        ).forEach { LiquidGlassHelper.tagAndApply(it, LiquidGlassDrawable.Kind.CARD) }

        LiquidGlassHelper.tagAndApply(binding.btnPickGalleryWallpaper, LiquidGlassDrawable.Kind.PILL_ACTIVE)
        LiquidGlassHelper.tagAndApply(binding.btnPinCompactWidget, LiquidGlassDrawable.Kind.PILL_ACTIVE)
        LiquidGlassHelper.tagAndApply(binding.btnPinControlWidget, LiquidGlassDrawable.Kind.PILL_ACTIVE)
    }

    private fun syncSystemBars() {
        val isLight = PersonalizationManager.getConfig(this).isLightResolved(this)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.isAppearanceLightStatusBars = isLight
        c.isAppearanceLightNavigationBars = isLight
    }

    private fun renderAllControls() {
        val dp = resources.displayMetrics.density
        val boldFont = ResourcesCompat.getFont(this, R.font.pjs_bold)
        val cfg = PersonalizationManager.getConfig(this)

        // 1. App Icons Row
        binding.iconVariantsRow.removeAllViews()
        val activeIcon = AppIconManager.getCurrentVariant(this)
        AppIconManager.IconVariant.entries.forEach { variant ->
            val selected = variant == activeIcon
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                applyLiquidPressEffect()
                setOnClickListener {
                    AppIconManager.applyVariant(this@AppearanceActivity, variant)
                    Toast.makeText(
                        this@AppearanceActivity,
                        getString(R.string.icon_switched_toast, if (isTr) variant.labelTr else variant.labelEn),
                        Toast.LENGTH_SHORT
                    ).show()
                    renderAllControls()
                }
            }

            val previewTile = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * dp
                    setColor(variant.previewBgColor)
                    setStroke(
                        if (selected) (2.4f * dp).toInt() else (1f * dp).toInt(),
                        if (selected) cfg.accent.primaryHex else Color.argb(80, 255, 255, 255)
                    )
                }
            }
            val logoImg = ImageView(this).apply {
                setImageResource(R.drawable.ic_gkte_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((42 * dp).toInt(), (18 * dp).toInt())
            }
            previewTile.addView(logoImg)

            val lbl = TextView(this).apply {
                text = if (isTr) variant.labelTr else variant.labelEn
                textSize = 10.5f
                typeface = boldFont
                gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
                setTextColor(if (selected) cfg.accent.glowHex else cfg.secondaryTextColor(this@AppearanceActivity))
            }
            cell.addView(previewTile)
            cell.addView(lbl)
            binding.iconVariantsRow.addView(cell)
        }

        // 2. Wallpaper Styles Row
        binding.wallpaperPillsRow.removeAllViews()
        PersonalizationManager.WallpaperStyle.entries.forEach { style ->
            if (style == PersonalizationManager.WallpaperStyle.CUSTOM_PHOTO &&
                !PersonalizationManager.hasCustomWallpaperFile(this)
            ) {
                return@forEach
            }
            val selected = cfg.wallpaperStyle == style
            val pill = buildPill(
                text = if (isTr) style.labelTr else style.labelEn,
                selected = selected,
                onClick = {
                    PersonalizationManager.setWallpaperStyle(this, style)
                    renderAllControls()
                }
            )
            binding.wallpaperPillsRow.addView(pill)
        }

        // Wallpaper Dim Level Row
        binding.wallpaperDimRow.removeAllViews()
        val dimLabels = if (isTr) listOf("Canlı (%25)", "Dengeli (%48)", "Koyu (%70)") else listOf("Vivid", "Balanced", "Deep Dim")
        dimLabels.forEachIndexed { idx, label ->
            val pill = buildPill(
                text = label,
                selected = cfg.wallpaperDimLevel == idx,
                weight = 1f,
                onClick = {
                    PersonalizationManager.setWallpaperDimLevel(this, idx)
                    renderAllControls()
                }
            )
            binding.wallpaperDimRow.addView(pill)
        }

        // 3. Theme Mode Row
        binding.themePillsRow.removeAllViews()
        PersonalizationManager.ThemeMode.entries.forEach { mode ->
            val selected = cfg.themeMode == mode
            val pill = buildPill(
                text = if (isTr) mode.labelTr else mode.labelEn,
                selected = selected,
                onClick = {
                    PersonalizationManager.setThemeMode(this, mode)
                    ThemeUtils.applyTheme(mode.key)
                    renderAllControls()
                }
            )
            binding.themePillsRow.addView(pill)
        }

        // Accent Swatches Row
        binding.accentSwatchesRow.removeAllViews()
        PersonalizationManager.AccentPreset.entries.forEach { preset ->
            val selected = cfg.accent == preset
            val sizePx = (if (selected) 36 else 30) * dp
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(preset.primaryHex)
                    setStroke(
                        if (selected) (3f * dp).toInt() else (1.2f * dp).toInt(),
                        if (selected) Color.WHITE else Color.argb(100, 255, 255, 255)
                    )
                }
                layoutParams = LinearLayout.LayoutParams(sizePx.toInt(), sizePx.toInt()).apply {
                    marginEnd = (12 * dp).toInt()
                }
                applyLiquidPressEffect()
                setOnClickListener {
                    PersonalizationManager.setAccent(this@AppearanceActivity, preset)
                    renderAllControls()
                }
            }
            binding.accentSwatchesRow.addView(swatch)
        }

        // 4. Frost Level Row
        binding.frostOptionsRow.removeAllViews()
        val frostLabels = listOf(
            getString(R.string.frost_clear),
            getString(R.string.frost_balanced),
            getString(R.string.frost_heavy)
        )
        frostLabels.forEachIndexed { idx, label ->
            val pill = buildPill(
                text = label,
                selected = cfg.frostLevel == idx,
                weight = 1f,
                onClick = {
                    PersonalizationManager.setFrostLevel(this, idx)
                    renderAllControls()
                }
            )
            binding.frostOptionsRow.addView(pill)
        }

        // Corner Radius Row
        binding.cornerRadiusRow.removeAllViews()
        val radii = listOf(18f to "18dp", 24f to "24dp", 30f to "30dp")
        radii.forEach { (radVal, label) ->
            val selected = kotlin.math.abs(cfg.cornerRadiusDp - radVal) < 2f
            val pill = buildPill(
                text = label,
                selected = selected,
                weight = 1f,
                onClick = {
                    PersonalizationManager.setCornerRadiusDp(this, radVal)
                    renderAllControls()
                }
            )
            binding.cornerRadiusRow.addView(pill)
        }

        LiquidGlassHelper.refreshTree(binding.appearanceContent)
    }

    private fun buildPill(
        text: String,
        selected: Boolean,
        weight: Float = 0f,
        onClick: () -> Unit,
    ): TextView {
        val dp = resources.displayMetrics.density
        val cfg = PersonalizationManager.getConfig(this)
        val boldFont = ResourcesCompat.getFont(this, R.font.pjs_bold)
        return TextView(this).apply {
            this.text = text
            textSize = 11.5f
            typeface = boldFont
            gravity = Gravity.CENTER
            LiquidGlassHelper.tagAndApply(
                this,
                if (selected) LiquidGlassDrawable.Kind.PILL_ACTIVE else LiquidGlassDrawable.Kind.PILL_MUTED
            )
            setTextColor(if (selected) Color.WHITE else cfg.primaryTextColor(this@AppearanceActivity))
            setPadding((13 * dp).toInt(), (8 * dp).toInt(), (13 * dp).toInt(), (8 * dp).toInt())
            layoutParams = if (weight > 0f) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    marginEnd = (6 * dp).toInt()
                }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * dp).toInt()
                }
            }
            applyLiquidPressEffect()
            setOnClickListener { onClick() }
        }
    }

    private fun pinWidget(provider: ComponentName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(provider, null, null)
                return
            }
        }
        Toast.makeText(this, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
    }
}
