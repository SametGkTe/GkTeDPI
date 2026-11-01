package com.sametgkte.gktedpi.vpn

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-throughput, API-21-safe cryptographic primitives & SIP004 Shadowsocks AEAD streams.
 * Zero external dependencies; works identically on Android 5.0–15 and pure JVM unit tests.
 */
object RealVpnCrypto {

    private const val B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_LOOKUP = IntArray(128) { -1 }.apply {
        for (i in B64_STD.indices) this[B64_STD[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    private val secureRandom = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    fun encodeBase64(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(B64_STD[b0 ushr 2])
            sb.append(B64_STD[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < data.size) B64_STD[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < data.size) B64_STD[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decodeBase64Flexible(input: String): ByteArray {
        val out = ByteArray((input.length * 3) / 4 + 4)
        var outLen = 0
        var buf = 0
        var bits = 0
        for (c in input) {
            if (c == '=') break
            val code = c.code
            if (code >= 128) continue
            val v = B64_LOOKUP[code]
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((buf ushr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outLen)
    }

    fun decodeBase64FlexibleToString(input: String): String =
        String(decodeBase64Flexible(input), StandardCharsets.UTF_8)

    /** OpenSSL EVP_BytesToKey(MD5) used by Shadowsocks AEAD master key derivation. */
    fun evpBytesToKey(password: ByteArray, keyLen: Int): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        val out = ByteArray(keyLen)
        var offset = 0
        var prev = ByteArray(0)
        while (offset < keyLen) {
            md5.reset()
            if (prev.isNotEmpty()) md5.update(prev)
            md5.update(password)
            prev = md5.digest()
            val copy = minOf(prev.size, keyLen - offset)
            System.arraycopy(prev, 0, out, offset, copy)
            offset += copy
        }
        return out
    }

    /** RFC 5869 HKDF-SHA1 used by Shadowsocks AEAD ("ss-subkey"). */
    fun hkdfSha1(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val prkMac = Mac.getInstance("HmacSHA1")
        prkMac.init(SecretKeySpec(salt, "HmacSHA1"))
        val prk = prkMac.doFinal(ikm)

        val expandMac = Mac.getInstance("HmacSHA1")
        expandMac.init(SecretKeySpec(prk, "HmacSHA1"))

        val okm = ByteArray(outLen)
        var offset = 0
        var prev = ByteArray(0)
        var counter = 1
        while (offset < outLen) {
            expandMac.reset()
            if (prev.isNotEmpty()) expandMac.update(prev)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            prev = expandMac.doFinal()
            val copy = minOf(prev.size, outLen - offset)
            System.arraycopy(prev, 0, okm, offset, copy)
            offset += copy
            counter++
        }
        return okm
    }

    /** Trojan password hash: lowercase 56-character hex SHA-224. */
    fun sha224Hex(input: ByteArray): String {
        val digest = try {
            MessageDigest.getInstance("SHA-224").digest(input)
        } catch (_: Throwable) {
            sha224Pure(input)
        }
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    private fun sha224Pure(msg: ByteArray): ByteArray {
        val k = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0x0bf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
        )
        var h0 = -0x3efa6128; var h1 = 0x367cd507; var h2 = 0x3070dd17; var h3 = -0x08f0a93f
        var h4 = -0x03ffff4; var h5 = 0x68581511; var h6 = 0x64f98fa7; var h7 = -0x4105b05c
        val bitLen = msg.size.toLong() * 8L
        val padLen = ((56 - ((msg.size + 1) % 64)) + 64) % 64
        val padded = ByteArray(msg.size + 1 + padLen + 8)
        System.arraycopy(msg, 0, padded, 0, msg.size)
        padded[msg.size] = 0x80.toByte()
        for (i in 0..7) padded[padded.size - 8 + i] = ((bitLen ushr ((7 - i) * 8)) and 0xFF).toByte()
        val w = IntArray(64)
        var off = 0
        while (off < padded.size) {
            for (i in 0..15) {
                val p = off + i * 4
                w[i] = ((padded[p].toInt() and 0xFF) shl 24) or
                    ((padded[p + 1].toInt() and 0xFF) shl 16) or
                    ((padded[p + 2].toInt() and 0xFF) shl 8) or
                    (padded[p + 3].toInt() and 0xFF)
            }
            for (i in 16..63) {
                val s0 = Integer.rotateRight(w[i - 15], 7) xor Integer.rotateRight(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = Integer.rotateRight(w[i - 2], 17) xor Integer.rotateRight(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4; var f = h5; var g = h6; var h = h7
            for (i in 0..63) {
                val s1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[i] + w[i]
                val s0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            off += 64
        }
        val out = ByteArray(28)
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6)
        for (i in 0..6) {
            out[i * 4] = (hs[i] ushr 24).toByte()
            out[i * 4 + 1] = (hs[i] ushr 16).toByte()
            out[i * 4 + 2] = (hs[i] ushr 8).toByte()
            out[i * 4 + 3] = hs[i].toByte()
        }
        return out
    }

    // =========================================================================
    // RFC 8439 ChaCha20-IETF-Poly1305 (Zero-Allocation Limb Math, API 21+)
    // =========================================================================

    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putU32le(v: Int, out: ByteArray, o: Int) {
        out[o] = v.toByte()
        out[o + 1] = (v ushr 8).toByte()
        out[o + 2] = (v ushr 16).toByte()
        out[o + 3] = (v ushr 24).toByte()
    }

    private fun chachaQuarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun chacha20Block(key32: ByteArray, counter: Int, nonce12: ByteArray, out64: ByteArray, work: IntArray) {
        work[0] = 0x61707865
        work[1] = 0x3320646e
        work[2] = 0x79622d32
        work[3] = 0x6b206574
        for (i in 0..7) work[4 + i] = u32le(key32, i * 4)
        work[12] = counter
        work[13] = u32le(nonce12, 0)
        work[14] = u32le(nonce12, 4)
        work[15] = u32le(nonce12, 8)

        val init0 = work[0]; val init1 = work[1]; val init2 = work[2]; val init3 = work[3]
        val init4 = work[4]; val init5 = work[5]; val init6 = work[6]; val init7 = work[7]
        val init8 = work[8]; val init9 = work[9]; val init10 = work[10]; val init11 = work[11]
        val init12 = work[12]; val init13 = work[13]; val init14 = work[14]; val init15 = work[15]

        repeat(10) {
            chachaQuarterRound(work, 0, 4, 8, 12)
            chachaQuarterRound(work, 1, 5, 9, 13)
            chachaQuarterRound(work, 2, 6, 10, 14)
            chachaQuarterRound(work, 3, 7, 11, 15)
            chachaQuarterRound(work, 0, 5, 10, 15)
            chachaQuarterRound(work, 1, 6, 11, 12)
            chachaQuarterRound(work, 2, 7, 8, 13)
            chachaQuarterRound(work, 3, 4, 9, 14)
        }

        putU32le(work[0] + init0, out64, 0)
        putU32le(work[1] + init1, out64, 4)
        putU32le(work[2] + init2, out64, 8)
        putU32le(work[3] + init3, out64, 12)
        putU32le(work[4] + init4, out64, 16)
        putU32le(work[5] + init5, out64, 20)
        putU32le(work[6] + init6, out64, 24)
        putU32le(work[7] + init7, out64, 28)
        putU32le(work[8] + init8, out64, 32)
        putU32le(work[9] + init9, out64, 36)
        putU32le(work[10] + init10, out64, 40)
        putU32le(work[11] + init11, out64, 44)
        putU32le(work[12] + init12, out64, 48)
        putU32le(work[13] + init13, out64, 52)
        putU32le(work[14] + init14, out64, 56)
        putU32le(work[15] + init15, out64, 60)
    }

    private fun chacha20Xor(
        key32: ByteArray,
        initialCounter: Int,
        nonce12: ByteArray,
        src: ByteArray,
        srcOff: Int,
        len: Int,
        dst: ByteArray,
        dstOff: Int,
    ) {
        val work = IntArray(16)
        val block = ByteArray(64)
        var pos = 0
        var ctr = initialCounter
        while (pos < len) {
            chacha20Block(key32, ctr++, nonce12, block, work)
            val take = minOf(64, len - pos)
            for (i in 0 until take) {
                dst[dstOff + pos + i] = (src[srcOff + pos + i].toInt() xor block[i].toInt()).toByte()
            }
            pos += take
        }
    }

    private fun u32leLong(b: ByteArray, o: Int): Long = (u32le(b, o).toLong() and 0xFFFFFFFFL)

    /** Donna 26-bit limb Poly1305 authenticator (RFC 8439 §2.5). */
    private fun poly1305AeadTag(
        otk32: ByteArray,
        ciphertext: ByteArray,
        ctOff: Int,
        ctLen: Int,
        tagOut: ByteArray,
        tagOff: Int,
    ) {
        val r0 = u32leLong(otk32, 0) and 0x3ffffffL
        val r1 = (u32leLong(otk32, 3) ushr 2) and 0x3ffff03L
        val r2 = (u32leLong(otk32, 6) ushr 4) and 0x3ffc0ffL
        val r3 = (u32leLong(otk32, 9) ushr 6) and 0x3f03fffL
        val r4 = (u32leLong(otk32, 12) ushr 8) and 0x00fffffL

        val s1 = r1 * 5L; val s2 = r2 * 5L; val s3 = r3 * 5L; val s4 = r4 * 5L
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val tail = ByteArray(16)

        fun feedBlocks(buf: ByteArray, start: Int, length: Int, padTo16: Boolean) {
            var idx = start
            var rem = length
            while (rem >= 16) {
                val t0 = u32leLong(buf, idx)
                val t1 = u32leLong(buf, idx + 4)
                val t2 = u32leLong(buf, idx + 8)
                val t3 = u32leLong(buf, idx + 12)
                h0 += t0 and 0x3ffffffL
                h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3ffffffL
                h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3ffffffL
                h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3ffffffL
                h4 += (t3 ushr 8) or (1L shl 24)

                var d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
                var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
                var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
                var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
                var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

                var c = d0 ushr 26; h0 = d0 and 0x3ffffffL
                d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffffL
                d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffffL
                d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffffL
                d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffffL
                h0 += c * 5L; c = h0 ushr 26; h0 = h0 and 0x3ffffffL
                h1 += c

                idx += 16
                rem -= 16
            }
            if (rem > 0) {
                tail.fill(0)
                System.arraycopy(buf, idx, tail, 0, rem)
                val hib = if (padTo16) {
                    1L shl 24
                } else {
                    tail[rem] = 1
                    0L
                }
                val t0 = u32leLong(tail, 0)
                val t1 = u32leLong(tail, 4)
                val t2 = u32leLong(tail, 8)
                val t3 = u32leLong(tail, 12)
                h0 += t0 and 0x3ffffffL
                h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3ffffffL
                h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3ffffffL
                h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3ffffffL
                h4 += (t3 ushr 8) or hib

                var d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
                var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
                var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
                var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
                var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

                var c = d0 ushr 26; h0 = d0 and 0x3ffffffL
                d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffffL
                d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffffL
                d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffffL
                d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffffL
                h0 += c * 5L; c = h0 ushr 26; h0 = h0 and 0x3ffffffL
                h1 += c
            }
        }

        // Empty AAD (0 bytes), then ciphertext padded to 16-byte boundary, then [aadLen=0 (8B LE), ctLen (8B LE)]
        feedBlocks(ciphertext, ctOff, ctLen, padTo16 = true)
        val lenBlock = ByteArray(16)
        val ctLenLong = ctLen.toLong()
        for (i in 0..7) {
            lenBlock[8 + i] = ((ctLenLong ushr (i * 8)) and 0xFFL).toByte()
        }
        feedBlocks(lenBlock, 0, 16, padTo16 = true)

        var c = h1 ushr 26; h1 = h1 and 0x3ffffffL
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffffL
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffffL
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffffL
        h0 += c * 5L; c = h0 ushr 26; h0 = h0 and 0x3ffffffL
        h1 += c

        var g0 = h0 + 5L; c = g0 ushr 26; g0 = g0 and 0x3ffffffL
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffffL
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffffL
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffffL
        val g4 = h4 + c - (1L shl 26)

        val mask = (g4 ushr 63) - 1L
        val nmask = mask.inv()
        h0 = (h0 and nmask) or (g0 and mask)
        h1 = (h1 and nmask) or (g1 and mask)
        h2 = (h2 and nmask) or (g2 and mask)
        h3 = (h3 and nmask) or (g3 and mask)
        h4 = (h4 and nmask) or (g4 and mask)

        var f0 = (h0 or (h1 shl 26)) and 0xFFFFFFFFL
        var f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL
        var f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL
        var f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL

        var f = f0 + u32leLong(otk32, 16); f0 = f and 0xFFFFFFFFL; c = f ushr 32
        f = f1 + u32leLong(otk32, 20) + c; f1 = f and 0xFFFFFFFFL; c = f ushr 32
        f = f2 + u32leLong(otk32, 24) + c; f2 = f and 0xFFFFFFFFL; c = f ushr 32
        f = f3 + u32leLong(otk32, 28) + c; f3 = f and 0xFFFFFFFFL

        putU32le(f0.toInt(), tagOut, tagOff)
        putU32le(f1.toInt(), tagOut, tagOff + 4)
        putU32le(f2.toInt(), tagOut, tagOff + 8)
        putU32le(f3.toInt(), tagOut, tagOff + 12)
    }

    fun chacha20Poly1305Encrypt(
        key32: ByteArray,
        nonce12: ByteArray,
        plain: ByteArray,
        plainOff: Int = 0,
        plainLen: Int = plain.size,
    ): ByteArray {
        val out = ByteArray(plainLen + 16)
        val block0 = ByteArray(64)
        chacha20Block(key32, 0, nonce12, block0, IntArray(16))
        chacha20Xor(key32, 1, nonce12, plain, plainOff, plainLen, out, 0)
        poly1305AeadTag(block0, out, 0, plainLen, out, plainLen)
        return out
    }

    fun chacha20Poly1305Decrypt(
        key32: ByteArray,
        nonce12: ByteArray,
        cipherAndTag: ByteArray,
        off: Int = 0,
        len: Int = cipherAndTag.size,
    ): ByteArray {
        if (len < 16) throw GeneralSecurityException("Ciphertext shorter than tag")
        val ctLen = len - 16
        val block0 = ByteArray(64)
        chacha20Block(key32, 0, nonce12, block0, IntArray(16))
        val expectedTag = ByteArray(16)
        poly1305AeadTag(block0, cipherAndTag, off, ctLen, expectedTag, 0)
        var diff = 0
        for (i in 0 until 16) {
            diff = diff or (expectedTag[i].toInt() xor cipherAndTag[off + ctLen + i].toInt())
        }
        if (diff != 0) throw GeneralSecurityException("ChaCha20-Poly1305 tag mismatch")
        val plain = ByteArray(ctLen)
        chacha20Xor(key32, 1, nonce12, cipherAndTag, off, ctLen, plain, 0)
        return plain
    }

    interface SsAeadBox {
        val keySize: Int
        val saltSize: Int
        fun encrypt(nonce12: ByteArray, plain: ByteArray, off: Int, len: Int): ByteArray
        fun decrypt(nonce12: ByteArray, cipherAndTag: ByteArray, off: Int, len: Int): ByteArray
    }

    fun createSsAeadBox(cipherName: String, subKey: ByteArray): SsAeadBox {
        return when (cipherName.lowercase()) {
            "chacha20-ietf-poly1305" -> object : SsAeadBox {
                override val keySize: Int = 32
                override val saltSize: Int = 32
                override fun encrypt(nonce12: ByteArray, plain: ByteArray, off: Int, len: Int): ByteArray =
                    chacha20Poly1305Encrypt(subKey, nonce12, plain, off, len)

                override fun decrypt(nonce12: ByteArray, cipherAndTag: ByteArray, off: Int, len: Int): ByteArray =
                    chacha20Poly1305Decrypt(subKey, nonce12, cipherAndTag, off, len)
            }

            "aes-256-gcm", "aes-128-gcm" -> {
                val kSize = if (cipherName.startsWith("aes-128", ignoreCase = true)) 16 else 32
                val keySpec = SecretKeySpec(subKey, 0, kSize, "AES")
                object : SsAeadBox {
                    override val keySize: Int = kSize
                    override val saltSize: Int = kSize
                    override fun encrypt(nonce12: ByteArray, plain: ByteArray, off: Int, len: Int): ByteArray {
                        val c = Cipher.getInstance("AES/GCM/NoPadding")
                        c.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce12))
                        return c.doFinal(plain, off, len)
                    }

                    override fun decrypt(nonce12: ByteArray, cipherAndTag: ByteArray, off: Int, len: Int): ByteArray {
                        val c = Cipher.getInstance("AES/GCM/NoPadding")
                        c.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce12))
                        return c.doFinal(cipherAndTag, off, len)
                    }
                }
            }

            else -> throw IllegalArgumentException("Unsupported AEAD cipher: $cipherName")
        }
    }

    fun keySizeForCipher(cipherName: String): Int = when (cipherName.lowercase()) {
        "aes-128-gcm" -> 16
        else -> 32
    }

    internal fun incrementLittleEndianNonce(nonce12: ByteArray) {
        for (i in 0 until 12) {
            val next = (nonce12[i].toInt() and 0xFF) + 1
            nonce12[i] = next.toByte()
            if ((next and 0x100) == 0) break
        }
    }
}

/**
 * SIP004 Shadowsocks AEAD OutputStream.
 * Coalesces the initial salt + first encrypted frame into a single write for 0-RTT connection setup.
 */
class ShadowsocksAeadOutputStream(
    private val rawOut: OutputStream,
    private val cipherName: String,
    password: String,
) : OutputStream() {

    companion object {
        private const val MAX_CHUNK = 0x3FFF
        private val SUBKEY_INFO = "ss-subkey".toByteArray(StandardCharsets.US_ASCII)
    }

    private val keyLen = RealVpnCrypto.keySizeForCipher(cipherName)
    private val masterKey = RealVpnCrypto.evpBytesToKey(password.toByteArray(StandardCharsets.UTF_8), keyLen)
    private val salt = RealVpnCrypto.randomBytes(keyLen)
    private val subKey = RealVpnCrypto.hkdfSha1(masterKey, salt, SUBKEY_INFO, keyLen)
    private val box = RealVpnCrypto.createSsAeadBox(cipherName, subKey)
    private val nonce = ByteArray(12)
    private var saltSent = false
    private val singleByte = ByteArray(1)

    override fun write(b: Int) {
        singleByte[0] = b.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        var pos = off
        var rem = len
        while (rem > 0) {
            val chunk = minOf(MAX_CHUNK, rem)
            val lenPlain = byteArrayOf(((chunk ushr 8) and 0xFF).toByte(), (chunk and 0xFF).toByte())
            val encLen = box.encrypt(nonce, lenPlain, 0, 2)
            RealVpnCrypto.incrementLittleEndianNonce(nonce)

            val encPayload = box.encrypt(nonce, b, pos, chunk)
            RealVpnCrypto.incrementLittleEndianNonce(nonce)

            if (!saltSent) {
                val combined = ByteArray(salt.size + encLen.size + encPayload.size)
                System.arraycopy(salt, 0, combined, 0, salt.size)
                System.arraycopy(encLen, 0, combined, salt.size, encLen.size)
                System.arraycopy(encPayload, 0, combined, salt.size + encLen.size, encPayload.size)
                rawOut.write(combined)
                saltSent = true
            } else {
                val combined = ByteArray(encLen.size + encPayload.size)
                System.arraycopy(encLen, 0, combined, 0, encLen.size)
                System.arraycopy(encPayload, 0, combined, encLen.size, encPayload.size)
                rawOut.write(combined)
            }
            pos += chunk
            rem -= chunk
        }
    }

    override fun flush() {
        rawOut.flush()
    }

    override fun close() {
        rawOut.close()
    }
}

/**
 * SIP004 Shadowsocks AEAD InputStream.
 */
class ShadowsocksAeadInputStream(
    private val rawIn: InputStream,
    private val cipherName: String,
    password: String,
) : InputStream() {

    companion object {
        private val SUBKEY_INFO = "ss-subkey".toByteArray(StandardCharsets.US_ASCII)
    }

    private val keyLen = RealVpnCrypto.keySizeForCipher(cipherName)
    private val masterKey = RealVpnCrypto.evpBytesToKey(password.toByteArray(StandardCharsets.UTF_8), keyLen)
    private var box: RealVpnCrypto.SsAeadBox? = null
    private val nonce = ByteArray(12)
    private var plainBuf: ByteArray = ByteArray(0)
    private var plainPos: Int = 0
    private var eof = false
    private val singleByte = ByteArray(1)

    override fun read(): Int {
        val n = read(singleByte, 0, 1)
        return if (n <= 0) -1 else (singleByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (plainPos >= plainBuf.size) {
            if (!fillNextChunk()) return -1
        }
        val copy = minOf(len, plainBuf.size - plainPos)
        System.arraycopy(plainBuf, plainPos, b, off, copy)
        plainPos += copy
        return copy
    }

    private fun fillNextChunk(): Boolean {
        if (eof) return false
        try {
            var activeBox = box
            if (activeBox == null) {
                val salt = ByteArray(keyLen)
                val first = rawIn.read()
                if (first < 0) {
                    eof = true
                    return false
                }
                salt[0] = first.toByte()
                readExact(salt, 1, keyLen - 1)
                val subKey = RealVpnCrypto.hkdfSha1(masterKey, salt, SUBKEY_INFO, keyLen)
                activeBox = RealVpnCrypto.createSsAeadBox(cipherName, subKey)
                box = activeBox
            }

            val encLen = ByteArray(2 + 16)
            val firstLenByte = rawIn.read()
            if (firstLenByte < 0) {
                eof = true
                return false
            }
            encLen[0] = firstLenByte.toByte()
            readExact(encLen, 1, encLen.size - 1)

            val decLenBytes = activeBox.decrypt(nonce, encLen, 0, encLen.size)
            RealVpnCrypto.incrementLittleEndianNonce(nonce)
            val payloadLen = (((decLenBytes[0].toInt() and 0xFF) shl 8) or (decLenBytes[1].toInt() and 0xFF)) and 0x3FFF
            if (payloadLen <= 0) {
                eof = true
                return false
            }

            val encPayload = ByteArray(payloadLen + 16)
            readExact(encPayload, 0, encPayload.size)
            plainBuf = activeBox.decrypt(nonce, encPayload, 0, encPayload.size)
            RealVpnCrypto.incrementLittleEndianNonce(nonce)
            plainPos = 0
            return true
        } catch (e: EOFException) {
            eof = true
            return false
        } catch (e: GeneralSecurityException) {
            throw IOException("Shadowsocks AEAD decryption error", e)
        }
    }

    private fun readExact(buf: ByteArray, off: Int, len: Int) {
        var readSoFar = 0
        while (readSoFar < len) {
            val n = rawIn.read(buf, off + readSoFar, len - readSoFar)
            if (n < 0) throw EOFException("Unexpected EOF in AEAD frame")
            readSoFar += n
        }
    }

    override fun close() {
        rawIn.close()
    }
}
