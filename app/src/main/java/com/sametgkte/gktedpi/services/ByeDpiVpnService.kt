package com.sametgkte.gktedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.activities.MainActivity
import com.sametgkte.gktedpi.core.ByeDpiProxy
import com.sametgkte.gktedpi.core.ByeDpiProxyPreferences
import com.sametgkte.gktedpi.core.TProxyService
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
import com.sametgkte.gktedpi.data.SplitTunnelMode
import com.sametgkte.gktedpi.profiles.ProfileManager
import com.sametgkte.gktedpi.utility.RoutingUtils
import com.sametgkte.gktedpi.utility.checkIp
import com.sametgkte.gktedpi.utility.createConnectionNotification
import com.sametgkte.gktedpi.utility.formatSpeed
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.getStringNotNull
import com.sametgkte.gktedpi.utility.registerNotificationChannel
import com.sametgkte.gktedpi.utility.splitTunnelApps
import com.sametgkte.gktedpi.utility.splitTunnelMode
import com.sametgkte.gktedpi.vpn.RealVpnNodeManager
import com.sametgkte.gktedpi.vpn.RealVpnRuntimeSnapshot
import com.sametgkte.gktedpi.vpn.RealVpnSocksServer
import com.sametgkte.gktedpi.vpn.TunnelEngine
import com.sametgkte.gktedpi.vpn.VpnCountries
import com.sametgkte.gktedpi.vpn.VpnEngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Locale

class ByeDpiVpnService : LifecycleVpnService() {
    private var byeDpiProxy = ByeDpiProxy()
    private var realVpnServer: RealVpnSocksServer? = null
    private var activeEngine: TunnelEngine = TunnelEngine.LOCAL_DPI
    private var proxyJob: Job? = null
    private var statsJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var activeConfigFile: File? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "GkTeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
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

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        lifecycleScope.launch { stop() }
    }

    private suspend fun restart() {
        Log.i(TAG, "Restarting VPN with updated settings")
        mutex.withLock {
            stopping = true
            try {
                stopStatsLoop()
                stopTun2Socks()
                stopBackendInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping during restart", e)
            } finally {
                stopping = false
            }
        }
        delay(150)
        status = ServiceStatus.Disconnected
        start()
    }

    private suspend fun start() {
        Log.i(TAG, "Starting VPN")

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            return
        }

        val engine = VpnEngineState.getEngine(this)
        activeEngine = engine

        try {
            startForeground(engine)
            mutex.withLock {
                if (engine == TunnelEngine.REAL_VPN) {
                    val requestedCountry = VpnEngineState.getSelectedCountryCode(this@ByeDpiVpnService)
                    VpnEngineState.updateSnapshot(
                        RealVpnRuntimeSnapshot(
                            connecting = true,
                            connected = false,
                            countryCode = requestedCountry,
                            fatihStealthActive = VpnEngineState.isFatihStealthEnabled(this@ByeDpiVpnService),
                        )
                    )
                    val verifiedPool = RealVpnNodeManager.selectOptimalPool(
                        context = this@ByeDpiVpnService,
                        countryOrAuto = requestedCountry,
                        protectSocket = { sock -> protect(sock) },
                    )
                    val relayPort = findAvailableLoopbackPort(1086)
                    val server = RealVpnSocksServer(
                        context = this@ByeDpiVpnService,
                        listenPort = relayPort,
                        requestedCountryOrAuto = requestedCountry,
                        initialPool = verifiedPool,
                        protectSocket = { sock -> protect(sock) },
                    )
                    server.start()
                    realVpnServer = server

                    startTun2Socks(
                        overrideProxyAddress = "127.0.0.1",
                        overridePort = relayPort,
                        isRealVpn = true,
                    )
                    VpnEngineState.updateSnapshot(
                        RealVpnRuntimeSnapshot(
                            connecting = false,
                            connected = true,
                            countryCode = verifiedPool.resolvedCountryCode,
                            serverLabel = verifiedPool.primary.label,
                            exitIp = verifiedPool.exitIp,
                            latencyMs = verifiedPool.latencyMs,
                            protocolBadge = verifiedPool.primary.protocol.badge,
                            standbyCount = verifiedPool.standbys.size,
                            fatihStealthActive = verifiedPool.fatihStealthActive,
                        )
                    )
                } else {
                    VpnEngineState.resetSnapshot(VpnEngineState.getSelectedCountryCode(this@ByeDpiVpnService))
                    startByeDpiProxy()
                    delay(120)
                    startTun2Socks(
                        overrideProxyAddress = null,
                        overridePort = null,
                        isRealVpn = false,
                    )
                }
                startStatsLoop()
            }
            updateStatus(ServiceStatus.Connected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            VpnEngineState.resetSnapshot(VpnEngineState.getSelectedCountryCode(this))
            updateStatus(ServiceStatus.Failed, e.message)
            stop()
        }
    }

    private fun startForeground(engine: TunnelEngine) {
        val isTr = Locale.getDefault().language == "tr"
        val label = if (engine == TunnelEngine.REAL_VPN) {
            val country = VpnCountries.byCode(VpnEngineState.getSelectedCountryCode(this))
            "${country.flag} ${country.localizedName(isTr)}"
        } else {
            ProfileManager.getActiveProfile(this).name
        }
        val notification: Notification = createNotification(
            getString(R.string.vpn_notification_active_profile, label)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping VPN")

        mutex.withLock {
            stopping = true
            try {
                stopStatsLoop()
                stopTun2Socks()
                stopBackendInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            } finally {
                stopping = false
            }
        }

        VpnEngineState.resetSnapshot(VpnEngineState.getSelectedCountryCode(this))
        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private fun startByeDpiProxy() {
        Log.i(TAG, "Starting native ByeDPI proxy")

        if (proxyJob != null) {
            throw IllegalStateException("Proxy job already active")
        }

        byeDpiProxy = ByeDpiProxy()
        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = byeDpiProxy.startProxy(preferences)

            withContext(Dispatchers.Main) {
                if (code != 0) {
                    Log.e(TAG, "Proxy stopped with error code $code")
                    if (!stopping) {
                        updateStatus(ServiceStatus.Failed)
                        stop()
                    }
                } else {
                    if (!stopping) {
                        stop()
                    }
                }
            }
        }
    }

    private suspend fun stopBackendInternal() {
        realVpnServer?.stop()
        realVpnServer = null

        if (proxyJob != null) {
            try {
                byeDpiProxy.stopProxy()
            } catch (_: Throwable) {
            }
            proxyJob?.join()
            proxyJob = null
        }
    }

    private fun findAvailableLoopbackPort(preferred: Int): Int {
        return try {
            ServerSocket().use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferred))
                s.localPort
            }
        } catch (_: Throwable) {
            ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }
    }

    private fun startTun2Socks(
        overrideProxyAddress: String?,
        overridePort: Int?,
        isRealVpn: Boolean,
    ) {
        if (tunFd != null) {
            throw IllegalStateException("TUN fd already open")
        }

        val prefs = getPreferences()
        val proxyAddress = overrideProxyAddress ?: prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1").let {
            if (it == "0.0.0.0" || it == "::") "127.0.0.1" else it
        }
        val port = overridePort ?: (prefs.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080)
        val configuredMtu = (prefs.getString("vpn_mtu", null)?.toIntOrNull() ?: 8500).coerceIn(1280, 9000)
        val mtu = if (isRealVpn) minOf(configuredMtu, 1420) else configuredMtu
        val udpMode = if (isRealVpn) {
            "tcp"
        } else {
            prefs.getStringNotNull("tun2socks_udp_mode", "udp").let {
                if (it == "tcp") "tcp" else "udp"
            }
        }
        val pipelineLine = if (isRealVpn) "\n        |   pipeline: 'true'" else ""
        val stackSize = (prefs.getString("tun2socks_stack_size", null)?.toIntOrNull() ?: 81920)
            .coerceIn(16384, 524288)

        val primaryDns = prefs.getStringNotNull("dns_ip", "1.1.1.1").trim()
        val secondaryDns = prefs.getStringNotNull("dns_ip_secondary", "1.0.0.1").trim()
        val ipv6 = prefs.getBoolean("ipv6_enable", false)
        val ipv6Dns = prefs.getStringNotNull("dns_ipv6", "2606:4700:4700::1111").trim()
        val lanBypass = prefs.getBoolean("lan_bypass", true)

        val tun2socksConfig = """
        | misc:
        |   task-stack-size: $stackSize
        | tunnel:
        |   mtu: $mtu
        | socks5:
        |   mtu: $mtu
        |   address: $proxyAddress
        |   port: $port
        |   udp: '$udpMode'$pipelineLine
        """.trimMargin("| ")

        val configPath = File.createTempFile("gktedpi_tproxy_", ".yml", cacheDir).apply {
            writeText(tun2socksConfig)
        }
        activeConfigFile = configPath

        val fd = createBuilder(
            primaryDns = primaryDns,
            secondaryDns = secondaryDns,
            ipv6 = ipv6,
            ipv6Dns = ipv6Dns,
            mtu = mtu,
            lanBypass = lanBypass,
        ).establish() ?: throw IllegalStateException("VPN tunnel establish returned null")

        this.tunFd = fd
        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)
        Log.i(TAG, "Tun2Socks started (RealVPN=$isRealVpn, MTU=$mtu, LANBypass=$lanBypass, UDP=$udpMode)")
    }

    private fun stopTun2Socks() {
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "TProxyStopService warning", e)
        }

        try {
            activeConfigFile?.delete()
            activeConfigFile = null
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to remove temporary config", e)
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed closing tunFd", e)
        }
        tunFd = null
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch(Dispatchers.Default) {
            var prevTx = 0L
            var prevRx = 0L
            var initialized = false
            val showInNotif = getPreferences().getBoolean("live_notification_stats", true)
            val isTr = Locale.getDefault().language == "tr"
            val notificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getSystemService(NotificationManager::class.java)
            } else null

            while (isActive) {
                val raw = try {
                    TProxyService.TProxyGetStats()
                } catch (_: Throwable) {
                    null
                }
                if (raw != null && raw.size >= 4) {
                    val txBytes = raw[1].coerceAtLeast(0L)
                    val rxBytes = raw[3].coerceAtLeast(0L)
                    val txSpeed = if (initialized) (txBytes - prevTx).coerceAtLeast(0L) else 0L
                    val rxSpeed = if (initialized) (rxBytes - prevRx).coerceAtLeast(0L) else 0L
                    prevTx = txBytes
                    prevRx = rxBytes
                    initialized = true

                    updateTrafficStats(
                        TrafficStats(
                            txBytes = txBytes,
                            rxBytes = rxBytes,
                            txSpeedBps = txSpeed,
                            rxSpeedBps = rxSpeed,
                        )
                    )

                    if (showInNotif && notificationManager != null && status == ServiceStatus.Connected) {
                        val headerTitle = if (activeEngine == TunnelEngine.REAL_VPN) {
                            val snap = VpnEngineState.snapshot
                            val c = VpnCountries.byCode(snap.countryCode)
                            val ipPart = if (snap.exitIp.isNotBlank()) " (${snap.exitIp})" else ""
                            val stealthTag = if (snap.fatihStealthActive) " • 🏫 Stealth" else ""
                            "${c.flag} ${c.localizedName(isTr)}$ipPart$stealthTag"
                        } else {
                            ProfileManager.getActiveProfile(this@ByeDpiVpnService).name
                        }
                        val text = "$headerTitle • ↓ ${formatSpeed(rxSpeed)}  ↑ ${formatSpeed(txSpeed)}"
                        notificationManager.notify(
                            FOREGROUND_SERVICE_ID,
                            createNotification(text)
                        )
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun stopStatsLoop() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        com.sametgkte.gktedpi.smart.SmartSniEngine.decoratePreferences(
            this,
            ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())
        )

    private fun updateStatus(newStatus: ServiceStatus, reason: String? = null) {
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
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        ).apply {
            setPackage(packageName)
            putExtra(SENDER, Sender.VPN.ordinal)
            putExtra("sender", reason ?: "VPN")
        }
        sendBroadcast(intent)
        com.sametgkte.gktedpi.widget.GkTeWidgetUpdater.updateAll(this)
        com.sametgkte.gktedpi.api.GkTeVpnApiBridge.broadcastStateChange(this)
    }

    private fun createNotification(contentText: String): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            contentText,
            ByeDpiVpnService::class.java,
        )

    private fun createBuilder(
        primaryDns: String,
        secondaryDns: String,
        ipv6: Boolean,
        ipv6Dns: String,
        mtu: Int,
        lanBypass: Boolean,
    ): Builder {
        val builder = Builder()
        builder.setSession("GkTeDPI")
        builder.setMtu(mtu)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        val tunIpv4 = getPreferences().getStringNotNull("tun_ipv4", "198.18.0.1")
            .takeIf { checkIp(it) } ?: "198.18.0.1"
        builder.addAddress(tunIpv4, 32)

        if (lanBypass) {
            for (route in RoutingUtils.LAN_BYPASS_IPV4_ROUTES) {
                builder.addRoute(route.address, route.prefixLength)
            }
            if (primaryDns.isNotBlank() && checkIp(primaryDns) && !primaryDns.contains(":")) {
                try {
                    builder.addRoute(primaryDns, 32)
                } catch (_: Exception) {
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
            builder.addRoute("::", 0)
            if (ipv6Dns.isNotBlank() && checkIp(ipv6Dns)) {
                try {
                    builder.addDnsServer(ipv6Dns)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid IPv6 DNS: $ipv6Dns", e)
                }
            }
        }

        if (primaryDns.isNotBlank() && checkIp(primaryDns)) {
            try {
                builder.addDnsServer(primaryDns)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid Primary DNS: $primaryDns", e)
            }
        }

        if (secondaryDns.isNotBlank() && secondaryDns != primaryDns && checkIp(secondaryDns)) {
            try {
                builder.addDnsServer(secondaryDns)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid Secondary DNS: $secondaryDns", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applySplitTunneling(builder)

        return builder
    }

    private fun applySplitTunneling(builder: Builder) {
        val prefs = getPreferences()
        val splitMode = prefs.splitTunnelMode()
        val selectedApps = prefs.splitTunnelApps()

        when (splitMode) {
            SplitTunnelMode.Disabled -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }

            SplitTunnelMode.Whitelist -> {
                val validApps = selectedApps.filter { it != applicationContext.packageName }
                if (validApps.isEmpty()) {
                    builder.addDisallowedApplication(applicationContext.packageName)
                } else {
                    for (pkg in validApps) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: PackageManager.NameNotFoundException) {
                            Log.w(TAG, "Whitelisted package not installed: $pkg")
                        }
                    }
                }
            }

            SplitTunnelMode.Blacklist -> {
                builder.addDisallowedApplication(applicationContext.packageName)
                for (pkg in selectedApps) {
                    if (pkg == applicationContext.packageName) continue
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Blacklisted package not installed: $pkg")
                    }
                }
            }
        }
    }
}
