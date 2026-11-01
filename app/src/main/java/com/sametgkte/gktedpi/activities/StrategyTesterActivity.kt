package com.sametgkte.gktedpi.activities

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.adapters.StrategyTestAdapter
import com.sametgkte.gktedpi.adapters.StrategyTestResult
import com.sametgkte.gktedpi.core.ByeDpiProxy
import com.sametgkte.gktedpi.core.ByeDpiProxyCmdPreferences
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.databinding.ActivityStrategyTesterBinding
import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.services.ServiceManager
import com.sametgkte.gktedpi.services.appStatus
import com.sametgkte.gktedpi.ui.applyLiquidPressEffect
import com.sametgkte.gktedpi.utility.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

class StrategyTesterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrategyTesterBinding
    private lateinit var adapter: StrategyTestAdapter
    private var testJob: Job? = null

    companion object {
        private const val TEST_PORT = 1089
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityStrategyTesterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.sametgkte.gktedpi.ui.LiquidGlassHelper.upgradeScreen(binding.testerContent)

        ViewCompat.setOnApplyWindowInsetsListener(binding.testerContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, bars.top + (pad / 2), pad, bars.bottom + (pad / 2))
            insets
        }

        val isTr = Locale.getDefault().language == "tr"
        adapter = StrategyTestAdapter { preset ->
            ProfileManager.applyPresetToActive(this, preset)
            Toast.makeText(
                this,
                getString(R.string.strategy_applied, preset.localizedName(isTr)),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = adapter

        binding.backButton.applyLiquidPressEffect()
        binding.backButton.setOnClickListener { finish() }

        binding.startTestButton.applyLiquidPressEffect()
        binding.startTestButton.setOnClickListener {
            if (testJob?.isActive == true) {
                stopTesting()
            } else {
                startTesting()
            }
        }
    }

    private fun startTesting() {
        // Ensure active service is paused because native ByeDPI uses process-wide params struct
        if (appStatus.first == AppStatus.Running) {
            ServiceManager.stop(this)
        }

        val targets = binding.targetsEditText.text?.toString()
            .orEmpty()
            .split(",", "\n", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            .ifEmpty { listOf("https://discord.com", "https://www.cloudflare.com") }

        val collected = mutableListOf<StrategyTestResult>()
        adapter.submitList(emptyList())
        binding.startTestButton.setText(R.string.tester_stop)

        val isTr = Locale.getDefault().language == "tr"
        val presets = BuiltInPresets.ALL

        testJob = lifecycleScope.launch {
            delay(250)
            for ((idx, preset) in presets.withIndex()) {
                if (!isActive) break
                binding.testerProgressText.text = getString(
                    R.string.tester_running_item,
                    idx + 1,
                    presets.size,
                    preset.localizedName(isTr)
                )

                val result = evaluatePreset(preset.commandArgs, targets)
                collected.add(
                    StrategyTestResult(
                        preset = preset,
                        successCount = result.first,
                        totalTargets = targets.size,
                        avgLatencyMs = result.second,
                    )
                )

                val sorted = collected.sortedWith(
                    compareByDescending<StrategyTestResult> { it.successCount }
                        .thenBy { if (it.avgLatencyMs > 0) it.avgLatencyMs else Long.MAX_VALUE }
                )
                adapter.submitList(sorted)
            }

            binding.testerProgressText.setText(R.string.tester_done)
            binding.startTestButton.setText(R.string.tester_start)
        }
    }

    private suspend fun evaluatePreset(cmdArgs: String, targets: List<String>): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val proxy = ByeDpiProxy()
            val prefs = ByeDpiProxyCmdPreferences(
                cmd = cmdArgs,
                fallbackIp = "127.0.0.1",
                fallbackPort = TEST_PORT
            )
            val runner = launch(Dispatchers.IO) {
                proxy.startProxy(prefs)
            }

            delay(180)
            val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TEST_PORT))

            val outcomes = coroutineScope {
                targets.map { urlStr ->
                    async {
                        val t0 = SystemClock.elapsedRealtime()
                        try {
                            val conn = (URL(urlStr).openConnection(socks) as HttpURLConnection).apply {
                                requestMethod = "GET"
                                instanceFollowRedirects = false
                                connectTimeout = 3500
                                readTimeout = 3500
                                setRequestProperty("User-Agent", "Mozilla/5.0 GkTeDPI/2.0")
                            }
                            val code = conn.responseCode
                            conn.disconnect()
                            if (code in 100..599) {
                                true to (SystemClock.elapsedRealtime() - t0)
                            } else {
                                false to 0L
                            }
                        } catch (_: Exception) {
                            false to 0L
                        }
                    }
                }.awaitAll()
            }

            try {
                proxy.stopProxy()
            } catch (_: Exception) {
            }
            runner.join()

            val passed = outcomes.filter { it.first }
            val avgMs = if (passed.isNotEmpty()) {
                passed.sumOf { it.second } / passed.size
            } else {
                0L
            }
            passed.size to avgMs
        }

    private fun stopTesting() {
        testJob?.cancel()
        testJob = null
        binding.startTestButton.setText(R.string.tester_start)
        binding.testerProgressText.setText(R.string.tester_ready_hint)
    }

    override fun onDestroy() {
        testJob?.cancel()
        super.onDestroy()
    }
}
