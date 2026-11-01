package com.sametgkte.gktedpi

import com.sametgkte.gktedpi.shizuku.LocalHttpToSocksBridge
import com.sametgkte.gktedpi.smart.AppTriggerManager
import com.sametgkte.gktedpi.smart.SmartSniEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SmartSniAndBridgeTest {

    @Test
    fun smartSniZeroPingBypassPrependsEmptyHostGroup() {
        val raw = listOf("ciadpi", "-i", "127.0.0.1", "-p", "1080", "-F", "-d", "1", "-r", "1+s")
        val rewritten = SmartSniEngine.rewriteArgsWithHosts(
            rawArgs = raw,
            hostsFilePath = "/tmp/bypass.txt",
            mode = SmartSniEngine.Mode.BYPASS_SAFE_DOMAINS,
        )
        assertEquals(
            listOf(
                "ciadpi", "-i", "127.0.0.1", "-p", "1080", "-F",
                "-H", "/tmp/bypass.txt",
                "-A", "n",
                "-d", "1", "-r", "1+s"
            ),
            rewritten
        )
    }

    @Test
    fun smartSniOnlyBlockedInjectsHostsIntoEveryAutoGroup() {
        val raw = listOf("ciadpi", "-i", "127.0.0.1", "-p", "1080", "-d", "1", "-A", "t,r,s", "-f", "-1")
        val rewritten = SmartSniEngine.rewriteArgsWithHosts(
            rawArgs = raw,
            hostsFilePath = "/tmp/blocked.txt",
            mode = SmartSniEngine.Mode.ONLY_BLOCKED_DOMAINS,
        )
        assertEquals(
            listOf(
                "ciadpi", "-i", "127.0.0.1", "-p", "1080",
                "-H", "/tmp/blocked.txt", "-d", "1",
                "-A", "t,r,s", "-H", "/tmp/blocked.txt", "-f", "-1",
                "-A", "n"
            ),
            rewritten
        )
    }

    @Test
    fun appTriggerTransientPackagesAreIgnored() {
        assertTrue(AppTriggerManager.isTransientSystemPackage("com.android.systemui"))
        assertTrue(AppTriggerManager.isTransientSystemPackage("com.google.android.inputmethod.latin"))
        assertFalse(AppTriggerManager.isTransientSystemPackage("com.discord"))
        assertFalse(AppTriggerManager.isTransientSystemPackage("com.sametgkte.gktetok"))
    }

    @Test
    fun localHttpConnectToSocks5PreservesDomainAtypAndRelaysPayload() {
        val capturedDomain = AtomicReference<String>()
        val capturedPort = AtomicReference<Int>()
        val serverReady = CountDownLatch(1)

        val mockSocksServer = ServerSocket(0)
        val socksPort = mockSocksServer.localPort
        val bridgePortSocket = ServerSocket(0)
        val bridgePort = bridgePortSocket.localPort
        bridgePortSocket.close()

        val socksThread = Thread {
            serverReady.countDown()
            mockSocksServer.use { srv ->
                val s = srv.accept()
                s.use { conn ->
                    val inp = conn.getInputStream()
                    val out = conn.getOutputStream()
                    // Greeting
                    val greet = ByteArray(3)
                    inp.read(greet)
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                    // Request header
                    val head = ByteArray(5)
                    inp.read(head)
                    assertEquals(0x03, head[3].toInt() and 0xFF) // Domain ATYP!
                    val len = head[4].toInt() and 0xFF
                    val domBytes = ByteArray(len)
                    inp.read(domBytes)
                    val pHi = inp.read()
                    val pLo = inp.read()
                    capturedDomain.set(String(domBytes, StandardCharsets.UTF_8))
                    capturedPort.set((pHi shl 8) or pLo)

                    // Reply success
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                    out.flush()

                    // Read client ping & echo back pong
                    val ping = ByteArray(4)
                    inp.read(ping)
                    if (String(ping, StandardCharsets.UTF_8) == "PING") {
                        out.write("PONG".toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }
                }
            }
        }
        socksThread.start()
        assertTrue(serverReady.await(3, TimeUnit.SECONDS))

        val bridge = LocalHttpToSocksBridge(
            bindHost = "127.0.0.1",
            listenPort = bridgePort,
            socksHost = "127.0.0.1",
            socksPort = socksPort,
        )
        bridge.start()
        try {
            Thread.sleep(120)
            Socket().use { client ->
                client.soTimeout = 4000
                client.connect(InetSocketAddress("127.0.0.1", bridgePort), 3000)
                val out = client.getOutputStream()
                val inp = client.getInputStream()
                out.write("CONNECT discord.com:443 HTTP/1.1\r\nHost: discord.com:443\r\n\r\n".toByteArray())
                out.flush()

                val respBuf = ByteArray(64)
                val n = inp.read(respBuf)
                val respStr = String(respBuf, 0, n, StandardCharsets.ISO_8859_1)
                assertTrue(respStr.contains("200"))

                out.write("PING".toByteArray())
                out.flush()
                val pong = ByteArray(4)
                val pn = inp.read(pong)
                assertEquals("PONG", String(pong, 0, pn, StandardCharsets.UTF_8))
            }
            assertEquals("discord.com", capturedDomain.get())
            assertEquals(443, capturedPort.get())
        } finally {
            bridge.stop()
        }
    }
}
