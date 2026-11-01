package com.sametgkte.gktedpi.core

import android.content.SharedPreferences
import com.sametgkte.gktedpi.utility.getStringNotNull
import com.sametgkte.gktedpi.utility.shellQuoteJoin
import com.sametgkte.gktedpi.utility.shellSplit

sealed interface ByeDpiProxyPreferences {
    fun toCommandArgs(): Array<String>
    fun toDisplayCommand(): String = shellQuoteJoin(toCommandArgs().toList())

    companion object {
        fun fromSharedPreferences(preferences: SharedPreferences): ByeDpiProxyPreferences =
            when (preferences.getBoolean("byedpi_enable_cmd_settings", false)) {
                true -> ByeDpiProxyCmdPreferences(preferences)
                false -> ByeDpiProxyUIPreferences(preferences)
            }
    }
}

class ByeDpiProxyCmdPreferences(
    rawArgs: Array<String>,
    fallbackIp: String = "127.0.0.1",
    fallbackPort: Int = 1080,
) : ByeDpiProxyPreferences {

    private val finalArgs: Array<String>

    init {
        val cleaned = rawArgs.filter { it.isNotBlank() }.toMutableList()
        if (cleaned.isEmpty() || cleaned.first() != "ciadpi") {
            if (cleaned.firstOrNull()?.startsWith("-") == false) {
                cleaned[0] = "ciadpi"
            } else {
                cleaned.add(0, "ciadpi")
            }
        }
        val hasIp = cleaned.any { it == "-i" || it == "--ip" || it.startsWith("--ip=") }
        val hasPort = cleaned.any { it == "-p" || it == "--port" || it.startsWith("--port=") }
        val injected = mutableListOf("ciadpi")
        if (!hasIp) {
            injected.add("-i")
            injected.add(fallbackIp)
        }
        if (!hasPort) {
            injected.add("-p")
            injected.add(fallbackPort.toString())
        }
        injected.addAll(cleaned.drop(1))
        finalArgs = injected.toTypedArray()
    }

    constructor(
        cmd: String,
        fallbackIp: String = "127.0.0.1",
        fallbackPort: Int = 1080,
    ) : this(cmdToArgs(cmd), fallbackIp, fallbackPort)

    constructor(preferences: SharedPreferences) : this(
        cmd = preferences.getStringNotNull("byedpi_cmd_args", "-d 1 -r 1+s -M h,d -a 2"),
        fallbackIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1"),
        fallbackPort = preferences.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080,
    )

    override fun toCommandArgs(): Array<String> = finalArgs

    companion object {
        fun cmdToArgs(cmd: String): Array<String> {
            val trimmed = cmd.trim()
            if (trimmed.isEmpty()) return arrayOf("ciadpi")
            val tokens = shellSplit(trimmed)
            return if (tokens.firstOrNull()?.startsWith("-") == false) {
                (listOf("ciadpi") + tokens.drop(1)).toTypedArray()
            } else {
                (listOf("ciadpi") + tokens).toTypedArray()
            }
        }
    }
}

class ByeDpiProxyUIPreferences(
    val ip: String = "127.0.0.1",
    val port: Int = 1080,
    val connIp: String = "",
    val maxConnections: Int = 512,
    val bufferSize: Int = 16384,
    val defaultTtl: Int = 0,
    val noDomain: Boolean = false,
    val noUdp: Boolean = false,
    val tcpFastOpen: Boolean = false,
    val debugLevel: Int = 0,
    val sfDelay: Int = 3,
    val noWaitSend: Boolean = false,
    val autoEnabled: Boolean = false,
    val autoTorst: Boolean = true,
    val autoRedirect: Boolean = true,
    val autoSslErr: Boolean = true,
    val autoNone: Boolean = false,
    val timeoutSec: String = "",
    val cacheTtl: Int = 100800,
    val portFilter: String = "",
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
    val desyncMethod: DesyncMethod = DesyncMethod.Disorder,
    val splitPosition: Int = 1,
    val offsetFlag: OffsetFlag = OffsetFlag.SNI,
    val secondSplitEnabled: Boolean = false,
    val secondSplitMethod: DesyncMethod = DesyncMethod.Disorder,
    val secondSplitPosition: Int = 3,
    val secondOffsetFlag: OffsetFlag = OffsetFlag.SNI,
    val fakeTtl: Int = 8,
    val md5Sig: Boolean = false,
    val fakeOffset: Int = 0,
    val fakeSni: String = "www.iana.org",
    val fakeData: String = "",
    val ipOptions: String = "",
    val oobData: String = "a",
    val dropSack: Boolean = false,
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val tlsRecordSplit: Boolean = false,
    val tlsRecordSplitPosition: Int = 1,
    val tlsRecordSplitAtSni: Boolean = true,
    val secondTlsRecordSplit: Boolean = false,
    val secondTlsRecordSplitPosition: Int = 3,
    val secondTlsRecordSplitAtSni: Boolean = false,
    val hostsMode: HostsMode = HostsMode.Disable,
    val hosts: String = "",
    val udpFakeCount: Int = 0,
    val extraAutoArgs: String = "",
) : ByeDpiProxyPreferences {

    constructor(preferences: SharedPreferences) : this(
        ip = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1"),
        port = preferences.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080,
        connIp = preferences.getStringNotNull("byedpi_conn_ip", "").trim(),
        maxConnections = preferences.getString("byedpi_max_connections", null)?.toIntOrNull() ?: 512,
        bufferSize = preferences.getString("byedpi_buffer_size", null)?.toIntOrNull() ?: 16384,
        defaultTtl = preferences.getString("byedpi_default_ttl", null)?.toIntOrNull() ?: 0,
        noDomain = preferences.getBoolean("byedpi_no_domain", false),
        noUdp = preferences.getBoolean("byedpi_no_udp", false),
        tcpFastOpen = preferences.getBoolean("byedpi_tcp_fast_open", false),
        debugLevel = preferences.getString("byedpi_debug_level", null)?.toIntOrNull() ?: 0,
        sfDelay = preferences.getString("byedpi_sfdelay", null)?.toIntOrNull() ?: 3,
        noWaitSend = preferences.getBoolean("byedpi_no_wait_send", false),
        autoEnabled = preferences.getBoolean("byedpi_auto_enabled", false),
        autoTorst = preferences.getBoolean("byedpi_auto_torst", true),
        autoRedirect = preferences.getBoolean("byedpi_auto_redirect", true),
        autoSslErr = preferences.getBoolean("byedpi_auto_ssl_err", true),
        autoNone = preferences.getBoolean("byedpi_auto_none", false),
        timeoutSec = preferences.getStringNotNull("byedpi_timeout", "").trim(),
        cacheTtl = preferences.getString("byedpi_cache_ttl", null)?.toIntOrNull() ?: 100800,
        portFilter = preferences.getStringNotNull("byedpi_port_filter", "").trim(),
        desyncHttp = preferences.getBoolean("byedpi_desync_http", true),
        desyncHttps = preferences.getBoolean("byedpi_desync_https", true),
        desyncUdp = preferences.getBoolean("byedpi_desync_udp", false),
        desyncMethod = DesyncMethod.fromName(
            preferences.getStringNotNull("byedpi_desync_method", "disorder")
        ),
        splitPosition = preferences.getString("byedpi_split_position", null)?.toIntOrNull() ?: 1,
        offsetFlag = preferences.getString("byedpi_offset_flag", null)?.let {
            OffsetFlag.fromName(it)
        } ?: if (preferences.getBoolean("byedpi_split_at_host", false)) {
            if (preferences.getBoolean("byedpi_desync_https", true)) OffsetFlag.SNI else OffsetFlag.Host
        } else {
            OffsetFlag.None
        },
        secondSplitEnabled = preferences.getBoolean("byedpi_second_split_enabled", false),
        secondSplitMethod = DesyncMethod.fromName(
            preferences.getStringNotNull("byedpi_second_split_method", "disorder")
        ),
        secondSplitPosition = preferences.getString("byedpi_second_split_position", null)?.toIntOrNull() ?: 3,
        secondOffsetFlag = OffsetFlag.fromName(
            preferences.getStringNotNull("byedpi_second_offset_flag", "sni")
        ),
        fakeTtl = preferences.getString("byedpi_fake_ttl", null)?.toIntOrNull() ?: 8,
        md5Sig = preferences.getBoolean("byedpi_md5sig", false),
        fakeOffset = preferences.getString("byedpi_fake_offset", null)?.toIntOrNull() ?: 0,
        fakeSni = preferences.getStringNotNull("byedpi_fake_sni", "www.iana.org"),
        fakeData = preferences.getStringNotNull("byedpi_fake_data", ""),
        ipOptions = preferences.getStringNotNull("byedpi_ip_options", ""),
        oobData = preferences.getStringNotNull("byedpi_oob_data", "a"),
        dropSack = preferences.getBoolean("byedpi_drop_sack", false),
        hostMixedCase = preferences.getBoolean("byedpi_host_mixed_case", false),
        domainMixedCase = preferences.getBoolean("byedpi_domain_mixed_case", false),
        hostRemoveSpaces = preferences.getBoolean("byedpi_host_remove_spaces", false),
        tlsRecordSplit = preferences.getBoolean("byedpi_tlsrec_enabled", false),
        tlsRecordSplitPosition = preferences.getString("byedpi_tlsrec_position", null)?.toIntOrNull() ?: 1,
        tlsRecordSplitAtSni = preferences.getBoolean("byedpi_tlsrec_at_sni", true),
        secondTlsRecordSplit = preferences.getBoolean("byedpi_second_tlsrec_enabled", false),
        secondTlsRecordSplitPosition = preferences.getString("byedpi_second_tlsrec_position", null)?.toIntOrNull() ?: 3,
        secondTlsRecordSplitAtSni = preferences.getBoolean("byedpi_second_tlsrec_at_sni", false),
        hostsMode = HostsMode.fromName(
            preferences.getStringNotNull("byedpi_hosts_mode", "disable")
        ),
        hosts = when (HostsMode.fromName(preferences.getStringNotNull("byedpi_hosts_mode", "disable"))) {
            HostsMode.Blacklist -> preferences.getStringNotNull("byedpi_hosts_blacklist", "")
            HostsMode.Whitelist -> preferences.getStringNotNull("byedpi_hosts_whitelist", "")
            HostsMode.Disable -> ""
        },
        udpFakeCount = preferences.getString("byedpi_udp_fake_count", null)?.toIntOrNull() ?: 0,
        extraAutoArgs = preferences.getStringNotNull("byedpi_extra_auto_args", "").trim(),
    )

    override fun toCommandArgs(): Array<String> {
        val args = mutableListOf("ciadpi")

        args.add("-i")
        args.add(ip.ifBlank { "127.0.0.1" })

        args.add("-p")
        args.add(port.coerceIn(1, 65535).toString())

        if (connIp.isNotBlank()) {
            args.add("-I")
            args.add(connIp)
        }

        if (maxConnections != 512 && maxConnections > 0) {
            args.add("-c")
            args.add(maxConnections.toString())
        }

        if (bufferSize != 16384 && bufferSize > 0) {
            args.add("-b")
            args.add(bufferSize.toString())
        }

        if (defaultTtl in 1..255) {
            args.add("-g")
            args.add(defaultTtl.toString())
        }

        if (noDomain) args.add("-N")
        if (noUdp) args.add("-U")
        if (tcpFastOpen) args.add("-F")

        if (debugLevel in 1..2) {
            args.add("-x")
            args.add(debugLevel.toString())
        }

        if (sfDelay in 0..999 && sfDelay != 3) {
            args.add("-w")
            args.add(sfDelay.toString())
        }

        if (noWaitSend) args.add("-W")

        if (cacheTtl > 0 && cacheTtl != 100800) {
            args.add("-u")
            args.add(cacheTtl.toString())
        }

        timeoutSec.toFloatOrNull()?.takeIf { it > 0f }?.let {
            args.add("-T")
            args.add(timeoutSec)
        }

        val normalizedHosts = hosts.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (hostsMode == HostsMode.Blacklist && normalizedHosts.isNotEmpty()) {
            args.add("-H")
            args.add(":$normalizedHosts")
            args.add("-A")
            args.add("none")
        }

        if (autoEnabled) {
            val triggers = buildList {
                if (autoTorst) add("t")
                if (autoRedirect) add("r")
                if (autoSslErr) add("s")
                if (autoNone) add("n")
            }
            if (triggers.isNotEmpty()) {
                args.add("-A")
                args.add(triggers.joinToString(","))
            }
        }

        if (hostsMode == HostsMode.Whitelist && normalizedHosts.isNotEmpty()) {
            args.add("-H")
            args.add(":$normalizedHosts")
        }

        if (portFilter.isNotBlank()) {
            args.add("-V")
            args.add(portFilter)
        }

        val effectiveUdp = desyncUdp || udpFakeCount > 0
        val protos = buildList {
            if (desyncHttps) add("t")
            if (desyncHttp) add("h")
            if (effectiveUdp) add("u")
        }
        // Only add -K if user narrowed protocols (not all 3 and not 0)
        if (protos.isNotEmpty() && protos.size < 3) {
            args.add("-K")
            args.add(protos.joinToString(","))
        }

        if (desyncMethod != DesyncMethod.None) {
            args.add(desyncMethod.shortFlag)
            args.add("${splitPosition}${offsetFlag.suffix}")
        }

        if (secondSplitEnabled && secondSplitMethod != DesyncMethod.None) {
            args.add(secondSplitMethod.shortFlag)
            args.add("${secondSplitPosition}${secondOffsetFlag.suffix}")
        }

        val usesFake = desyncMethod == DesyncMethod.Fake ||
            (secondSplitEnabled && secondSplitMethod == DesyncMethod.Fake)
        if (usesFake) {
            if (fakeTtl in 1..255) {
                args.add("-t")
                args.add(fakeTtl.toString())
            }
            if (md5Sig) {
                args.add("-S")
            }
            if (fakeOffset > 0) {
                args.add("-O")
                args.add(fakeOffset.toString())
            }
            if (fakeSni.isNotBlank()) {
                args.add("-n")
                args.add(fakeSni.trim())
            }
            if (fakeData.isNotBlank()) {
                val formatted = if (fakeData.startsWith(":") || fakeData.startsWith("/")) {
                    fakeData
                } else {
                    ":$fakeData"
                }
                args.add("-l")
                args.add(formatted)
            }
            if (ipOptions.isNotBlank()) {
                val formatted = if (ipOptions.startsWith(":") || ipOptions.startsWith("/")) {
                    ipOptions
                } else {
                    ":$ipOptions"
                }
                args.add("-k")
                args.add(formatted)
            }
        }

        val usesOob = desyncMethod == DesyncMethod.OOB ||
            desyncMethod == DesyncMethod.DISOOB ||
            (secondSplitEnabled && (secondSplitMethod == DesyncMethod.OOB || secondSplitMethod == DesyncMethod.DISOOB))
        if (usesOob && oobData.isNotBlank()) {
            args.add("-e")
            args.add(oobData.trim())
        }

        if (dropSack) {
            args.add("-Y")
        }

        val httpMods = buildList {
            if (hostMixedCase) add("h")
            if (domainMixedCase) add("d")
            if (hostRemoveSpaces) add("r")
        }
        if (httpMods.isNotEmpty()) {
            args.add("-M")
            args.add(httpMods.joinToString(","))
        }

        if (tlsRecordSplit) {
            val sniFlag = if (tlsRecordSplitAtSni) "+s" else ""
            args.add("-r")
            args.add("${tlsRecordSplitPosition}$sniFlag")

            if (secondTlsRecordSplit) {
                val sniFlag2 = if (secondTlsRecordSplitAtSni) "+s" else ""
                args.add("-r")
                args.add("${secondTlsRecordSplitPosition}$sniFlag2")
            }
        }

        if (udpFakeCount > 0) {
            args.add("-a")
            args.add(udpFakeCount.toString())
        }

        if (extraAutoArgs.isNotBlank()) {
            args.addAll(shellSplit(extraAutoArgs))
        }

        return args.toTypedArray()
    }

    enum class DesyncMethod(val value: String, val shortFlag: String) {
        None("none", ""),
        Split("split", "-s"),
        Disorder("disorder", "-d"),
        Fake("fake", "-f"),
        OOB("oob", "-o"),
        DISOOB("disoob", "-q");

        companion object {
            fun fromName(name: String?): DesyncMethod = when (name) {
                "none" -> None
                "split" -> Split
                "disorder" -> Disorder
                "fake" -> Fake
                "oob" -> OOB
                "disoob" -> DISOOB
                else -> Disorder
            }
        }
    }

    enum class OffsetFlag(val value: String, val suffix: String) {
        None("none", ""),
        SNI("sni", "+s"),
        Host("host", "+h"),
        End("end", "+e");

        companion object {
            fun fromName(name: String?): OffsetFlag = when (name) {
                "sni" -> SNI
                "host" -> Host
                "end" -> End
                else -> None
            }
        }
    }

    enum class HostsMode(val value: String) {
        Disable("disable"),
        Blacklist("blacklist"),
        Whitelist("whitelist");

        companion object {
            fun fromName(name: String?): HostsMode = when (name) {
                "blacklist" -> Blacklist
                "whitelist" -> Whitelist
                else -> Disable
            }
        }
    }
}
