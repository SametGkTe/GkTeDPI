package com.sametgkte.gktedpi.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * FATİH / School & Campus NGFW Stealth Engine (Everest / HTTPS-443 Obfuscation).
 *
 * Defeats Fortinet FortiGate / Palo Alto / Sophos L7 school & campus firewalls via:
 *  1. Strict TCP Port 443 TLS & Port 80 WebSocket protocol selection (blocks non-443/80 & raw binary).
 *  2. Everest-style 3-Record TLS ClientHello Fragmentation slicing directly through the SNI hostname.
 *  3. Direct-IP DNS-over-HTTPS (1.1.1.1 / 1.0.0.1 / 8.8.8.8 / 9.9.9.9) over fragmented TLS (anti-DNS-poison).
 *  4. Multi-CDN Stealth Bootstrap (jsDelivr / Fastly educational CDNs + fragmented HTTPS client).
 *  5. Cloudflare Clean-Edge Anycast IP Fronting (104.16.x.x / 172.64.x.x / 162.159.x.x) for WS+TLS tunnels.
 *  6. Active Firewall SSL-MITM Certificate Rejection (blocks Fortinet / FATIH-CA / MEB MITM certs).
 */
object FatihStealthEngine {

    private data class DohEntry(val ip: InetAddress, val expiresAtElapsed: Long)

    private val dohCache = ConcurrentHashMap<String, DohEntry>()
    private const val DOH_TTL_MS = 600_000L

    private val FIREWALL_MITM_MARKERS = listOf(
        "fortinet",
        "fortigate",
        "fatih",
        "meb.gov",
        "turktelekom",
        "btk.gov",
        "cyberroam",
        "sophos",
        "paloalto",
        "ssl-inspection",
        "untangle",
        "watchguard",
    )

    /** Clean Cloudflare Anycast CDN Edge IPs (Port 443) shared with educational/global web assets. */
    val CLEAN_CLOUDFLARE_EDGE_IPS: List<String> = listOf(
        "104.16.10.135",
        "104.16.132.229",
        "104.18.2.161",
        "172.64.155.209",
        "162.159.135.42",
    )

    private data class DohProvider(
        val ip: String,
        val sni: String,
        val pathPrefix: String,
    )

    private val DOH_PROVIDERS = listOf(
        DohProvider("1.1.1.1", "cloudflare-dns.com", "/dns-query?type=A&name="),
        DohProvider("1.0.0.1", "cloudflare-dns.com", "/dns-query?type=A&name="),
        DohProvider("8.8.8.8", "dns.google", "/resolve?type=A&name="),
        DohProvider("9.9.9.9", "dns.quad9.net", "/dns-query?type=A&name="),
    )

    /**
     * Built-in Port-443 TLS / WS+TLS emergency stealth seeds so first-time launch inside
     * FATİH Wi-Fi works even with zero prior cache and blocked subscription domains.
     */
    val FATIH_STEALTH_SEEDS: List<VpnEndpoint> by lazy {
        listOf(
            VpnEndpoint(
                id = "stealth_jp_cf_1",
                countryCode = "JP",
                label = "Stealth CDN • #1 (Port 443 WS+TLS)",
                protocol = VpnProtocol.VLESS,
                host = "104.16.10.135",
                port = 443,
                uuid = "4e4f1f70-5e56-4a76-bb9d-db087f4690c4",
                tlsEnabled = true,
                tlsSni = "jp3.yohototo.top",
                alpn = listOf("http/1.1"),
                transport = "ws",
                wsPath = "/tyxyws",
                wsHost = "jp3.yohototo.top",
            ),
            VpnEndpoint(
                id = "stealth_jp_cf_2",
                countryCode = "JP",
                label = "Stealth CDN • #2 (Port 443 WS+TLS)",
                protocol = VpnProtocol.VLESS,
                host = "104.19.164.155",
                port = 443,
                uuid = "4e4f1f70-5e56-4a76-bb9d-db087f4690c4",
                tlsEnabled = true,
                tlsSni = "jp3.yohototo.top",
                alpn = listOf("http/1.1"),
                transport = "ws",
                wsPath = "/tyxyws",
                wsHost = "jp3.yohototo.top",
            ),
            VpnEndpoint(
                id = "stealth_de_443_1",
                countryCode = "DE",
                label = "DE • Frankfurt Stealth (Port 443 TLS)",
                protocol = VpnProtocol.TROJAN,
                host = "193.108.119.193",
                port = 443,
                password = "humanity",
                tlsEnabled = true,
                tlsSni = "resources.iloverussia2026.com",
                tlsInsecure = true,
            ),
            VpnEndpoint(
                id = "stealth_us_443_1",
                countryCode = "US",
                label = "US • Virginia Stealth (Port 443 TLS)",
                protocol = VpnProtocol.TROJAN,
                host = "66.163.114.45",
                port = 443,
                password = "0ab6c98dae3b48e8b9c4a776b6c9c19a",
                tlsEnabled = true,
                tlsSni = "cdn.findconnectwithemployersandbuildprofessionalnetwork.com",
                tlsInsecure = true,
            ),
            VpnEndpoint(
                id = "stealth_pl_443_1",
                countryCode = "PL",
                label = "PL • Varşova Stealth (Port 443 TLS)",
                protocol = VpnProtocol.TROJAN,
                host = "70.34.252.255",
                port = 443,
                password = "humanity",
                tlsEnabled = true,
                tlsSni = "browseexecutivejobopeningsandenhanceleadershipcareer.com",
                tlsInsecure = true,
            ),
            VpnEndpoint(
                id = "stealth_ca_443_1",
                countryCode = "CA",
                label = "CA • Toronto Stealth (Port 443 TLS)",
                protocol = VpnProtocol.TROJAN,
                host = "216.152.147.48",
                port = 443,
                password = "humanity",
                tlsEnabled = true,
                tlsSni = "courses.connectingpeoplewithgreatjobs.com",
                tlsInsecure = true,
            ),
            VpnEndpoint(
                id = "stealth_sg_443_1",
                countryCode = "SG",
                label = "SG • Singapur Stealth (Port 443 TLS)",
                protocol = VpnProtocol.TROJAN,
                host = "149.28.130.108",
                port = 443,
                password = "humanity",
                tlsEnabled = true,
                tlsSni = "searchcreativejobsandshowcaseyourartistictalents.com",
                tlsInsecure = true,
            ),
        )
    }

     val antiMitmSslContext: SSLContext by lazy { createAntiMitmSslContext() }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createAntiMitmSslContext(): SSLContext {
        val tm = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    verifyCertificateNotFirewallMitm(chain)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        return SSLContext.getInstance("TLS").apply {
            init(null, tm, SecureRandom())
        }
    }

    /** Throws [CertificateException] if a school/corporate firewall injected a MITM block cert. */
    fun verifyCertificateNotFirewallMitm(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) return
        for (cert in chain) {
            val combined = (
                cert.issuerX500Principal.name + " | " + cert.subjectX500Principal.name
                ).lowercase(Locale.ROOT)
            for (marker in FIREWALL_MITM_MARKERS) {
                if (combined.contains(marker)) {
                    throw CertificateException("Güvenlik duvarı SSL-MITM sertifikası engellendi ($marker)")
                }
            }
        }
    }

    /**
     * Returns true ONLY for endpoints disguised as standard HTTPS (TCP 443 + TLS) or HTTP WS (TCP 80)
     * that can traverse strict MEB FATİH / campus port + L7 protocol filters.
     */
    fun isStealthCompatible(ep: VpnEndpoint): Boolean {
        if (ep.isCustom) return true
        val validProto = ep.protocol == VpnProtocol.VLESS || ep.protocol == VpnProtocol.TROJAN
        if (!validProto) return false
        val tr = ep.transport.lowercase(Locale.ROOT)
        if (tr != "ws" && tr != "tcp") return false
        if (ep.port == 443 && ep.tlsEnabled) return true
        if (ep.port == 80 && tr == "ws") return true
        return false
    }

    /**
     * Ranks `WS + TLS : 443` highest (indistinguishable from browser HTTPS WebSocket),
     * injects Clean Cloudflare Anycast Edge IP clones for Cloudflare-backed WS+TLS nodes,
     * followed by `TCP + TLS : 443`, then `WS : 80`.
     */
    fun prepareStealthCandidates(rawList: List<VpnEndpoint>, maxCount: Int = 18): List<VpnEndpoint> {
        val filtered = rawList.filter { isStealthCompatible(it) }
        if (filtered.isEmpty()) return emptyList()

        val wsTls443 = filtered.filter { it.port == 443 && it.tlsEnabled && it.transport.equals("ws", true) }
        val tcpTls443 = filtered.filter { it.port == 443 && it.tlsEnabled && !it.transport.equals("ws", true) }
        val ws80 = filtered.filter { it.port == 80 }

        val expanded = ArrayList<VpnEndpoint>(maxCount + 8)
        val seen = HashSet<String>()

        fun addUnique(ep: VpnEndpoint) {
            if (seen.add(ep.uniqueKey())) expanded.add(ep)
        }

        // Prioritize previously verified fast nodes first
        filtered.filter { it.measuredLatencyMs in 1..2800 }
            .sortedBy { it.measuredLatencyMs }
            .forEach(::addUnique)

        for (ep in wsTls443) {
            addUnique(ep)
            if (looksLikeCloudflareFronted(ep)) {
                val cleanEdge = CLEAN_CLOUDFLARE_EDGE_IPS[(ep.id.hashCode() and 0x7FFFFFFF) % CLEAN_CLOUDFLARE_EDGE_IPS.size]
                if (cleanEdge != ep.host) {
                    addUnique(
                        ep.copy(
                            id = "${ep.id}_cfedge",
                            host = cleanEdge,
                            label = "${ep.label} (CDN)",
                            alpn = ep.alpn.ifEmpty { listOf("http/1.1") },
                        )
                    )
                }
            }
        }

        tcpTls443.forEach(::addUnique)
        ws80.forEach(::addUnique)

        return expanded.take(maxCount)
    }

    fun looksLikeCloudflareFronted(ep: VpnEndpoint): Boolean {
        val h = ep.host.trim()
        return h.startsWith("104.16.") ||
            h.startsWith("104.17.") ||
            h.startsWith("104.18.") ||
            h.startsWith("104.19.") ||
            h.startsWith("104.20.") ||
            h.startsWith("104.21.") ||
            h.startsWith("172.64.") ||
            h.startsWith("172.67.") ||
            h.startsWith("162.159.") ||
            h.startsWith("188.114.")
    }

    /**
     * Wraps a connected [Socket] so Java/Android Conscrypt `SSLSocketFactory.createSocket(s, ...)`
     * routes its initial `ClientHello` through [splitTlsClientHelloRecord], slicing the TLS record
     * into 3 valid `0x16` Handshake records directly across the SNI hostname bytes.
     */
    fun wrapSocketForTlsFragmentation(inner: Socket, sniHint: String): Socket =
        TlsFragmentingSocket(inner, sniHint)

    /**
     * Pure function (unit-testable): splits a single TLS Handshake `ClientHello` record (`0x16`)
     * into 3 valid TLS Handshake records `[2B] + [up to mid-SNI] + [rest]`.
     */
    fun splitTlsClientHelloRecord(rawRecord: ByteArray, off: Int, len: Int, sniHint: String = ""): List<ByteArray> {
        if (len < 40 || (rawRecord[off].toInt() and 0xFF) != 0x16) {
            return listOf(rawRecord.copyOfRange(off, off + len))
        }
        val vmaj = rawRecord[off + 1]
        val vmin = rawRecord[off + 2]
        val recLen = ((rawRecord[off + 3].toInt() and 0xFF) shl 8) or (rawRecord[off + 4].toInt() and 0xFF)
        if (recLen < 32 || 5 + recLen > len) {
            return listOf(rawRecord.copyOfRange(off, off + len))
        }

        val bodyStart = off + 5
        val firstCut = 2

        // Locate exact SNI bytes inside ClientHello body if present so we cut mid-hostname!
        var secondCut = minOf(recLen - 4, maxOf(24, recLen / 3))
        val cleanSni = sniHint.trim().lowercase(Locale.ROOT)
        if (cleanSni.length >= 4 && !PhysicalNetworkHelper.isIpLiteral(cleanSni)) {
            val needle = cleanSni.toByteArray(StandardCharsets.US_ASCII)
            val idx = indexOfSubArray(rawRecord, bodyStart, recLen, needle)
            if (idx > firstCut + 2 && idx + (needle.size / 2) < recLen - 2) {
                secondCut = idx + (needle.size / 2)
            }
        }

        fun makeTlsRecord(sliceStartInBody: Int, sliceLen: Int): ByteArray {
            val out = ByteArray(5 + sliceLen)
            out[0] = 0x16
            out[1] = vmaj
            out[2] = vmin
            out[3] = ((sliceLen ushr 8) and 0xFF).toByte()
            out[4] = (sliceLen and 0xFF).toByte()
            System.arraycopy(rawRecord, bodyStart + sliceStartInBody, out, 5, sliceLen)
            return out
        }

        val outList = ArrayList<ByteArray>(4)
        outList.add(makeTlsRecord(0, firstCut))
        outList.add(makeTlsRecord(firstCut, secondCut - firstCut))
        outList.add(makeTlsRecord(secondCut, recLen - secondCut))
        val consumed = 5 + recLen
        if (consumed < len) {
            outList.add(rawRecord.copyOfRange(off + consumed, off + len))
        }
        return outList
    }

    private fun indexOfSubArray(haystack: ByteArray, start: Int, length: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > length) return -1
        val maxI = length - needle.size
        outer@ for (i in 0..maxI) {
            for (j in needle.indices) {
                if (haystack[start + i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Resolves [host] via Direct-IP DNS-over-HTTPS (`1.1.1.1` / `8.8.8.8`) over fragmented TLS 443,
     * falling back to physical network DNS only if all DoH Anycast IPs are unreachable.
     */
    fun resolveHostStealth(
        context: Context?,
        host: String,
        protectSocket: ((Socket) -> Boolean)? = null,
    ): InetAddress {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (PhysicalNetworkHelper.isIpLiteral(clean)) {
            return InetAddress.getByName(clean)
        }
        val now = SystemClock.elapsedRealtime()
        dohCache[clean]?.let { cached ->
            if (cached.expiresAtElapsed > now) return cached.ip
        }

        val encoded = URLEncoder.encode(clean, "UTF-8")
        for (provider in DOH_PROVIDERS) {
            try {
                val jsonText = performRawStealthHttpsGet(
                    context = context,
                    connectIp = provider.ip,
                    port = 443,
                    hostHeader = provider.sni,
                    sni = provider.sni,
                    pathAndQuery = provider.pathPrefix + encoded,
                    acceptHeader = "application/dns-json",
                    protectSocket = protectSocket,
                    timeoutMs = 2800,
                )
                val ipStr = extractFirstIpv4FromDohJson(jsonText)
                if (!ipStr.isNullOrBlank()) {
                    val resolved = InetAddress.getByName(ipStr)
                    dohCache[clean] = DohEntry(resolved, now + DOH_TTL_MS)
                    return resolved
                }
            } catch (_: Throwable) {
            }
        }

        return PhysicalNetworkHelper.resolveEndpointHost(context, clean)
    }

    internal fun extractFirstIpv4FromDohJson(jsonText: String): String? {
        return try {
            val obj = JSONObject(jsonText)
            val answers = obj.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                if (ans.optInt("type") == 1) {
                    val data = ans.optString("data").trim()
                    if (PhysicalNetworkHelper.isIpLiteral(data) && ':' !in data) {
                        return data
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Fetches an HTTPS URL using Direct-IP DoH + 3-record TLS ClientHello fragmentation on Port 443,
     * bypassing SNI blockers & poisoned school DNS when pulling server lists.
     */
    fun fetchHttpsTextStealth(
        context: Context?,
        urlStr: String,
        protectSocket: ((Socket) -> Boolean)? = null,
        timeoutMs: Int = 5500,
    ): String {
        val url = URL(urlStr)
        val host = url.host
        val port = if (url.port > 0) url.port else 443
        val path = buildString {
            append(url.path.ifEmpty { "/" })
            if (!url.query.isNullOrEmpty()) append('?').append(url.query)
        }
        val ip = resolveHostStealth(context, host, protectSocket).hostAddress ?: host
        return performRawStealthHttpsGet(
            context = context,
            connectIp = ip,
            port = port,
            hostHeader = host,
            sni = host,
            pathAndQuery = path,
            acceptHeader = "*/*",
            protectSocket = protectSocket,
            timeoutMs = timeoutMs,
        )
    }

    private fun performRawStealthHttpsGet(
        context: Context?,
        connectIp: String,
        port: Int,
        hostHeader: String,
        sni: String,
        pathAndQuery: String,
        acceptHeader: String,
        protectSocket: ((Socket) -> Boolean)?,
        timeoutMs: Int,
    ): String {
        val physNet = PhysicalNetworkHelper.findPhysicalNetwork(context)
        val raw = Socket()
        try {
            if (protectSocket != null) {
                protectSocket.invoke(raw)
            } else if (physNet != null) {
                try {
                    physNet.bindSocket(raw)
                } catch (_: Throwable) {
                }
            }
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(InetAddress.getByName(connectIp), port), timeoutMs)
            raw.soTimeout = timeoutMs

            val frag = wrapSocketForTlsFragmentation(raw, sni)
            val ssl = antiMitmSslContext.socketFactory.createSocket(frag, sni, port, true) as SSLSocket
            ssl.useClientMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        params.applicationProtocols = arrayOf("http/1.1")
                    }
                    ssl.sslParameters = params
                } catch (_: Throwable) {
                }
            }
            ssl.startHandshake()

            val req = buildString {
                append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36\r\n")
                append("Accept: ").append(acceptHeader).append("\r\n")
                append("Accept-Encoding: gzip\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = ssl.getOutputStream()
            out.write(req.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val inp = ssl.getInputStream()
            val headerBytes = ByteArrayOutputStream(512)
            var state = 0
            while (headerBytes.size() < 16384) {
                val b = inp.read()
                if (b < 0) break
                headerBytes.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (state == 4) break
            }

            val headerStr = headerBytes.toString("ISO-8859-1")
            val lines = headerStr.split("\r\n")
            val statusLine = lines.firstOrNull().orEmpty()
            if (!statusLine.contains(" 200")) {
                throw IOException("Stealth HTTPS status: $statusLine")
            }

            var isChunked = false
            var isGzip = false
            var contentLength = -1
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val k = line.substring(0, idx).trim().lowercase(Locale.ROOT)
                val v = line.substring(idx + 1).trim().lowercase(Locale.ROOT)
                when (k) {
                    "transfer-encoding" -> if ("chunked" in v) isChunked = true
                    "content-encoding" -> if ("gzip" in v) isGzip = true
                    "content-length" -> contentLength = v.toIntOrNull() ?: -1
                }
            }

            val rawBody = when {
                isChunked -> readChunkedBody(inp)
                contentLength >= 0 -> {
                    val b = ByteArray(contentLength)
                    RealVpnDialer.readExact(inp, b, 0, contentLength)
                    b
                }
                else -> inp.readBytes()
            }

            val decodedBytes = if (isGzip && rawBody.size >= 2) {
                GZIPInputStream(ByteArrayInputStream(rawBody)).use { it.readBytes() }
            } else {
                rawBody
            }
            return String(decodedBytes, StandardCharsets.UTF_8)
        } finally {
            try {
                raw.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readChunkedBody(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream(4096)
        while (true) {
            val line = readAsciiLine(inp).trim()
            if (line.isEmpty()) continue
            val semi = line.indexOf(';')
            val hex = if (semi >= 0) line.substring(0, semi).trim() else line
            val chunkSize = hex.toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            val buf = ByteArray(chunkSize)
            RealVpnDialer.readExact(inp, buf, 0, chunkSize)
            out.write(buf)
            // Consume trailing CRLF after chunk
            val c1 = inp.read()
            if (c1 == '\r'.code) inp.read()
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(inp: InputStream): String {
        val sb = StringBuilder(32)
        while (sb.length < 256) {
            val b = inp.read()
            if (b < 0) throw EOFException("Chunk EOF")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}

/**
 * Pure delegation Socket with NO internal OS FileDescriptor of its own, guaranteeing that
 * Android Conscrypt wraps it via `ConscryptEngineSocket` (`getInputStream`/`getOutputStream`)
 * so the initial TLS `ClientHello` record is fragmented across 3 TCP/TLS segments.
 */
private class TlsFragmentingSocket(
    private val inner: Socket,
    private val sniHint: String,
) : Socket() {
    private var fragOut: OutputStream? = null

    override fun getInputStream(): InputStream = inner.getInputStream()

    @Synchronized
    override fun getOutputStream(): OutputStream {
        fragOut?.let { return it }
        val rawOut = inner.getOutputStream()
        val wrapped = object : OutputStream() {
            private var firstFlightHandled = false
            private val single = ByteArray(1)

            override fun write(b: Int) {
                single[0] = b.toByte()
                write(single, 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                if (!firstFlightHandled) {
                    firstFlightHandled = true
                    if (len >= 40 && (b[off].toInt() and 0xFF) == 0x16) {
                        val records = FatihStealthEngine.splitTlsClientHelloRecord(b, off, len, sniHint)
                        for (rec in records) {
                            rawOut.write(rec)
                            rawOut.flush()
                        }
                        return
                    }
                }
                rawOut.write(b, off, len)
            }

            override fun flush() = rawOut.flush()
            override fun close() = rawOut.close()
        }
        fragOut = wrapped
        return wrapped
    }

    override fun isConnected(): Boolean = inner.isConnected
    override fun isClosed(): Boolean = inner.isClosed
    override fun isBound(): Boolean = inner.isBound
    override fun getInetAddress(): InetAddress? = inner.inetAddress
    override fun getLocalAddress(): InetAddress = inner.localAddress
    override fun getPort(): Int = inner.port
    override fun getLocalPort(): Int = inner.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress
    override fun setSoTimeout(timeout: Int) {
        inner.soTimeout = timeout
    }
    override fun getSoTimeout(): Int = inner.soTimeout
    override fun setTcpNoDelay(on: Boolean) {
        inner.tcpNoDelay = on
    }
    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay
    override fun setKeepAlive(on: Boolean) {
        inner.keepAlive = on
    }
    override fun getKeepAlive(): Boolean = inner.keepAlive

    @Synchronized
    override fun close() {
        inner.close()
    }
}
