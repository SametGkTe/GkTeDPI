package com.sametgkte.gktedpi.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TunnelSession(
    val rawSocket: Socket,
    val input: InputStream,
    val output: OutputStream,
) : Closeable {
    override fun close() {
        try {
            input.close()
        } catch (_: Throwable) {
        }
        try {
            output.close()
        } catch (_: Throwable) {
        }
        try {
            rawSocket.close()
        } catch (_: Throwable) {
        }
    }
}

object PhysicalNetworkHelper {
    private data class CachedIp(val address: InetAddress, val expiresElapsedMs: Long)

    private val dnsCache = ConcurrentHashMap<String, CachedIp>()
    private const val DNS_TTL_MS = 300_000L

    fun findPhysicalNetwork(context: Context?): Network? {
        if (context == null) return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        @Suppress("DEPRECATION")
        val all = try {
            cm.allNetworks
        } catch (_: Throwable) {
            emptyArray()
        }
        var cellularFallback: Network? = null
        for (net in all) {
            val caps = try {
                cm.getNetworkCapabilities(net)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    return net
                }
                if (cellularFallback == null) cellularFallback = net
            }
        }
        return cellularFallback
    }

    fun resolveEndpointHost(context: Context?, host: String): InetAddress {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (isIpLiteral(clean)) {
            return InetAddress.getByName(clean)
        }
        val now = SystemClock.elapsedRealtime()
        dnsCache[clean]?.let { cached ->
            if (cached.expiresElapsedMs > now) return cached.address
        }
        val net = findPhysicalNetwork(context)
        val addresses = if (net != null) {
            try {
                net.getAllByName(clean)
            } catch (_: Throwable) {
                InetAddress.getAllByName(clean)
            }
        } else {
            InetAddress.getAllByName(clean)
        }
        val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        dnsCache[clean] = CachedIp(preferred, now + DNS_TTL_MS)
        return preferred
    }

    fun openPhysicalHttpConnection(context: Context?, urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        val net = findPhysicalNetwork(context)
        val conn = if (net != null) {
            try {
                net.openConnection(url) as HttpURLConnection
            } catch (_: Throwable) {
                url.openConnection() as HttpURLConnection
            }
        } else {
            url.openConnection() as HttpURLConnection
        }
        return conn
    }

    fun isIpLiteral(s: String): Boolean {
        if (s.isEmpty()) return false
        if (':' in s) return true
        var dots = 0
        for (ch in s) {
            if (ch == '.') dots++
            else if (ch !in '0'..'9') return false
        }
        return dots == 3
    }
}

object RealVpnDialer {

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val permissiveSslContext: SSLContext by lazy {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    FatihStealthEngine.verifyCertificateNotFirewallMitm(chain)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
    }

    /**
     * Dials [endpoint] outside the Android TUN interface via [protectSocket], negotiates
     * transport (TCP / TLS / WebSocket) + VPN protocol (SS-AEAD / Trojan / VLESS / SOCKS5 / HTTP),
     * and optionally coalesces [initialPayload] into the very first protocol packet (0-RTT).
     * When [stealthMode] is enabled (FATİH / School NGFW Bypass), uses Direct-IP DoH + 3-record
     * TLS ClientHello SNI fragmentation + anti-MITM certificate verification.
     */
    fun openTunnel(
        context: Context?,
        endpoint: VpnEndpoint,
        targetHost: String,
        targetPort: Int,
        protectSocket: ((Socket) -> Boolean)?,
        connectTimeoutMs: Int = 3200,
        readTimeoutMs: Int = 0,
        initialPayload: ByteArray? = null,
        stealthMode: Boolean = false,
    ): TunnelSession {
        val rawSocket = Socket()
        try {
            if (protectSocket != null) {
                protectSocket.invoke(rawSocket)
            }
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            try {
                rawSocket.receiveBufferSize = 262_144
                rawSocket.sendBufferSize = 262_144
            } catch (_: Throwable) {
            }

            val resolvedAddr = if (stealthMode) {
                FatihStealthEngine.resolveHostStealth(context, endpoint.host, protectSocket)
            } else {
                PhysicalNetworkHelper.resolveEndpointHost(context, endpoint.host)
            }
            rawSocket.connect(InetSocketAddress(resolvedAddr, endpoint.port), connectTimeoutMs)
            rawSocket.soTimeout = if (connectTimeoutMs > 0) maxOf(connectTimeoutMs, 4000) else 5000

            val activeSocket: Socket = if (endpoint.tlsEnabled) {
                val sni = endpoint.tlsSni.ifBlank { endpoint.host }
                val baseSocket = if (stealthMode) {
                    FatihStealthEngine.wrapSocketForTlsFragmentation(rawSocket, sni)
                } else {
                    rawSocket
                }
                wrapTlsSocket(
                    rawSocket = baseSocket,
                    sni = sni,
                    alpn = endpoint.alpn,
                    insecure = endpoint.tlsInsecure || stealthMode,
                )
            } else {
                rawSocket
            }

            var transportIn: InputStream = BufferedInputStream(activeSocket.getInputStream(), 32_768)
            var transportOut: OutputStream = BufferedOutputStream(activeSocket.getOutputStream(), 32_768)

            if (endpoint.transport.equals("ws", ignoreCase = true)) {
                val wsHost = endpoint.wsHost.ifBlank { endpoint.tlsSni.ifBlank { endpoint.host } }
                val wsPair = negotiateWebSocket(transportIn, transportOut, wsHost, endpoint.wsPath)
                transportIn = wsPair.first
                transportOut = wsPair.second
            }

            val session = when (endpoint.protocol) {
                VpnProtocol.SHADOWSOCKS_AEAD -> setupShadowsocksAead(
                    rawSocket = activeSocket,
                    rawIn = transportIn,
                    rawOut = transportOut,
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    initialPayload = initialPayload,
                )

                VpnProtocol.TROJAN -> setupTrojan(
                    rawSocket = activeSocket,
                    rawIn = transportIn,
                    rawOut = transportOut,
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    initialPayload = initialPayload,
                )

                VpnProtocol.VLESS -> setupVless(
                    rawSocket = activeSocket,
                    rawIn = transportIn,
                    rawOut = transportOut,
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    initialPayload = initialPayload,
                )

                VpnProtocol.SOCKS5 -> setupUpstreamSocks5(
                    rawSocket = activeSocket,
                    rawIn = transportIn,
                    rawOut = transportOut,
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    initialPayload = initialPayload,
                )

                VpnProtocol.HTTP_CONNECT -> setupUpstreamHttpConnect(
                    rawSocket = activeSocket,
                    rawIn = transportIn,
                    rawOut = transportOut,
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    initialPayload = initialPayload,
                )
            }

            rawSocket.soTimeout = readTimeoutMs
            return session
        } catch (t: Throwable) {
            try {
                rawSocket.close()
            } catch (_: Throwable) {
            }
            if (t is IOException) throw t
            throw IOException("Tunnel dial failed (${endpoint.protocol}): ${t.message}", t)
        }
    }

    private fun wrapTlsSocket(
        rawSocket: Socket,
        sni: String,
        alpn: List<String>,
        insecure: Boolean,
    ): SSLSocket {
        val factory = if (insecure) {
            permissiveSslContext.socketFactory
        } else {
            SSLContext.getDefault().socketFactory
        }
        val ssl = factory.createSocket(rawSocket, sni, rawSocket.port, true) as SSLSocket
        ssl.useClientMode = true

        if (sni.isNotBlank() && !PhysicalNetworkHelper.isIpLiteral(sni)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && alpn.isNotEmpty()) {
                        params.applicationProtocols = alpn.toTypedArray()
                    }
                    ssl.sslParameters = params
                } catch (_: Throwable) {
                }
            } else {
                try {
                    ssl.javaClass.getMethod("setHostname", String::class.java).invoke(ssl, sni)
                } catch (_: Throwable) {
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && alpn.isNotEmpty()) {
            try {
                val params = ssl.sslParameters
                params.applicationProtocols = alpn.toTypedArray()
                ssl.sslParameters = params
            } catch (_: Throwable) {
            }
        }

        ssl.startHandshake()
        try {
            val certs = ssl.session.peerCertificates
                .mapNotNull { it as? X509Certificate }
                .toTypedArray()
            FatihStealthEngine.verifyCertificateNotFirewallMitm(certs)
        } catch (ce: java.security.cert.CertificateException) {
            ssl.close()
            throw IOException(ce.message ?: "SSL MITM blocked", ce)
        } catch (_: Throwable) {
        }
        return ssl
    }

    private fun negotiateWebSocket(
        rawIn: InputStream,
        rawOut: OutputStream,
        wsHost: String,
        wsPath: String,
    ): Pair<InputStream, OutputStream> {
        val path = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
        val key = RealVpnCrypto.encodeBase64(RealVpnCrypto.randomBytes(16))
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(wsHost).append("\r\n")
            append("Origin: https://").append(wsHost).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Pragma: no-cache\r\n")
            append("Cache-Control: no-cache\r\n")
            append("User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        rawOut.write(req.toByteArray(StandardCharsets.US_ASCII))
        rawOut.flush()

        val hdrBuf = ByteArrayOutputStream(256)
        var state = 0
        while (hdrBuf.size() < 8192) {
            val b = rawIn.read()
            if (b < 0) throw EOFException("WebSocket handshake closed by server")
            hdrBuf.write(b)
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
        val statusLine = hdrBuf.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains("101")) {
            throw IOException("WebSocket upgrade rejected: $statusLine")
        }

        val writeLock = Any()
        val wsOut = WebSocketBinaryOutputStream(rawOut, writeLock)
        val wsIn = WebSocketBinaryInputStream(rawIn, rawOut, writeLock)
        return wsIn to wsOut
    }

    private fun setupShadowsocksAead(
        rawSocket: Socket,
        rawIn: InputStream,
        rawOut: OutputStream,
        endpoint: VpnEndpoint,
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray?,
    ): TunnelSession {
        val ssOut = ShadowsocksAeadOutputStream(rawOut, endpoint.cipher, endpoint.password)
        val ssIn = ShadowsocksAeadInputStream(rawIn, endpoint.cipher, endpoint.password)
        val addrHeader = buildSocks5TargetAddress(targetHost, targetPort)
        if (initialPayload != null && initialPayload.isNotEmpty()) {
            val coalesced = ByteArray(addrHeader.size + initialPayload.size)
            System.arraycopy(addrHeader, 0, coalesced, 0, addrHeader.size)
            System.arraycopy(initialPayload, 0, coalesced, addrHeader.size, initialPayload.size)
            ssOut.write(coalesced)
        } else {
            ssOut.write(addrHeader)
        }
        ssOut.flush()
        return TunnelSession(rawSocket, ssIn, ssOut)
    }

    private fun setupTrojan(
        rawSocket: Socket,
        rawIn: InputStream,
        rawOut: OutputStream,
        endpoint: VpnEndpoint,
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray?,
    ): TunnelSession {
        val pwHex = RealVpnCrypto.sha224Hex(endpoint.password.toByteArray(StandardCharsets.UTF_8))
            .toByteArray(StandardCharsets.US_ASCII)
        val addrHeader = buildSocks5TargetAddress(targetHost, targetPort)
        val extraLen = initialPayload?.size ?: 0
        val buf = ByteArrayOutputStream(pwHex.size + 2 + 1 + addrHeader.size + 2 + extraLen)
        buf.write(pwHex)
        buf.write('\r'.code); buf.write('\n'.code)
        buf.write(0x01) // CONNECT
        buf.write(addrHeader)
        buf.write('\r'.code); buf.write('\n'.code)
        if (initialPayload != null && initialPayload.isNotEmpty()) {
            buf.write(initialPayload)
        }
        rawOut.write(buf.toByteArray())
        rawOut.flush()
        return TunnelSession(rawSocket, rawIn, rawOut)
    }

    private fun setupVless(
        rawSocket: Socket,
        rawIn: InputStream,
        rawOut: OutputStream,
        endpoint: VpnEndpoint,
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray?,
    ): TunnelSession {
        val parsedUuid = UUID.fromString(endpoint.uuid.trim())
        val uuidBytes = ByteArray(16)
        val msb = parsedUuid.mostSignificantBits
        val lsb = parsedUuid.leastSignificantBits
        for (i in 0..7) {
            uuidBytes[i] = ((msb ushr ((7 - i) * 8)) and 0xFFL).toByte()
            uuidBytes[8 + i] = ((lsb ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }

        val vlessAddr = buildVlessTargetAddress(targetHost, targetPort)
        val extraLen = initialPayload?.size ?: 0
        val buf = ByteArrayOutputStream(1 + 16 + 1 + 1 + vlessAddr.size + extraLen)
        buf.write(0x00) // VLESS v0
        buf.write(uuidBytes)
        buf.write(0x00) // Addons length = 0
        buf.write(0x01) // Command TCP = 1
        buf.write(vlessAddr)
        if (initialPayload != null && initialPayload.isNotEmpty()) {
            buf.write(initialPayload)
        }
        rawOut.write(buf.toByteArray())
        rawOut.flush()
        return TunnelSession(rawSocket, VlessResponseInputStream(rawIn), rawOut)
    }

    private fun setupUpstreamSocks5(
        rawSocket: Socket,
        rawIn: InputStream,
        rawOut: OutputStream,
        endpoint: VpnEndpoint,
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray?,
    ): TunnelSession {
        val hasAuth = endpoint.username.isNotEmpty()
        if (hasAuth) {
            rawOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            rawOut.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        rawOut.flush()
        val greet = ByteArray(2)
        readExact(rawIn, greet, 0, 2)
        if (greet[0].toInt() != 0x05) throw IOException("Invalid upstream SOCKS5 version")
        if ((greet[1].toInt() and 0xFF) == 0x02) {
            val u = endpoint.username.toByteArray(StandardCharsets.UTF_8)
            val p = endpoint.password.toByteArray(StandardCharsets.UTF_8)
            val authReq = ByteArray(3 + u.size + p.size)
            authReq[0] = 0x01
            authReq[1] = u.size.toByte()
            System.arraycopy(u, 0, authReq, 2, u.size)
            authReq[2 + u.size] = p.size.toByte()
            System.arraycopy(p, 0, authReq, 3 + u.size, p.size)
            rawOut.write(authReq)
            rawOut.flush()
            val authRes = ByteArray(2)
            readExact(rawIn, authRes, 0, 2)
            if (authRes[1].toInt() != 0x00) throw IOException("Upstream SOCKS5 auth failed")
        } else if ((greet[1].toInt() and 0xFF) != 0x00) {
            throw IOException("Upstream SOCKS5 rejected auth method")
        }

        val target = buildSocks5TargetAddress(targetHost, targetPort)
        val req = ByteArray(3 + target.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00
        System.arraycopy(target, 0, req, 3, target.size)
        rawOut.write(req)
        rawOut.flush()

        val head = ByteArray(4)
        readExact(rawIn, head, 0, 4)
        if (head[1].toInt() != 0x00) throw IOException("Upstream SOCKS5 connect error: ${head[1]}")
        when (head[3].toInt() and 0xFF) {
            0x01 -> readExact(rawIn, ByteArray(6), 0, 6)
            0x04 -> readExact(rawIn, ByteArray(18), 0, 18)
            0x03 -> {
                val dlen = rawIn.read()
                if (dlen < 0) throw EOFException()
                readExact(rawIn, ByteArray(dlen + 2), 0, dlen + 2)
            }
        }
        if (initialPayload != null && initialPayload.isNotEmpty()) {
            rawOut.write(initialPayload)
            rawOut.flush()
        }
        return TunnelSession(rawSocket, rawIn, rawOut)
    }

    private fun setupUpstreamHttpConnect(
        rawSocket: Socket,
        rawIn: InputStream,
        rawOut: OutputStream,
        endpoint: VpnEndpoint,
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray?,
    ): TunnelSession {
        val hostBracketed = if (':' in targetHost && !targetHost.startsWith("[")) "[$targetHost]" else targetHost
        val authHeader = if (endpoint.username.isNotEmpty()) {
            val token = RealVpnCrypto.encodeBase64(
                "${endpoint.username}:${endpoint.password}".toByteArray(StandardCharsets.UTF_8)
            )
            "Proxy-Authorization: Basic $token\r\n"
        } else ""
        val req = "CONNECT $hostBracketed:$targetPort HTTP/1.1\r\n" +
            "Host: $hostBracketed:$targetPort\r\n" +
            authHeader +
            "Proxy-Connection: keep-alive\r\n\r\n"
        rawOut.write(req.toByteArray(StandardCharsets.US_ASCII))
        rawOut.flush()

        val hdr = ByteArrayOutputStream(128)
        var state = 0
        while (hdr.size() < 8192) {
            val b = rawIn.read()
            if (b < 0) throw EOFException("HTTP proxy closed")
            hdr.write(b)
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
        val firstLine = hdr.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
        if (!firstLine.contains(" 200")) throw IOException("HTTP CONNECT failed: $firstLine")
        if (initialPayload != null && initialPayload.isNotEmpty()) {
            rawOut.write(initialPayload)
            rawOut.flush()
        }
        return TunnelSession(rawSocket, rawIn, rawOut)
    }

    /** SOCKS5 / Shadowsocks / Trojan target address: [ATYP][ADDR][PORT 2B BE]. */
    fun buildSocks5TargetAddress(host: String, port: Int): ByteArray {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        val out = ByteArrayOutputStream(32)
        if (PhysicalNetworkHelper.isIpLiteral(clean)) {
            val addr = InetAddress.getByName(clean)
            val raw = addr.address
            out.write(if (addr is Inet6Address || raw.size == 16) 0x04 else 0x01)
            out.write(raw)
        } else {
            val dom = clean.toByteArray(StandardCharsets.UTF_8)
            out.write(0x03)
            out.write(dom.size and 0xFF)
            out.write(dom)
        }
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        return out.toByteArray()
    }

    /** VLESS target address: [PORT 2B BE][ATYP: 1=IPv4, 2=Domain, 3=IPv6][ADDR]. */
    private fun buildVlessTargetAddress(host: String, port: Int): ByteArray {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        val out = ByteArrayOutputStream(32)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        if (PhysicalNetworkHelper.isIpLiteral(clean)) {
            val addr = InetAddress.getByName(clean)
            val raw = addr.address
            if (addr is Inet6Address || raw.size == 16) {
                out.write(0x03)
                out.write(raw)
            } else {
                out.write(0x01)
                out.write(raw)
            }
        } else {
            val dom = clean.toByteArray(StandardCharsets.UTF_8)
            out.write(0x02)
            out.write(dom.size and 0xFF)
            out.write(dom)
        }
        return out.toByteArray()
    }

    internal fun readExact(inp: InputStream, buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val r = inp.read(buf, off + done, len - done)
            if (r < 0) throw EOFException("Unexpected stream EOF")
            done += r
        }
    }
}

private class VlessResponseInputStream(private val rawIn: InputStream) : InputStream() {
    private var headerParsed = false
    private val one = ByteArray(1)

    private fun ensureHeader() {
        if (headerParsed) return
        val ver = rawIn.read()
        if (ver < 0) throw EOFException("VLESS empty response")
        val addonLen = rawIn.read()
        if (addonLen < 0) throw EOFException("VLESS truncated response header")
        if (addonLen > 0) {
            RealVpnDialer.readExact(rawIn, ByteArray(addonLen), 0, addonLen)
        }
        headerParsed = true
    }

    override fun read(): Int {
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        ensureHeader()
        return rawIn.read(b, off, len)
    }

    override fun close() = rawIn.close()
}

private class WebSocketBinaryOutputStream(
    private val rawOut: OutputStream,
    private val writeLock: Any,
) : OutputStream() {
    private val one = ByteArray(1)

    override fun write(b: Int) {
        one[0] = b.toByte()
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val mask = RealVpnCrypto.randomBytes(4)
        val hdrLen = when {
            len < 126 -> 6
            len <= 65535 -> 8
            else -> 14
        }
        val frame = ByteArray(hdrLen + len)
        frame[0] = 0x82.toByte() // FIN + Binary opcode
        var mOff: Int
        when {
            len < 126 -> {
                frame[1] = (0x80 or len).toByte()
                mOff = 2
            }
            len <= 65535 -> {
                frame[1] = (0x80 or 126).toByte()
                frame[2] = ((len ushr 8) and 0xFF).toByte()
                frame[3] = (len and 0xFF).toByte()
                mOff = 4
            }
            else -> {
                frame[1] = (0x80 or 127).toByte()
                val l = len.toLong()
                for (i in 0..7) {
                    frame[2 + i] = ((l ushr ((7 - i) * 8)) and 0xFFL).toByte()
                }
                mOff = 10
            }
        }
        System.arraycopy(mask, 0, frame, mOff, 4)
        val pOff = mOff + 4
        for (i in 0 until len) {
            frame[pOff + i] = (b[off + i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        synchronized(writeLock) {
            rawOut.write(frame)
        }
    }

    override fun flush() {
        synchronized(writeLock) {
            rawOut.flush()
        }
    }

    override fun close() = rawOut.close()
}

private class WebSocketBinaryInputStream(
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    private val writeLock: Any,
) : InputStream() {
    private var payloadBuf = ByteArray(0)
    private var payloadPos = 0
    private var closed = false
    private val one = ByteArray(1)

    override fun read(): Int {
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (payloadPos >= payloadBuf.size) {
            if (!readNextDataFrame()) return -1
        }
        val copy = minOf(len, payloadBuf.size - payloadPos)
        System.arraycopy(payloadBuf, payloadPos, b, off, copy)
        payloadPos += copy
        return copy
    }

    private fun readNextDataFrame(): Boolean {
        if (closed) return false
        while (true) {
            val b0 = rawIn.read()
            if (b0 < 0) {
                closed = true
                return false
            }
            val b1 = rawIn.read()
            if (b1 < 0) {
                closed = true
                return false
            }
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var frameLen = (b1 and 0x7F).toLong()
            if (frameLen == 126L) {
                val ext = ByteArray(2)
                RealVpnDialer.readExact(rawIn, ext, 0, 2)
                frameLen = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
            } else if (frameLen == 127L) {
                val ext = ByteArray(8)
                RealVpnDialer.readExact(rawIn, ext, 0, 8)
                frameLen = 0L
                for (i in 0..7) frameLen = (frameLen shl 8) or (ext[i].toLong() and 0xFFL)
            }
            if (frameLen > 4_194_304L) throw IOException("Oversized WebSocket frame: $frameLen")
            val maskKey = if (masked) {
                ByteArray(4).also { RealVpnDialer.readExact(rawIn, it, 0, 4) }
            } else null

            val data = ByteArray(frameLen.toInt())
            if (data.isNotEmpty()) {
                RealVpnDialer.readExact(rawIn, data, 0, data.size)
                if (maskKey != null) {
                    for (i in data.indices) {
                        data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                    }
                }
            }

            when (opcode) {
                0x00, 0x01, 0x02 -> {
                    if (data.isNotEmpty()) {
                        payloadBuf = data
                        payloadPos = 0
                        return true
                    }
                }
                0x08 -> { // Close
                    closed = true
                    return false
                }
                0x09 -> { // Ping -> reply masked Pong
                    sendControlPong(data)
                }
            }
        }
    }

    private fun sendControlPong(pingPayload: ByteArray) {
        try {
            val clipped = if (pingPayload.size > 125) pingPayload.copyOf(125) else pingPayload
            val mask = RealVpnCrypto.randomBytes(4)
            val pong = ByteArray(6 + clipped.size)
            pong[0] = 0x8A.toByte()
            pong[1] = (0x80 or clipped.size).toByte()
            System.arraycopy(mask, 0, pong, 2, 4)
            for (i in clipped.indices) {
                pong[6 + i] = (clipped[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            synchronized(writeLock) {
                rawOut.write(pong)
                rawOut.flush()
            }
        } catch (_: Throwable) {
        }
    }

    override fun close() = rawIn.close()
}
