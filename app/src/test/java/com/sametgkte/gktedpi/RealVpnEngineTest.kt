package com.sametgkte.gktedpi

import com.sametgkte.gktedpi.profiles.BuiltInPresets
import com.sametgkte.gktedpi.vpn.FatihStealthEngine
import com.sametgkte.gktedpi.vpn.RealVpnCrypto
import com.sametgkte.gktedpi.vpn.RealVpnNodeManager
import com.sametgkte.gktedpi.vpn.ShadowsocksAeadInputStream
import com.sametgkte.gktedpi.vpn.ShadowsocksAeadOutputStream
import com.sametgkte.gktedpi.vpn.VpnCountries
import com.sametgkte.gktedpi.vpn.VpnEndpoint
import com.sametgkte.gktedpi.vpn.VpnProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class RealVpnEngineTest {

    @Test
    fun `chacha20 poly1305 encrypts and decrypts multi-size payloads accurately`() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        for (len in listOf(0, 1, 15, 16, 17, 63, 64, 65, 517, 4096, 16383)) {
            val plain = ByteArray(len) { ((it * 31) xor 0x5A).toByte() }
            val ct = RealVpnCrypto.chacha20Poly1305Encrypt(key, nonce, plain)
            assertEquals(len + 16, ct.size)
            val dec = RealVpnCrypto.chacha20Poly1305Decrypt(key, nonce, ct)
            assertArrayEquals(plain, dec)
        }
    }

    @Test
    fun `shadowsocks aead streams roundtrip coalesced and multi-chunk payloads`() {
        for (cipher in listOf("chacha20-ietf-poly1305", "aes-256-gcm", "aes-128-gcm")) {
            val pipe = ByteArrayOutputStream()
            val ssOut = ShadowsocksAeadOutputStream(pipe, cipher, "GkTeSecretPass2026!")
            val bigPayload = ByteArray(25_000) { (it % 251).toByte() }
            ssOut.write(bigPayload, 0, 500)
            ssOut.write(bigPayload, 500, bigPayload.size - 500)
            ssOut.flush()

            val ssIn = ShadowsocksAeadInputStream(ByteArrayInputStream(pipe.toByteArray()), cipher, "GkTeSecretPass2026!")
            val recovered = ssIn.readBytes()
            assertArrayEquals("Failed for $cipher", bigPayload, recovered)
        }
    }

    @Test
    fun `sha224 hex matches FIPS 180-4 vector for trojan auth`() {
        val digest = RealVpnCrypto.sha224Hex("abc".toByteArray(StandardCharsets.US_ASCII))
        assertEquals("23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7", digest)
    }

    @Test
    fun `singbox feed and share URI parsers extract supported endpoints and skip reality`() {
        val sampleFeed = """
            {
              "outbounds": [
                {
                  "type": "shadowsocks",
                  "server": "82.38.31.66",
                  "server_port": 8080,
                  "method": "chacha20-ietf-poly1305",
                  "password": "pass1",
                  "tag": "NL-SS"
                },
                {
                  "type": "trojan",
                  "server": "66.163.114.45",
                  "server_port": 443,
                  "password": "pw2",
                  "tls": { "enabled": true, "server_name": "cdn.example.com" },
                  "transport": { "type": "ws", "path": "/ws", "headers": { "Host": "cdn.example.com" } },
                  "tag": "US-Trojan"
                },
                {
                  "type": "vless",
                  "server": "1.2.3.4",
                  "server_port": 443,
                  "uuid": "abd5755a-8b77-4ed4-afbd-f2ccbc08e6f5",
                  "tls": { "enabled": true, "reality": { "enabled": true, "public_key": "abc" } }
                }
              ]
            }
        """.trimIndent()

        val list = RealVpnNodeManager.parseSingBoxFeed(sampleFeed, "NL")
        assertEquals(2, list.size)
        assertEquals(VpnProtocol.SHADOWSOCKS_AEAD, list[0].protocol)
        assertEquals(VpnProtocol.TROJAN, list[1].protocol)
        assertEquals("/ws", list[1].wsPath)

        val trUri = VpnEndpoint.parseShareUri(
            "trojan://secret123@66.23.203.21:443?sni=example.org&type=tcp#ABD%20Sunucu",
            defaultCountry = "US",
            isCustom = true
        )
        assertNotNull(trUri)
        assertEquals("ABD Sunucu", trUri!!.label)
        assertEquals("example.org", trUri.tlsSni)

        val realityUri = VpnEndpoint.parseShareUri(
            "vless://abd5755a-8b77-4ed4-afbd-f2ccbc08e6f5@1.2.3.4:443?security=reality&pbk=xyz&type=tcp#SkipMe"
        )
        assertNull(realityUri)
    }

    @Test
    fun `fatih stealth engine splits TLS ClientHello right across SNI and reassembles cleanly`() {
        val sni = "jp3.yohototo.top"
        val body = ByteArrayOutputStream().apply {
            write(ByteArray(64) { 0x11 })
            write(sni.toByteArray(StandardCharsets.US_ASCII))
            write(ByteArray(48) { 0x22 })
        }.toByteArray()
        val tlsRecord = ByteArray(5 + body.size)
        tlsRecord[0] = 0x16
        tlsRecord[1] = 0x03
        tlsRecord[2] = 0x01
        tlsRecord[3] = ((body.size ushr 8) and 0xFF).toByte()
        tlsRecord[4] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, tlsRecord, 5, body.size)

        val parts = FatihStealthEngine.splitTlsClientHelloRecord(tlsRecord, 0, tlsRecord.size, sni)
        assertEquals(3, parts.size)

        val reassembledBody = ByteArrayOutputStream()
        for (rec in parts) {
            assertEquals(0x16, rec[0].toInt() and 0xFF)
            val len = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            assertEquals(5 + len, rec.size)
            val recAscii = String(rec, 5, len, StandardCharsets.ISO_8859_1)
            assertFalse("Full SNI must never appear intact inside any single TLS record!", recAscii.contains(sni))
            reassembledBody.write(rec, 5, len)
        }
        assertArrayEquals(body, reassembledBody.toByteArray())
    }

    @Test
    fun `fatih stealth engine filters non-443 ports and injects clean cloudflare edge clones`() {
        val rawSs = VpnEndpoint("1", "NL", "RawSS", VpnProtocol.SHADOWSOCKS_AEAD, "82.38.31.66", 8080, password = "p")
        val cfWs443 = VpnEndpoint(
            id = "2",
            countryCode = "JP",
            label = "CF-WS",
            protocol = VpnProtocol.VLESS,
            host = "104.19.164.155",
            port = 443,
            uuid = "4e4f1f70-5e56-4a76-bb9d-db087f4690c4",
            tlsEnabled = true,
            tlsSni = "jp3.yohototo.top",
            transport = "ws",
            wsPath = "/tyxyws",
        )
        assertFalse(FatihStealthEngine.isStealthCompatible(rawSs))
        assertTrue(FatihStealthEngine.isStealthCompatible(cfWs443))

        val prepared = FatihStealthEngine.prepareStealthCandidates(listOf(rawSs, cfWs443))
        assertEquals(2, prepared.size) // Original 104.19.164.155 + Clean Cloudflare Anycast Edge clone!
        assertTrue(prepared.any { it.id.endsWith("_cfedge") })

        val dohSample = """{"Status":0,"Answer":[{"name":"cdn.jsdelivr.net","type":5,"data":"cname"},{"name":"cdn.jsdelivr.net","type":1,"data":"104.16.89.20"}]}"""
        assertEquals("104.16.89.20", FatihStealthEngine.extractFirstIpv4FromDohJson(dohSample))
    }

    @Test
    fun `simplified DPI presets include school_fatih_wifi and preserve legacy IDs`() {
        assertEquals(6, BuiltInPresets.ALL.size)
        assertNotNull(BuiltInPresets.findById("tr_universal"))
        assertNotNull(BuiltInPresets.findById("school_fatih_wifi"))
        assertNotNull(BuiltInPresets.findById("tr_turktelekom"))
        assertNotNull(BuiltInPresets.findById("tr_superonline_v2"))
        assertTrue(VpnCountries.ALL.any { it.code == "NL" })
        assertTrue(VpnCountries.ALL.any { it.code == "US" })
        assertTrue(VpnCountries.ALL.any { it.code == "DE" })
    }
}
