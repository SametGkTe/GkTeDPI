package com.sametgkte.gktedpi.services

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.core.ByeDpiProxy
import com.sametgkte.gktedpi.core.ByeDpiProxyPreferences
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.FAILED_BROADCAST
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.data.RESTART_ACTION
import com.sametgkte.gktedpi.data.SENDER
import com.sametgkte.gktedpi.data.STARTED_BROADCAST
import com.sametgkte.gktedpi.data.START_ACTION
import com.sametgkte.gktedpi.data.STOPPED_BROADCAST
import com.sametgkte.gktedpi.data.STOP_ACTION
import com.sametgkte.gktedpi.data.Sender
import com.sametgkte.gktedpi.data.ServiceStatus
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.shizuku.LocalHttpToSocksBridge
import com.sametgkte.gktedpi.shizuku.ShizukuBridgeManager
import com.sametgkte.gktedpi.smart.SmartSniEngine
import com.sametgkte.gktedpi.utility.createConnectionNotification
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.getStringNotNull
import com.sametgkte.gktedpi.utility.registerNotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var httpBridge: LocalHttpToSocksBridge? = null
    private val mutex = Mutex()
    private var stopping = false

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "GkTeDPIProxy"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            RESTART_ACTION -> {
                lifecycleScope.launch { restart() }
                START_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    private suspend fun restart() {
        mutex.withLock {
            stopping = true
            try {
                stopHttpBridge()
                stopProxy()
            } finally {
                stopping = false
            }
        }
        delay(120)
        status = ServiceStatus.Disconnected
        start()
    }

    private suspend fun start() {
        if (status == ServiceStatus.Connected) return

        try {
            startForeground()
            mutex.withLock {
                startProxy()
                delay(80)
                startHttpBridgeIfNeeded()
            }
            updateStatus(ServiceStatus.Connected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            updateStatus(ServiceStatus.Failed)
            stop()
        }
    }

    private fun startForeground() {
        val prefs = getPreferences()
        val ip = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val port = prefs.getStringNotNull("byedpi_proxy_port", "1080")
        val profile = ProfileManager.getActiveProfile(this).name
        val sysProxy = prefs.getBoolean(ShizukuBridgeManager.PREF_SYSTEM_PROXY_ACTIVE, false)
        val hotspot = prefs.getBoolean(ShizukuBridgeManager.PREF_HOTSPOT_SHARE_ACTIVE, false)
        val suffix = when {
            sysProxy -> "$profile • Shizuku Çift-VPN Köprüsü (:8118)"
            hotspot -> "$profile • Hotspot Paylaşım ($ip:$port / :8118)"
            else -> "$profile • SOCKS5 $ip:$port"
        }
        val notification: Notification = createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            suffix,
            ByeDpiProxyService::class.java,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        mutex.withLock {
            stopping = true
            try {
                stopHttpBridge()
                stopProxy()
                if (getPreferences().getBoolean(ShizukuBridgeManager.PREF_SYSTEM_PROXY_ACTIVE, false)) {
                    withContext(Dispatchers.IO) {
                        ShizukuBridgeManager.disableDualVpnSystemProxy(
                            this@ByeDpiProxyService,
                            alsoStopProxyService = false
                        )
                    }
                }
            } finally {
                stopping = false
            }
        }
        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private fun startProxy() {
        if (proxyJob != null) {
            throw IllegalStateException("Proxy job already active")
        }

        proxy = ByeDpiProxy()
        val preferences = SmartSniEngine.decoratePreferences(
            this,
            ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())
        )

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = proxy.startProxy(preferences)
            withContext(Dispatchers.Main) {
                if (code != 0 && !stopping) {
                    updateStatus(ServiceStatus.Failed)
                    stop()
                } else if (!stopping) {
                    updateStatus(ServiceStatus.Disconnected)
                }
            }
        }
    }

    private fun startHttpBridgeIfNeeded() {
        val prefs = getPreferences()
        val needBridge = prefs.getBoolean(ShizukuBridgeManager.PREF_HTTP_BRIDGE_ENABLED, false) ||
            prefs.getBoolean(ShizukuBridgeManager.PREF_SYSTEM_PROXY_ACTIVE, false) ||
            prefs.getBoolean(ShizukuBridgeManager.PREF_HOTSPOT_SHARE_ACTIVE, false)
        if (!needBridge) return

        val bindIp = if (prefs.getBoolean(ShizukuBridgeManager.PREF_HOTSPOT_SHARE_ACTIVE, false)) {
            "0.0.0.0"
        } else {
            "127.0.0.1"
        }
        val httpPort = prefs.getString(ShizukuBridgeManager.PREF_HTTP_BRIDGE_PORT, null)?.toIntOrNull()
            ?: ShizukuBridgeManager.DEFAULT_HTTP_BRIDGE_PORT
        val socksPort = prefs.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080

        stopHttpBridge()
        httpBridge = LocalHttpToSocksBridge(
            bindHost = bindIp,
            listenPort = httpPort,
            socksHost = "127.0.0.1",
            socksPort = socksPort,
        ).also { it.start() }
    }

    private fun stopHttpBridge() {
        httpBridge?.stop()
        httpBridge = null
    }

    private suspend fun stopProxy() {
        if (status == ServiceStatus.Disconnected && proxyJob == null) return
        proxy.stopProxy()
        proxyJob?.join()
        proxyJob = null
    }

    override fun onDestroy() {
        stopHttpBridge()
        super.onDestroy()
    }

    private fun updateStatus(newStatus: ServiceStatus) {
        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.Proxy
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        ).apply {
            setPackage(packageName)
            putExtra(SENDER, Sender.Proxy.ordinal)
        }
        sendBroadcast(intent)
        com.sametgkte.gktedpi.widget.GkTeWidgetUpdater.updateAll(this)
        com.sametgkte.gktedpi.api.GkTeVpnApiBridge.broadcastStateChange(this)
    }
}
