package com.sametgkte.gktedpi.shizuku

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Yerel HTTP CONNECT / HTTP -> SOCKS5 (ATYP=0x03 Domain) Köprüsü.
 *
 * Android `settings put global http_proxy` ve PC/PS5/Xbox/TV Wi-Fi proxy ayarları
 * yalnızca HTTP CONNECT konuştuğu için, gelen HTTP tünel isteklerini domain bilgisini
 * koruyarak doğrudan yerel `byedpi` SOCKS5 motoruna (`127.0.0.1:1080`) aktarır.
 * Böylece Android VPN yuvası (`VpnService`) meşgul edilmeden tam SNI/DPI aşma sağlanır!
 */
class LocalHttpToSocksBridge(
    private val bindHost: String = "127.0.0.1",
    val listenPort: Int = 8118,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 1080,
) {
    companion object {
        private const val TAG = "HttpToSocksBridge"
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val exec = Executors.newCachedThreadPool()
        pool = exec
        exec.execute {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(InetAddress.getByName(bindHost), listenPort), 128)
                serverSocket = srv
                try { Log.i(TAG, "HTTP->SOCKS5 bridge listening on $bindHost:$listenPort -> $socksHost:$socksPort") } catch (_: Throwable) {}
                while (running.get() && !srv.isClosed) {
                    val client = try {
                        srv.accept()
                    } catch (_: Exception) {
                        break
                    }
                    exec.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                try { Log.w(TAG, "Bridge server stopped: ${e.message}") } catch (_: Throwable) {}
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 20_000
            val clientIn = BufferedInputStream(client.getInputStream(), 8192)
            val clientOut = client.getOutputStream()

            val headerBytes = readHttpHeaderBlock(clientIn) ?: return
            val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val targetRaw = parts[1]

            if (method == "CONNECT") {
                val (host, port) = parseHostPort(targetRaw, 443) ?: return
                upstream = connectViaSocks5(host, port)
                client.soTimeout = 0
                upstream.soTimeout = 0
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                clientOut.flush()
                relayBidirectional(client, clientIn, clientOut, upstream)
            } else {
                // Plain HTTP proxy request (e.g. GET http://example.com/path HTTP/1.1)
                val uri = try { URI(targetRaw) } catch (_: Exception) { null }
                val hostHeader = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                val hostPort = if (uri?.host != null) {
                    uri.host to (if (uri.port > 0) uri.port else 80)
                } else if (!hostHeader.isNullOrBlank()) {
                    parseHostPort(hostHeader, 80) ?: return
                } else return

                upstream = connectViaSocks5(hostPort.first, hostPort.second)
                val pathAndQuery = buildString {
                    val p = uri?.rawPath
                    append(if (p.isNullOrEmpty()) "/" else p)
                    if (!uri?.rawQuery.isNullOrEmpty()) {
                        append('?').append(uri?.rawQuery)
                    }
                }
                val rewrittenFirst = "$method $pathAndQuery ${parts.getOrElse(2) { "HTTP/1.1" }}"
                val rebuilt = buildString {
                    append(rewrittenFirst).append("\r\n")
                    for (idx in 1 until lines.size) {
                        val ln = lines[idx]
                        if (ln.isEmpty()) continue
                        if (ln.startsWith("Proxy-Connection:", ignoreCase = true)) continue
                        append(ln).append("\r\n")
                    }
                    append("\r\n")
                }
                val upOut = upstream.getOutputStream()
                upOut.write(rebuilt.toByteArray(StandardCharsets.ISO_8859_1))
                upOut.flush()
                client.soTimeout = 0
                upstream.soTimeout = 0
                relayBidirectional(client, clientIn, clientOut, upstream)
            }
        } catch (_: Exception) {
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun connectViaSocks5(targetHost: String, targetPort: Int): Socket {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(socksHost, socksPort), 8_000)
        sock.soTimeout = 12_000
        val sin = sock.getInputStream()
        val sout = sock.getOutputStream()

        // SOCKS5 greeting: VER=5, NMETHODS=1, NO_AUTH=0
        sout.write(byteArrayOf(0x05, 0x01, 0x00))
        sout.flush()
        val greetResp = ByteArray(2)
        readFully(sin, greetResp)
        if (greetResp[0].toInt() != 0x05 || greetResp[1].toInt() != 0x00) {
            sock.close()
            throw IllegalStateException("SOCKS5 greeting rejected")
        }

        val domainBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
        if (domainBytes.isEmpty() || domainBytes.size > 255) {
            sock.close()
            throw IllegalArgumentException("Invalid host length")
        }
        val req = ByteArray(4 + 1 + domainBytes.size + 2)
        req[0] = 0x05 // VER
        req[1] = 0x01 // CMD = CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // ATYP = DOMAINNAME (preserves hostname for ByeDPI SNI/Host engine!)
        req[4] = domainBytes.size.toByte()
        System.arraycopy(domainBytes, 0, req, 5, domainBytes.size)
        val pOff = 5 + domainBytes.size
        req[pOff] = ((targetPort ushr 8) and 0xFF).toByte()
        req[pOff + 1] = (targetPort and 0xFF).toByte()
        sout.write(req)
        sout.flush()

        val head = ByteArray(4)
        readFully(sin, head)
        if (head[1].toInt() != 0x00) {
            sock.close()
            throw IllegalStateException("SOCKS5 connect failed: ${head[1]}")
        }
        when (head[3].toInt() and 0xFF) {
            0x01 -> readFully(sin, ByteArray(4 + 2))
            0x03 -> {
                val len = sin.read()
                if (len > 0) readFully(sin, ByteArray(len + 2))
            }
            0x04 -> readFully(sin, ByteArray(16 + 2))
        }
        return sock
    }

    private fun relayBidirectional(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: Socket,
    ) {
        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val exec = pool
        val t = Thread {
            try {
                copyStream(clientIn, upOut)
                try { upstream.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
            }
        }
        if (exec != null && !exec.isShutdown) {
            exec.execute(t)
        } else {
            t.start()
        }
        try {
            copyStream(upIn, clientOut)
            try { client.shutdownOutput() } catch (_: Exception) {}
        } catch (_: Exception) {
        }
    }

    private fun copyStream(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(16_384)
        while (true) {
            val n = inp.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            out.flush()
        }
    }

    private fun readFully(inp: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = inp.read(dst, off, dst.size - off)
            if (n < 0) throw IllegalStateException("EOF")
            off += n
        }
    }

    private fun readHttpHeaderBlock(inp: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream(512)
        var state = 0
        while (out.size() < 16_384) {
            val b = inp.read()
            if (b < 0) return null
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun parseHostPort(raw: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close <= 1) return null
            val host = trimmed.substring(1, close)
            val port = if (close + 1 < trimmed.length && trimmed[close + 1] == ':') {
                trimmed.substring(close + 2).toIntOrNull() ?: defaultPort
            } else defaultPort
            host to port
        } else {
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                val h = trimmed.substring(0, colon)
                val p = trimmed.substring(colon + 1).toIntOrNull() ?: defaultPort
                h to p
            } else {
                trimmed to defaultPort
            }
        }
    }
}
