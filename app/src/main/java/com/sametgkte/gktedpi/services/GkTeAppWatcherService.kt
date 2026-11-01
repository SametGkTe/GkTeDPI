package com.sametgkte.gktedpi.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.sametgkte.gktedpi.api.ExternalApiActivity
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.shizuku.ShizukuBridgeManager
import com.sametgkte.gktedpi.smart.AppTriggerManager

/**
 * Sıfır CPU Tüketimli Olay Güdümlü Uygulama Tetikleyici (`AccessibilityService`).
 *
 * `canRetrieveWindowContent = false` ile çalışır: ekran içeriğini veya yazıları ASLA okumaz;
 * yalnızca ön plana geçen uygulamanın paket adını (`TYPE_WINDOW_STATE_CHANGED`) dinleyerek
 * hedef uygulama açıldığında GkTeDPI'ı otomatik başlatır, çıkıldığında gecikmeli olarak uyutur.
 */
class GkTeAppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "GkTeAppWatcher"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSleepRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!AppTriggerManager.isEnabled(this)) return

        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == packageName || AppTriggerManager.isTransientSystemPackage(pkg)) {
            return
        }

        val watched = AppTriggerManager.getWatchedPackages(this)
        if (pkg in watched) {
            cancelPendingSleep()
            if (appStatus.first != AppStatus.Running) {
                Log.i(TAG, "Watched app opened ($pkg) -> waking GkTeDPI")
                AppTriggerManager.autoStartedCurrentSession = true
                wakeTunnel()
            }
        } else {
            val delaySec = AppTriggerManager.getStopDelaySeconds(this)
            if (delaySec > 0 &&
                AppTriggerManager.autoStartedCurrentSession &&
                appStatus.first == AppStatus.Running
            ) {
                scheduleSleep(delaySec * 1000L)
            }
        }
    }

    override fun onInterrupt() {
        cancelPendingSleep()
    }

    override fun onDestroy() {
        cancelPendingSleep()
        super.onDestroy()
    }

    private fun wakeTunnel() {
        when (AppTriggerManager.getTriggerConnectMode(this)) {
            "shizuku" -> {
                val res = ShizukuBridgeManager.enableDualVpnSystemProxy(this)
                if (res.isFailure) {
                    ServiceManager.start(this, Mode.Proxy)
                }
            }
            "proxy" -> ServiceManager.start(this, Mode.Proxy)
            else -> {
                if (VpnService.prepare(this) == null) {
                    ServiceManager.start(this, Mode.VPN)
                } else {
                    val bridge = Intent(com.sametgkte.gktedpi.sdk.GkTeDpiClient.ACTION_START).apply {
                        setPackage(packageName)
                        putExtra("mode", "vpn")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(bridge)
                }
            }
        }
    }

    private fun scheduleSleep(delayMs: Long) {
        if (pendingSleepRunnable != null) return
        val task = Runnable {
            pendingSleepRunnable = null
            if (AppTriggerManager.autoStartedCurrentSession && appStatus.first == AppStatus.Running) {
                Log.i(TAG, "Auto-sleep grace period elapsed -> stopping GkTeDPI")
                AppTriggerManager.autoStartedCurrentSession = false
                if (ShizukuBridgeManager.getState(this).systemProxyActive) {
                    ShizukuBridgeManager.disableDualVpnSystemProxy(this, alsoStopProxyService = true)
                } else {
                    ServiceManager.stop(this)
                }
            }
        }
        pendingSleepRunnable = task
        handler.postDelayed(task, delayMs)
    }

    private fun cancelPendingSleep() {
        pendingSleepRunnable?.let { handler.removeCallbacks(it) }
        pendingSleepRunnable = null
    }
}
