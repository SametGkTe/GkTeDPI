package com.sametgkte.gktedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.databinding.ActivityLogsBinding
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.utility.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    private val saveLogsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogsText()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(logs.toByteArray())
                }
            }
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val json = ProfileManager.exportFullBackupJson(this@LogsActivity)
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LogsActivity,
                        R.string.backup_exported_ok,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                val ok = !raw.isNullOrBlank() && ProfileManager.importFullBackupJson(this@LogsActivity, raw)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(
                            this@LogsActivity,
                            R.string.backup_imported_ok,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appStatus.first == AppStatus.Running) {
                            ServiceManager.restartIfRunning(this@LogsActivity)
                        }
                    } else {
                        Toast.makeText(
                            this@LogsActivity,
                            R.string.backup_import_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.sametgkte.gktedpi.ui.LiquidGlassHelper.upgradeScreen(binding.logsContent)

        ViewCompat.setOnApplyWindowInsetsListener(binding.logsContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        binding.backButton.applyLiquidPressEffect()
        binding.backButton.setOnClickListener { finish() }

        binding.refreshLogsBtn.applyLiquidPressEffect()
        binding.refreshLogsBtn.setOnClickListener { refreshLogs() }

        binding.saveLogsFileBtn.applyLiquidPressEffect()
        binding.saveLogsFileBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "gktedpi.log")
            }
            saveLogsLauncher.launch(intent)
        }

        binding.exportBackupBtn.applyLiquidPressEffect()
        binding.exportBackupBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "gktedpi-backup.json")
            }
            exportBackupLauncher.launch(intent)
        }

        binding.importBackupBtn.applyLiquidPressEffect()
        binding.importBackupBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importBackupLauncher.launch(intent)
        }

        refreshLogs()
    }

    private fun refreshLogs() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { collectLogsText() }
            binding.logOutputText.text = text
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun collectLogsText(): String = try {
        Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-t", "350"))
            .inputStream.bufferedReader()
            .use { it.readText() }
            .ifBlank { "GkTeDPI log buffer empty." }
    } catch (e: Exception) {
        "Logcat error: ${e.message}"
    }
}
