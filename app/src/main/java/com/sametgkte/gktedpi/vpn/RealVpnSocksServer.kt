package com.sametgkte.gktedpi.vpn

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * High-concurrency local SOCKS5 + HEV FWD_UDP + RFC1928 UDP_ASSOCIATE relay for Real Country VPN.
 * Bridges native C libhev-socks5-tunnel.so (TUN) to encrypted remote country endpoints with:
 *  - 0-RTT Initial Payload Coalescing (cuts 1 full RTT per HTTPS/TCP connection)
 *  - Automatic Hot-Standby Node Failover + Background Self-Healing
 *  - Encrypted Remote-Tunnel DNS-over-TCP + 90s In-Memory DNS Cache (Zero DNS Leak)
 */
class RealVpnSocksServer(
    private val context: Context,
    private val listenPort: Int,
    private val requestedCountryOrAuto: String,
    initialPool: VerifiedNodePool,
    private val protectSocket: (Socket) -> Boolean,
) {
    companion object {
        private const val TAG = "RealVpnSocksServer"
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val CMD_HEV_FWD_UDP = 0x05
        private const val DNS_CACHE_TTL_MS = 90_000L
    }

    private data class CachedDnsEntry(val responseWithoutTxId: ByteArray, val expiresElapsedMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val orderedNodesRef = AtomicReference<List<VpnEndpoint>>(initialPool.allOrdered())
    private val consecutiveFailures = AtomicInteger(0)
    private val reoptimizing = AtomicReference(false)
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()

    @Volatile
    private var stealthActive: Boolean =
        initialPool.fatihStealthActive || VpnEngineState.isFatihStealthEnabled(context)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 256)
        serverSocket = srv

        acceptJob = scope.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: Throwable) {
                    if (srv.isClosed) break else continue
                }
                scope.launch {
                    handleClientConnection(client)
                }
            }
        }
        Log.i(TAG, "RealVpnSocksServer listening on 127.0.0.1:$listenPort (${initialPoolSummary()})")
    }

    private fun initialPoolSummary(): String {
        val list = orderedNodesRef.get()
        val first = list.firstOrNull() ?: return "empty"
        return "${first.countryCode} ${first.protocol.badge} ${first.host}:${first.port} (+${list.size - 1} standby)"
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptJob?.cancel()
        scope.cancel()
        dnsCache.clear()
    }

    private suspend fun handleClientConnection(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val inp = BufferedInputStream(client.getInputStream(), 32_768)
            val out = BufferedOutputStream(client.getOutputStream(), 32_768)

            // 1. SOCKS5 Auth negotiation (supports both standard & pipelined handshakes)
            val ver = inp.read()
            if (ver != 0x05) {
                client.close()
                return
            }
            val nMethods = inp.read()
            if (nMethods <= 0) {
                client.close()
                return
            }
            RealVpnDialer.readExact(inp, ByteArray(nMethods), 0, nMethods)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // 2. SOCKS5 Request header [VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT]
            val reqHead = ByteArray(4)
            RealVpnDialer.readExact(inp, reqHead, 0, 4)
            val cmd = reqHead[1].toInt() and 0xFF
            val atyp = reqHead[3].toInt() and 0xFF
            val (dstHost, dstPort) = readSocks5Address(inp, atyp)

            when (cmd) {
                CMD_CONNECT -> handleTcpConnect(client, inp, out, dstHost, dstPort)
                CMD_HEV_FWD_UDP -> handleHevFwdUdp(client, inp, out)
                CMD_UDP_ASSOCIATE -> handleStandardUdpAssociate(client, inp, out)
                else -> {
                    out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    client.close()
                }
            }
        } catch (_: Throwable) {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun handleTcpConnect(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dstHost: String,
        dstPort: Int,
    ) {
        // Optimistic fast-open reply so local lwIP stack immediately releases ClientHello / HTTP request
        clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
        clientOut.flush()

        // Peek initial payload (e.g. TLS ClientHello) with a short 35ms window for 0-RTT coalescing
        val initialPayload = peekInitialBytes(client, clientIn, waitMs = 35)
        client.soTimeout = 0

        val tunnel = dialWithFailover(
            targetHost = dstHost,
            targetPort = dstPort,
            initialPayload = initialPayload,
            connectTimeoutMs = 3500,
        )
        if (tunnel == null) {
            client.close()
            return
        }

        tunnel.use { remote ->
            val upJob = scope.launch {
                pipeStream(clientIn, remote.output)
                try {
                    remote.rawSocket.shutdownOutput()
                } catch (_: Throwable) {
                }
            }
            val downJob = scope.launch {
                pipeStream(remote.input, clientOut)
                try {
                    client.shutdownOutput()
                } catch (_: Throwable) {
                }
            }
            upJob.join()
            downJob.join()
        }
        try {
            client.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * Handles hev-socks5-tunnel native UDP-in-TCP (`udp: 'tcp'`, CMD = 0x05).
     * Frame layout: [uint16 BE datlen][uint8 hdrlen][HevSocks5Addr (hdrlen - 3 bytes)][payload].
     */
    private suspend fun handleHevFwdUdp(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
        clientOut.flush()
        client.soTimeout = 0
        val writeLock = Any()

        while (scope.isActive && !client.isClosed) {
            val head4 = ByteArray(4)
            try {
                RealVpnDialer.readExact(clientIn, head4, 0, 4)
            } catch (_: EOFException) {
                break
            }
            val datLen = ((head4[0].toInt() and 0xFF) shl 8) or (head4[1].toInt() and 0xFF)
            val hdrLen = head4[2].toInt() and 0xFF
            val atyp = head4[3].toInt() and 0xFF
            val addrRemLen = hdrLen - 4
            if (addrRemLen < 2 || datLen < 0 || datLen > 65535) break

            val addrBytes = ByteArray(addrRemLen)
            RealVpnDialer.readExact(clientIn, addrBytes, 0, addrRemLen)
            val payload = ByteArray(datLen)
            if (datLen > 0) {
                RealVpnDialer.readExact(clientIn, payload, 0, datLen)
            }

            val parsedTarget = parseRawAddrTail(atyp, addrBytes) ?: continue
            val (dstIp, dstPort) = parsedTarget

            // Fast-path DNS over encrypted remote TCP tunnel (+ RAM cache); drop UDP 443 (QUIC -> instant TCP/TLS fallback)
            if (dstPort == 53 && payload.size >= 12) {
                scope.launch {
                    val dnsResp = resolveDnsOverEncryptedTunnel(dstIp, payload) ?: return@launch
                    val replyHdrLen = 4 + addrBytes.size
                    val frame = ByteArray(replyHdrLen + dnsResp.size)
                    frame[0] = ((dnsResp.size ushr 8) and 0xFF).toByte()
                    frame[1] = (dnsResp.size and 0xFF).toByte()
                    frame[2] = replyHdrLen.toByte()
                    frame[3] = atyp.toByte()
                    System.arraycopy(addrBytes, 0, frame, 4, addrBytes.size)
                    System.arraycopy(dnsResp, 0, frame, replyHdrLen, dnsResp.size)
                    synchronized(writeLock) {
                        try {
                            clientOut.write(frame)
                            clientOut.flush()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        try {
            client.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * Handles standard RFC 1928 UDP ASSOCIATE (`udp: 'udp'`, CMD = 0x03).
     */
    private suspend fun handleStandardUdpAssociate(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        val udpSock = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        try {
            val localPort = udpSock.localPort
            clientOut.write(
                byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((localPort ushr 8) and 0xFF).toByte(),
                    (localPort and 0xFF).toByte()
                )
            )
            clientOut.flush()
            client.soTimeout = 0

            val udpJob = scope.launch {
                val buf = ByteArray(4096)
                while (isActive && !udpSock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        udpSock.receive(pkt)
                    } catch (_: Throwable) {
                        break
                    }
                    val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    if (data.size < 10) continue
                    val atyp = data[3].toInt() and 0xFF
                    val hdrLen = when (atyp) {
                        0x01 -> 10
                        0x04 -> 22
                        else -> continue
                    }
                    if (data.size <= hdrLen) continue
                    val addrTail = data.copyOfRange(4, hdrLen)
                    val (dstIp, dstPort) = parseRawAddrTail(atyp, addrTail) ?: continue
                    val payload = data.copyOfRange(hdrLen, data.size)
                    val senderSocketAddr = pkt.socketAddress

                    if (dstPort == 53 && payload.size >= 12) {
                        scope.launch {
                            val dnsResp = resolveDnsOverEncryptedTunnel(dstIp, payload) ?: return@launch
                            val reply = ByteArray(hdrLen + dnsResp.size)
                            System.arraycopy(data, 0, reply, 0, hdrLen)
                            System.arraycopy(dnsResp, 0, reply, hdrLen, dnsResp.size)
                            try {
                                udpSock.send(DatagramPacket(reply, reply.size, senderSocketAddr))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }

            // Keep UDP association alive until TCP control socket closes
            val sink = ByteArray(64)
            while (clientIn.read(sink) >= 0) {
                // wait for EOF
            }
            udpJob.cancel()
        } finally {
            try {
                udpSock.close()
            } catch (_: Throwable) {
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveDnsOverEncryptedTunnel(preferredDnsIp: String, udpQuery: ByteArray): ByteArray? {
        if (udpQuery.size < 12) return null
        val txId0 = udpQuery[0]
        val txId1 = udpQuery[1]
        val cacheKey = buildDnsCacheKey(udpQuery)
        val now = SystemClock.elapsedRealtime()

        if (cacheKey != null) {
            dnsCache[cacheKey]?.let { hit ->
                if (hit.expiresElapsedMs > now && hit.responseWithoutTxId.size >= 10) {
                    val out = ByteArray(2 + hit.responseWithoutTxId.size)
                    out[0] = txId0
                    out[1] = txId1
                    System.arraycopy(hit.responseWithoutTxId, 0, out, 2, hit.responseWithoutTxId.size)
                    return out
                }
            }
        }

        val tcpDnsReq = ByteArray(2 + udpQuery.size)
        tcpDnsReq[0] = ((udpQuery.size ushr 8) and 0xFF).toByte()
        tcpDnsReq[1] = (udpQuery.size and 0xFF).toByte()
        System.arraycopy(udpQuery, 0, tcpDnsReq, 2, udpQuery.size)

        val primaryNode = orderedNodesRef.get().firstOrNull()
        val isCfWsNode = primaryNode != null &&
            primaryNode.transport.equals("ws", ignoreCase = true) &&
            FatihStealthEngine.looksLikeCloudflareFronted(primaryNode)

        val dnsCandidates = buildList {
            if (isCfWsNode || stealthActive) {
                add("8.8.8.8")
                add("9.9.9.9")
            }
            if (preferredDnsIp.isNotBlank() && !preferredDnsIp.startsWith("198.18.")) {
                add(preferredDnsIp)
            }
            add("8.8.8.8")
            add("1.1.1.1")
        }.distinct()

        for (dnsServer in dnsCandidates.take(2)) {
            val session = dialWithFailover(
                targetHost = dnsServer,
                targetPort = 53,
                initialPayload = tcpDnsReq,
                connectTimeoutMs = 2800,
            ) ?: continue

            try {
                session.rawSocket.soTimeout = 3500
                val lenBytes = ByteArray(2)
                RealVpnDialer.readExact(session.input, lenBytes, 0, 2)
                val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                if (respLen !in 12..4096) continue
                val resp = ByteArray(respLen)
                RealVpnDialer.readExact(session.input, resp, 0, respLen)

                if (cacheKey != null && (resp[3].toInt() and 0x0F) == 0) {
                    if (dnsCache.size > 256) dnsCache.clear()
                    dnsCache[cacheKey] = CachedDnsEntry(
                        responseWithoutTxId = resp.copyOfRange(2, resp.size),
                        expiresElapsedMs = now + DNS_CACHE_TTL_MS,
                    )
                }
                return resp
            } catch (_: Throwable) {
                // try backup DNS resolver
            } finally {
                session.close()
            }
        }
        return null
    }

    private fun buildDnsCacheKey(query: ByteArray): String? {
        if (query.size <= 12) return null
        // Bytes 2..end contain flags + QDCOUNT + Question section (invariant across txId)
        val sliceLen = minOf(query.size - 2, 96)
        return RealVpnCrypto.encodeBase64(query.copyOfRange(2, 2 + sliceLen))
    }

    private fun dialWithFailover(
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray?,
        connectTimeoutMs: Int,
    ): TunnelSession? {
        val currentList = orderedNodesRef.get()
        if (currentList.isEmpty()) return null

        val maxAttempts = minOf(currentList.size, 3)
        for (idx in 0 until maxAttempts) {
            val candidate = currentList[idx]
            try {
                val session = RealVpnDialer.openTunnel(
                    context = context,
                    endpoint = candidate,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    protectSocket = protectSocket,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = 0,
                    initialPayload = initialPayload,
                    stealthMode = stealthActive,
                )
                consecutiveFailures.set(0)
                if (idx > 0) {
                    promoteCandidateToPrimary(candidate)
                }
                return session
            } catch (_: Throwable) {
                // Try next hot-standby node immediately
            }
        }

        val fails = consecutiveFailures.incrementAndGet()
        if (fails >= 3) {
            triggerBackgroundReoptimize()
        }
        return null
    }

    private fun promoteCandidateToPrimary(winner: VpnEndpoint) {
        val cur = orderedNodesRef.get()
        if (cur.firstOrNull()?.uniqueKey() == winner.uniqueKey()) return
        val rotated = buildList {
            add(winner)
            for (ep in cur) {
                if (ep.uniqueKey() != winner.uniqueKey()) add(ep)
            }
        }
        orderedNodesRef.set(rotated)
        VpnEngineState.updateSnapshot(
            VpnEngineState.snapshot.copy(
                countryCode = winner.countryCode,
                serverLabel = winner.label,
                exitIp = winner.verifiedExitIp.ifBlank { VpnEngineState.snapshot.exitIp },
                latencyMs = if (winner.measuredLatencyMs > 0) winner.measuredLatencyMs else VpnEngineState.snapshot.latencyMs,
                protocolBadge = winner.protocol.badge,
            )
        )
    }

    private fun triggerBackgroundReoptimize() {
        if (!reoptimizing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val fresh = RealVpnNodeManager.selectOptimalPool(
                    context = context,
                    countryOrAuto = requestedCountryOrAuto,
                    protectSocket = protectSocket,
                )
                orderedNodesRef.set(fresh.allOrdered())
                stealthActive = fresh.fatihStealthActive || VpnEngineState.isFatihStealthEnabled(context)
                consecutiveFailures.set(0)
                VpnEngineState.updateSnapshot(
                    RealVpnRuntimeSnapshot(
                        connecting = false,
                        connected = true,
                        countryCode = fresh.resolvedCountryCode,
                        serverLabel = fresh.primary.label,
                        exitIp = fresh.exitIp,
                        latencyMs = fresh.latencyMs,
                        protocolBadge = fresh.primary.protocol.badge,
                        standbyCount = fresh.standbys.size,
                        fatihStealthActive = stealthActive,
                    )
                )
            } catch (_: Throwable) {
            } finally {
                reoptimizing.set(false)
            }
        }
    }

    private fun peekInitialBytes(client: Socket, inp: InputStream, waitMs: Int): ByteArray? {
        return try {
            client.soTimeout = waitMs
            val buf = ByteArray(8192)
            val n = inp.read(buf)
            if (n > 0) buf.copyOf(n) else null
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun pipeStream(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(32_768)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n > 0) {
                    out.write(buf, 0, n)
                    if (inp.available() <= 0) {
                        out.flush()
                    }
                }
            }
            out.flush()
        } catch (_: Throwable) {
        }
    }

    private fun readSocks5Address(inp: InputStream, atyp: Int): Pair<String, Int> {
        val host = when (atyp) {
            0x01 -> {
                val ip = ByteArray(4)
                RealVpnDialer.readExact(inp, ip, 0, 4)
                InetAddress.getByAddress(ip).hostAddress ?: "0.0.0.0"
            }
            0x04 -> {
                val ip = ByteArray(16)
                RealVpnDialer.readExact(inp, ip, 0, 16)
                InetAddress.getByAddress(ip).hostAddress ?: "::"
            }
            0x03 -> {
                val len = inp.read()
                if (len <= 0) throw EOFException("Invalid SOCKS5 domain length")
                val dom = ByteArray(len)
                RealVpnDialer.readExact(inp, dom, 0, len)
                String(dom, StandardCharsets.UTF_8)
            }
            else -> throw IllegalArgumentException("Unsupported SOCKS5 ATYP: $atyp")
        }
        val p = ByteArray(2)
        RealVpnDialer.readExact(inp, p, 0, 2)
        val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        return host to port
    }

    private fun parseRawAddrTail(atyp: Int, tail: ByteArray): Pair<String, Int>? {
        return try {
            when (atyp) {
                0x01 -> {
                    if (tail.size < 6) return null
                    val host = InetAddress.getByAddress(tail.copyOfRange(0, 4)).hostAddress ?: return null
                    val port = ((tail[4].toInt() and 0xFF) shl 8) or (tail[5].toInt() and 0xFF)
                    host to port
                }
                0x04 -> {
                    if (tail.size < 18) return null
                    val host = InetAddress.getByAddress(tail.copyOfRange(0, 16)).hostAddress ?: return null
                    val port = ((tail[16].toInt() and 0xFF) shl 8) or (tail[17].toInt() and 0xFF)
                    host to port
                }
                0x03 -> {
                    val dlen = tail[0].toInt() and 0xFF
                    if (tail.size < 1 + dlen + 2) return null
                    val host = String(tail, 1, dlen, StandardCharsets.UTF_8)
                    val pOff = 1 + dlen
                    val port = ((tail[pOff].toInt() and 0xFF) shl 8) or (tail[pOff + 1].toInt() and 0xFF)
                    host to port
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
