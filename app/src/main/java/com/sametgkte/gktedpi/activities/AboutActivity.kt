package com.sametgkte.gktedpi.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.BuildConfig
import com.sametgkte.gktedpi.databinding.ActivityAboutBinding
import com.sametgkte.gktedpi.ui.LiquidGlassDrawable
import com.sametgkte.gktedpi.ui.LiquidGlassHelper
import com.sametgkte.gktedpi.ui.PersonalizationManager
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.updater.GitHubAppUpdater
import com.sametgkte.gktedpi.utility.ThemeUtils

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    private val personalizationListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                binding.aboutAmbientBg.invalidate()
                LiquidGlassHelper.refreshTree(binding.aboutContent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.aboutContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        val isLight = PersonalizationManager.getConfig(this).isLightResolved(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }

        PersonalizationManager.addListener(personalizationListener)

        LiquidGlassHelper.tagAndApply(binding.aboutHeaderCard, LiquidGlassDrawable.Kind.HEADER)
        LiquidGlassHelper.tagAndApply(binding.aboutBackBtn, LiquidGlassDrawable.Kind.PILL_MUTED)
        LiquidGlassHelper.tagAndApply(binding.aboutHeroCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.aboutLogoBadge, LiquidGlassDrawable.Kind.ICON_SQUIRCLE)
        LiquidGlassHelper.tagAndApply(binding.btnAboutCheckUpdate, LiquidGlassDrawable.Kind.PILL_ACTIVE)
        LiquidGlassHelper.tagAndApply(binding.btnAboutGithub, LiquidGlassDrawable.Kind.PILL_MUTED)
        LiquidGlassHelper.tagAndApply(binding.aboutDescriptionCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.aboutTermsCard, LiquidGlassDrawable.Kind.CARD)
        LiquidGlassHelper.tagAndApply(binding.aboutSignatureCard, LiquidGlassDrawable.Kind.HEADER)

        binding.aboutVersionText.text = "v${BuildConfig.VERSION_NAME} • ${BuildConfig.APPLICATION_ID}"

        binding.aboutBackBtn.applyLiquidPressEffect()
        binding.aboutBackBtn.setOnClickListener { finish() }

        binding.btnAboutCheckUpdate.applyLiquidPressEffect()
        binding.btnAboutCheckUpdate.setOnClickListener {
            GitHubAppUpdater.checkManuallyWithUi(this, lifecycleScope)
        }

        binding.btnAboutGithub.applyLiquidPressEffect()
        binding.btnAboutGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubAppUpdater.REPO_HTML_URL)))
        }
    }

    override fun onResume() {
        super.onResume()
        GitHubAppUpdater.resumePendingInstallIfPermitted(this)
    }

    override fun onDestroy() {
        PersonalizationManager.removeListener(personalizationListener)
        super.onDestroy()
    }
}
