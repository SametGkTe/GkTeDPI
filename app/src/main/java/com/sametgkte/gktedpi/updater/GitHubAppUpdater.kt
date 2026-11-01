package com.sametgkte.gktedpi.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.sametgkte.gktedpi.BuildConfig
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.utility.getPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Self-contained GitHub Releases Auto & Manual Updater for SametGkTe/GkTeDPI.
 * Checks https://api.github.com/repos/SametGkTe/GkTeDPI/releases/latest,
 * downloads the release .apk with live progress, verifies APK magic header,
 * and triggers Android's PackageInstaller via FileProvider.
 */
object GitHubAppUpdater {

    const val GITHUB_OWNER = "SametGkTe"
    const val GITHUB_REPO = "GkTeDPI"
    const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val REPO_HTML_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val PREF_AUTO_CHECK_UPDATES = "auto_check_github_updates"

    @Volatile
    private var checkedOnceInProcess: Boolean = false

    @Volatile
    private var pendingInstallApkFile: File? = null

    data class ReleaseInfo(
        val tagName: String,
        val cleanVersion: String,
        val title: String,
        val changelog: String,
        val apkDownloadUrl: String?,
        val apkName: String?,
        val apkSizeBytes: Long,
        val htmlUrl: String,
    )

    sealed class CheckOutcome {
        data class UpdateAvailable(val release: ReleaseInfo) : CheckOutcome()
        data class UpToDate(val currentVersion: String, val remoteVersion: String?) : CheckOutcome()
        data class Error(val reason: String) : CheckOutcome()
    }

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getPreferences().getBoolean(PREF_AUTO_CHECK_UPDATES, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit().putBoolean(PREF_AUTO_CHECK_UPDATES, enabled).apply()
    }

    /**
     * Pure semantic version comparator (unit-tested).
     * Returns true strictly when remoteTag represents a higher version than currentVersion.
     */
    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remoteParts = parseNumericVersionParts(remoteTag)
        val localParts = parseNumericVersionParts(currentVersion)
        if (remoteParts.isEmpty() || localParts.isEmpty()) return false
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    internal fun parseNumericVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(".")
            .mapNotNull { token ->
                val digits = token.takeWhile { it.isDigit() }
                digits.toIntOrNull()
            }
    }

    internal fun parseReleaseJson(jsonText: String, supportedAbis: List<String>): ReleaseInfo {
        val obj = JSONObject(jsonText)
        val tagName = obj.optString("tag_name", "").trim()
        val name = obj.optString("name", tagName).ifBlank { tagName }
        val body = obj.optString("body", "").trim()
        val htmlUrl = obj.optString("html_url", "$REPO_HTML_URL/releases")

        var selectedUrl: String? = null
        var selectedName: String? = null
        var selectedSize = 0L

        val assets = obj.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            val apkCandidates = mutableListOf<Triple<String, String, Long>>()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                if (assetName.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                    apkCandidates.add(Triple(assetName, dlUrl, size))
                }
            }

            val primaryAbi = supportedAbis.firstOrNull()?.lowercase(Locale.US)
            val chosen = apkCandidates.firstOrNull { (n, _, _) ->
                n.contains("universal", ignoreCase = true) ||
                    (!n.contains("arm64") && !n.contains("armeabi") && !n.contains("x86"))
            } ?: apkCandidates.firstOrNull { (n, _, _) ->
                primaryAbi != null && n.lowercase(Locale.US).contains(primaryAbi)
            } ?: apkCandidates.firstOrNull()

            if (chosen != null) {
                selectedName = chosen.first
                selectedUrl = chosen.second
                selectedSize = chosen.third
            }
        }

        return ReleaseInfo(
            tagName = tagName,
            cleanVersion = tagName.removePrefix("v").removePrefix("V"),
            title = name,
            changelog = body,
            apkDownloadUrl = selectedUrl,
            apkName = selectedName,
            apkSizeBytes = selectedSize,
            htmlUrl = htmlUrl,
        )
    }

    suspend fun checkLatestRelease(): CheckOutcome = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GkTeDPI/${BuildConfig.VERSION_NAME} (Android)")
            }
            val code = conn.responseCode
            if (code == 404) {
                // Repository exists or has no published release tag yet -> treat as up-to-date
                return@withContext CheckOutcome.UpToDate(BuildConfig.VERSION_NAME, null)
            }
            if (code !in 200..299) {
                return@withContext CheckOutcome.Error("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val release = parseReleaseJson(text, abis)
            if (release.tagName.isNotBlank() && isNewerVersion(release.tagName, BuildConfig.VERSION_NAME)) {
                CheckOutcome.UpdateAvailable(release)
            } else {
                CheckOutcome.UpToDate(BuildConfig.VERSION_NAME, release.cleanVersion.ifBlank { null })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CheckOutcome.Error(t.localizedMessage ?: "Bağlantı hatası")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Runs once per app launch on startup if auto-update check is enabled.
     */
    fun checkOnStartupIfEnabled(activity: Activity, scope: LifecycleCoroutineScope) {
        if (checkedOnceInProcess || !isAutoCheckEnabled(activity)) return
        checkedOnceInProcess = true
        scope.launch {
            val outcome = checkLatestRelease()
            if (!activity.isFinishing && !activity.isDestroyed && outcome is CheckOutcome.UpdateAvailable) {
                showUpdateAvailableDialog(activity, scope, outcome.release)
            }
        }
    }

    /**
     * Triggered manually by user from Settings or About screen.
     */
    fun checkManuallyWithUi(activity: Activity, scope: LifecycleCoroutineScope) {
        Toast.makeText(activity, R.string.update_checking_toast, Toast.LENGTH_SHORT).show()
        scope.launch {
            val outcome = checkLatestRelease()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (outcome) {
                is CheckOutcome.UpdateAvailable -> showUpdateAvailableDialog(activity, scope, outcome.release)
                is CheckOutcome.UpToDate -> {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_up_to_date_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_up_to_date_body,
                                BuildConfig.VERSION_NAME
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                is CheckOutcome.Error -> {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_check_failed_fmt, outcome.reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun showUpdateAvailableDialog(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: ReleaseInfo,
    ) {
        val sizeStr = if (release.apkSizeBytes > 0L) {
            String.format(Locale.US, " (%.1f MB)", release.apkSizeBytes / 1_048_576.0)
        } else ""
        val notes = release.changelog.ifBlank {
            activity.getString(R.string.update_default_notes)
        }
        val msg = "${activity.getString(R.string.update_new_version_fmt, release.tagName, BuildConfig.VERSION_NAME)}$sizeStr\n\n$notes"

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(msg)
            .setNegativeButton(R.string.update_later_btn, null)

        if (!release.apkDownloadUrl.isNullOrBlank()) {
            builder.setPositiveButton(R.string.update_download_install_btn) { _, _ ->
                downloadAndInstallApk(activity, scope, release)
            }
        } else {
            builder.setPositiveButton(R.string.update_open_github_btn) { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
            }
        }
        builder.show()
    }

    private fun downloadAndInstallApk(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: ReleaseInfo,
    ) {
        val downloadUrl = release.apkDownloadUrl ?: return
        val dp = activity.resources.displayMetrics.density

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (16 * dp).toInt(), (22 * dp).toInt(), (10 * dp).toInt())
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val statusText = TextView(activity).apply {
            text = "0%"
            textSize = 13f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        container.addView(progressBar)
        container.addView(statusText)

        var downloadJob: Job? = null
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading_title)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel_button) { d, _ ->
                downloadJob?.cancel()
                d.dismiss()
            }
            .create()
        progressDialog.show()

        downloadJob = scope.launch {
            val resultFile = withContext(Dispatchers.IO) {
                try {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    val target = File(dir, "GkTeDPI-${release.cleanVersion}.apk")
                    var currentUrl = downloadUrl
                    var redirects = 0
                    var conn: HttpURLConnection

                    while (true) {
                        ensureActive()
                        conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 15000
                            readTimeout = 20000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "GkTeDPI/${BuildConfig.VERSION_NAME} (Android)")
                        }
                        val code = conn.responseCode
                        if (code in 301..308) {
                            val loc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (loc.isNullOrBlank() || ++redirects > 6) return@withContext null
                            currentUrl = loc
                            continue
                        }
                        if (code !in 200..299) {
                            conn.disconnect()
                            return@withContext null
                        }
                        break
                    }

                    val total = conn.contentLengthLong.coerceAtLeast(release.apkSizeBytes)
                    conn.inputStream.use { input ->
                        FileOutputStream(target).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var downloaded = 0L
                            var lastUiPercent = -1
                            while (true) {
                                ensureActive()
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                                if (pct != lastUiPercent) {
                                    lastUiPercent = pct
                                    withContext(Dispatchers.Main) {
                                        if (pct >= 0) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = pct
                                            statusText.text = String.format(
                                                Locale.US,
                                                "%d%%  (%.1f / %.1f MB)",
                                                pct,
                                                downloaded / 1_048_576.0,
                                                total / 1_048_576.0
                                            )
                                        } else {
                                            progressBar.isIndeterminate = true
                                            statusText.text = String.format(
                                                Locale.US,
                                                "%.1f MB indirildi...",
                                                downloaded / 1_048_576.0
                                            )
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    conn.disconnect()

                    // Verify valid ZIP/APK header ('P','K',0x03,0x04)
                    if (!hasValidApkHeader(target)) {
                        target.delete()
                        return@withContext null
                    }
                    target
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }

            if (progressDialog.isShowing) progressDialog.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (resultFile != null && resultFile.exists()) {
                triggerPackageInstall(activity, resultFile)
            } else {
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun hasValidApkHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        return try {
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) }
            magic[0] == 'P'.code.toByte() &&
                magic[1] == 'K'.code.toByte() &&
                magic[2] == 3.toByte() &&
                magic[3] == 4.toByte()
        } catch (_: Throwable) {
            false
        }
    }

    fun triggerPackageInstall(activity: Activity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallApkFile = apkFile
            Toast.makeText(activity, R.string.update_allow_install_perm_toast, Toast.LENGTH_LONG).show()
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(permIntent)
            return
        }

        pendingInstallApkFile = null
        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (t: Throwable) {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_check_failed_fmt, t.localizedMessage ?: "Installer"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Resumes pending APK installation automatically once user returns from granting
     * "Install unknown apps" permission.
     */
    fun resumePendingInstallIfPermitted(activity: Activity) {
        val pending = pendingInstallApkFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.canRequestPackageInstalls() &&
            pending.exists()
        ) {
            triggerPackageInstall(activity, pending)
        }
    }
}
