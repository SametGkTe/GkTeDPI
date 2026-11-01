package com.sametgkte.gktedpi.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class VpnCountry(
    val code: String,
    val flag: String,
    val nameTr: String,
    val nameEn: String,
    val cityTr: String,
    val cityEn: String,
    val descTr: String,
    val descEn: String,
    val recommended: Boolean = false,
) {
    fun localizedName(isTr: Boolean): String = if (isTr) nameTr else nameEn
    fun localizedCity(isTr: Boolean): String = if (isTr) cityTr else cityEn
    fun localizedDesc(isTr: Boolean): String = if (isTr) descTr else descEn
    fun displayTitle(isTr: Boolean): String =
        if (code == "AUTO") "$flag ${localizedName(isTr)}"
        else "$flag ${localizedName(isTr)} • ${localizedCity(isTr)}"
}

object VpnCountries {
    val ALL: List<VpnCountry> = listOf(
        VpnCountry(
            code = "AUTO",
            flag = "⚡",
            nameTr = "En Hızlı Ülke (Otomatik)",
            nameEn = "Fastest Country (Quick Connect)",
            cityTr = "Akıllı Seçim",
            cityEn = "Smart Pick",
            descTr = "Yakın Avrupa sunucularını yarıştırıp en düşük pingli ülkeye bağlanır.",
            descEn = "Races nearby European hubs and connects to the lowest-ping server.",
            recommended = true,
        ),
        VpnCountry(
            code = "NL",
            flag = "🇳🇱",
            nameTr = "Hollanda",
            nameEn = "Netherlands",
            cityTr = "Amsterdam",
            cityEn = "Amsterdam",
            descTr = "Proton tarzı yüksek hız • Yayın, Discord ve günlük kullanım.",
            descEn = "High-speed European hub • Streaming, Discord, and daily browsing.",
            recommended = true,
        ),
        VpnCountry(
            code = "DE",
            flag = "🇩🇪",
            nameTr = "Almanya",
            nameEn = "Germany",
            cityTr = "Frankfurt",
            cityEn = "Frankfurt",
            descTr = "Avrupa ana omurgası • Düşük ping ve kararlı bağlantı.",
            descEn = "Core European backbone • Low latency and rock-solid stability.",
            recommended = true,
        ),
        VpnCountry(
            code = "US",
            flag = "🇺🇸",
            nameTr = "Amerika Birleşik Devletleri",
            nameEn = "United States",
            cityTr = "New York / Virginia",
            cityEn = "New York / Virginia",
            descTr = "ABD içerikleri, yapay zeka servisleri ve küresel erişim.",
            descEn = "US streaming catalogs, AI services, and global access.",
            recommended = true,
        ),
        VpnCountry(
            code = "GB",
            flag = "🇬🇧",
            nameTr = "İngiltere",
            nameEn = "United Kingdom",
            cityTr = "Londra",
            cityEn = "London",
            descTr = "Birleşik Krallık çıkışı • Hızlı web ve medya akışı.",
            descEn = "UK exit IP • Fast web browsing and media streaming.",
        ),
        VpnCountry(
            code = "FR",
            flag = "🇫🇷",
            nameTr = "Fransa",
            nameEn = "France",
            cityTr = "Paris",
            cityEn = "Paris",
            descTr = "Batı Avrupa yüksek bant genişliği ve güvenli tünel.",
            descEn = "Western Europe high-bandwidth encrypted tunnel.",
        ),
        VpnCountry(
            code = "RO",
            flag = "🇷🇴",
            nameTr = "Romanya",
            nameEn = "Romania",
            cityTr = "Bükreş",
            cityEn = "Bucharest",
            descTr = "Türkiye'ye coğrafi olarak en yakın düşük gecikmeli rota.",
            descEn = "Closest low-latency Southeast European route.",
        ),
        VpnCountry(
            code = "PL",
            flag = "🇵🇱",
            nameTr = "Polonya",
            nameEn = "Poland",
            cityTr = "Varşova",
            cityEn = "Warsaw",
            descTr = "Orta Avrupa hızlı ve dengeli şifreli sunucular.",
            descEn = "Fast and balanced Central European encrypted nodes.",
        ),
        VpnCountry(
            code = "SE",
            flag = "🇸🇪",
            nameTr = "İsveç",
            nameEn = "Sweden",
            cityTr = "Stockholm",
            cityEn = "Stockholm",
            descTr = "Gizlilik odaklı İskandinav veri merkezi çıkışı.",
            descEn = "Privacy-focused Nordic datacenter exit.",
        ),
        VpnCountry(
            code = "FI",
            flag = "🇫🇮",
            nameTr = "Finlandiya",
            nameEn = "Finland",
            cityTr = "Helsinki",
            cityEn = "Helsinki",
            descTr = "Kuzey Avrupa temiz IP ve akıcı bağlantı.",
            descEn = "Clean Northern European IP and smooth throughput.",
        ),
        VpnCountry(
            code = "CA",
            flag = "🇨🇦",
            nameTr = "Kanada",
            nameEn = "Canada",
            cityTr = "Toronto / Montreal",
            cityEn = "Toronto / Montreal",
            descTr = "Kuzey Amerika alternatif yüksek hızlı çıkış.",
            descEn = "North American high-speed alternative hub.",
        ),
        VpnCountry(
            code = "JP",
            flag = "🇯🇵",
            nameTr = "Japonya",
            nameEn = "Japan",
            cityTr = "Tokyo",
            cityEn = "Tokyo",
            descTr = "Asya-Pasifik oyun, anime ve bölgeye özel servisler.",
            descEn = "Asia-Pacific hub for regional services and media.",
        ),
        VpnCountry(
            code = "SG",
            flag = "🇸🇬",
            nameTr = "Singapur",
            nameEn = "Singapore",
            cityTr = "Singapur",
            cityEn = "Singapore",
            descTr = "Güneydoğu Asya ultra hızlı fiber omurgası.",
            descEn = "Southeast Asia ultra-fast fiber backbone.",
        ),
    )

    val QUICK_RACE_CODES = listOf("NL", "DE", "RO", "PL", "FR", "GB", "US")

    fun byCode(code: String?): VpnCountry =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ALL[1] // default NL
}

enum class VpnProtocol(val badge: String) {
    SHADOWSOCKS_AEAD("SS-AEAD"),
    TROJAN("Trojan-TLS"),
    VLESS("VLESS"),
    SOCKS5("SOCKS5"),
    HTTP_CONNECT("HTTPS-Proxy"),
}

data class VpnEndpoint(
    val id: String,
    val countryCode: String,
    val label: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val cipher: String = "",
    val password: String = "",
    val uuid: String = "",
    val username: String = "",
    val tlsEnabled: Boolean = false,
    val tlsSni: String = "",
    val tlsInsecure: Boolean = true,
    val alpn: List<String> = emptyList(),
    val transport: String = "tcp", // "tcp" or "ws"
    val wsPath: String = "/",
    val wsHost: String = "",
    val isCustom: Boolean = false,
    val measuredLatencyMs: Long = -1L,
    val verifiedExitIp: String = "",
) {
    fun uniqueKey(): String = "${protocol.name}|${host.lowercase()}|$port|$transport|$wsPath"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("cc", countryCode)
        put("label", label)
        put("proto", protocol.name)
        put("host", host)
        put("port", port)
        put("cipher", cipher)
        put("pw", password)
        put("uuid", uuid)
        put("user", username)
        put("tls", tlsEnabled)
        put("sni", tlsSni)
        put("insec", tlsInsecure)
        put("alpn", JSONArray(alpn))
        put("tr", transport)
        put("wsp", wsPath)
        put("wsh", wsHost)
        put("custom", isCustom)
        put("ms", measuredLatencyMs)
        put("ip", verifiedExitIp)
    }

    companion object {
        private val SUPPORTED_SS_CIPHERS = setOf(
            "chacha20-ietf-poly1305",
            "aes-256-gcm",
            "aes-128-gcm",
        )

        fun fromJson(obj: JSONObject): VpnEndpoint? {
            val host = obj.optString("host", "").trim()
            val port = obj.optInt("port", 0)
            if (host.isEmpty() || port !in 1..65535) return null
            val proto = try {
                VpnProtocol.valueOf(obj.optString("proto", ""))
            } catch (_: Exception) {
                return null
            }
            val alpnArr = obj.optJSONArray("alpn")
            val alpnList = buildList {
                if (alpnArr != null) {
                    for (i in 0 until alpnArr.length()) {
                        val s = alpnArr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }
            return VpnEndpoint(
                id = obj.optString("id", UUID.randomUUID().toString()),
                countryCode = obj.optString("cc", "NL").uppercase(),
                label = obj.optString("label", "$host:$port"),
                protocol = proto,
                host = host,
                port = port,
                cipher = obj.optString("cipher", ""),
                password = obj.optString("pw", ""),
                uuid = obj.optString("uuid", ""),
                username = obj.optString("user", ""),
                tlsEnabled = obj.optBoolean("tls", false),
                tlsSni = obj.optString("sni", ""),
                tlsInsecure = obj.optBoolean("insec", true),
                alpn = alpnList,
                transport = obj.optString("tr", "tcp"),
                wsPath = obj.optString("wsp", "/"),
                wsHost = obj.optString("wsh", ""),
                isCustom = obj.optBoolean("custom", false),
                measuredLatencyMs = obj.optLong("ms", -1L),
                verifiedExitIp = obj.optString("ip", ""),
            )
        }

        /**
         * Parses a structured sing-box outbound JSON object into a supported VpnEndpoint,
         * automatically filtering out unsupported transports (reality, grpc, legacy stream ciphers).
         */
        fun fromSingBoxOutbound(obj: JSONObject, countryCode: String): VpnEndpoint? {
            val type = obj.optString("type", "").lowercase()
            val server = obj.optString("server", "").trim()
            val port = obj.optInt("server_port", 0)
            if (server.isEmpty() || port !in 1..65535) return null

            val tlsObj = obj.optJSONObject("tls")
            if (tlsObj?.optJSONObject("reality")?.optBoolean("enabled", false) == true) {
                return null
            }
            val tlsEnabled = tlsObj?.optBoolean("enabled", false) == true
            val tlsSni = tlsObj?.optString("server_name", "")?.trim().orEmpty()
            val alpnArr = tlsObj?.optJSONArray("alpn")
            val alpn = buildList {
                if (alpnArr != null) {
                    for (i in 0 until alpnArr.length()) {
                        val v = alpnArr.optString(i, "").trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }

            val trObj = obj.optJSONObject("transport")
            val trType = trObj?.optString("type", "tcp")?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
            if (trType != "tcp" && trType != "ws") return null
            val wsPath = trObj?.optString("path", "/")?.ifBlank { "/" } ?: "/"
            val wsHost = trObj?.optJSONObject("headers")?.optString("Host", "")?.trim().orEmpty()

            val tag = obj.optString("tag", "$countryCode-$server:$port")

            return when (type) {
                "shadowsocks" -> {
                    if (obj.optString("plugin", "").isNotBlank()) return null
                    val method = obj.optString("method", "").lowercase().trim()
                    if (method !in SUPPORTED_SS_CIPHERS) return null
                    val password = obj.optString("password", "")
                    if (password.isEmpty()) return null
                    VpnEndpoint(
                        id = "${countryCode}_ss_${server}_$port",
                        countryCode = countryCode,
                        label = tag,
                        protocol = VpnProtocol.SHADOWSOCKS_AEAD,
                        host = server,
                        port = port,
                        cipher = method,
                        password = password,
                        transport = "tcp",
                    )
                }

                "trojan" -> {
                    val password = obj.optString("password", "")
                    if (password.isEmpty()) return null
                    VpnEndpoint(
                        id = "${countryCode}_tr_${server}_$port",
                        countryCode = countryCode,
                        label = tag,
                        protocol = VpnProtocol.TROJAN,
                        host = server,
                        port = port,
                        password = password,
                        tlsEnabled = if (tlsObj != null) tlsEnabled else true,
                        tlsSni = tlsSni.ifBlank { server },
                        tlsInsecure = true,
                        alpn = alpn,
                        transport = trType,
                        wsPath = wsPath,
                        wsHost = wsHost.ifBlank { tlsSni.ifBlank { server } },
                    )
                }

                "vless" -> {
                    val flow = obj.optString("flow", "").trim()
                    if (flow.isNotEmpty()) return null // xtls-rprx-vision requires reality/xtls
                    val uid = obj.optString("uuid", "").trim()
                    if (!isValidUuid(uid)) return null
                    VpnEndpoint(
                        id = "${countryCode}_vl_${server}_$port",
                        countryCode = countryCode,
                        label = tag,
                        protocol = VpnProtocol.VLESS,
                        host = server,
                        port = port,
                        uuid = uid,
                        tlsEnabled = tlsEnabled,
                        tlsSni = tlsSni.ifBlank { server },
                        tlsInsecure = true,
                        alpn = alpn,
                        transport = trType,
                        wsPath = wsPath,
                        wsHost = wsHost.ifBlank { tlsSni.ifBlank { server } },
                    )
                }

                else -> null
            }
        }

        /**
         * Parses user-supplied or feed-supplied share links:
         * ss://..., trojan://..., vless://..., socks5://..., http://...
         */
        fun parseShareUri(rawUri: String, defaultCountry: String = "NL", isCustom: Boolean = false): VpnEndpoint? {
            val text = rawUri.trim()
            if (text.isEmpty() || !text.contains("://")) return null
            val scheme = text.substringBefore("://").lowercase()
            return try {
                when (scheme) {
                    "ss" -> parseShadowsocksUri(text, defaultCountry, isCustom)
                    "trojan" -> parseTrojanOrVlessUri(text, isTrojan = true, defaultCountry, isCustom)
                    "vless" -> parseTrojanOrVlessUri(text, isTrojan = false, defaultCountry, isCustom)
                    "socks5", "socks" -> parseProxyUri(text, VpnProtocol.SOCKS5, defaultCountry, isCustom)
                    "http", "https" -> parseProxyUri(text, VpnProtocol.HTTP_CONNECT, defaultCountry, isCustom)
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun parseShadowsocksUri(uriText: String, cc: String, isCustom: Boolean): VpnEndpoint? {
            val withoutScheme = uriText.substringAfter("://")
            val remark = if ("#" in withoutScheme) {
                urlDecode(withoutScheme.substringAfter("#")).trim()
            } else ""
            val mainPart = withoutScheme.substringBefore("#").substringBefore("?")
            val userInfo: String
            val hostPort: String
            if ("@" in mainPart) {
                val rawUser = mainPart.substringBeforeLast("@")
                hostPort = mainPart.substringAfterLast("@")
                userInfo = if (":" in rawUser) urlDecode(rawUser) else RealVpnCrypto.decodeBase64FlexibleToString(rawUser)
            } else {
                val decoded = RealVpnCrypto.decodeBase64FlexibleToString(mainPart)
                if ("@" !in decoded) return null
                userInfo = decoded.substringBeforeLast("@")
                hostPort = decoded.substringAfterLast("@")
            }
            if (":" !in userInfo) return null
            val method = userInfo.substringBefore(":").lowercase().trim()
            val password = userInfo.substringAfter(":")
            if (method !in SUPPORTED_SS_CIPHERS || password.isEmpty()) return null
            val (host, port) = splitHostPort(hostPort) ?: return null
            return VpnEndpoint(
                id = UUID.randomUUID().toString(),
                countryCode = cc,
                label = remark.ifBlank { "SS • $host:$port" },
                protocol = VpnProtocol.SHADOWSOCKS_AEAD,
                host = host,
                port = port,
                cipher = method,
                password = password,
                isCustom = isCustom,
            )
        }

        private fun parseTrojanOrVlessUri(
            uriText: String,
            isTrojan: Boolean,
            cc: String,
            isCustom: Boolean,
        ): VpnEndpoint? {
            val uri = URI(uriText.replace(" ", "%20"))
            val secret = uri.rawUserInfo?.let { urlDecode(it) }?.trim().orEmpty()
            val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
            val port = uri.port
            if (secret.isEmpty() || host.isEmpty() || port !in 1..65535) return null
            val params = parseQueryMap(uri.rawQuery)
            val security = params["security"]?.lowercase().orEmpty()
            if (security == "reality" || !params["pbk"].isNullOrBlank()) return null
            if (!params["flow"].isNullOrBlank()) return null
            val trType = params["type"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
            if (trType != "tcp" && trType != "ws") return null

            val sni = params["sni"]?.ifBlank { null } ?: params["peer"]?.ifBlank { null } ?: host
            val wsPath = params["path"]?.ifBlank { "/" } ?: "/"
            val wsHost = params["host"]?.ifBlank { sni } ?: sni
            val alpn = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val remark = uri.rawFragment?.let { urlDecode(it) }?.trim().orEmpty()

            return if (isTrojan) {
                VpnEndpoint(
                    id = UUID.randomUUID().toString(),
                    countryCode = cc,
                    label = remark.ifBlank { "Trojan • $host:$port" },
                    protocol = VpnProtocol.TROJAN,
                    host = host,
                    port = port,
                    password = secret,
                    tlsEnabled = security != "none",
                    tlsSni = sni,
                    tlsInsecure = true,
                    alpn = alpn,
                    transport = trType,
                    wsPath = wsPath,
                    wsHost = wsHost,
                    isCustom = isCustom,
                )
            } else {
                if (!isValidUuid(secret)) return null
                VpnEndpoint(
                    id = UUID.randomUUID().toString(),
                    countryCode = cc,
                    label = remark.ifBlank { "VLESS • $host:$port" },
                    protocol = VpnProtocol.VLESS,
                    host = host,
                    port = port,
                    uuid = secret,
                    tlsEnabled = security == "tls",
                    tlsSni = sni,
                    tlsInsecure = true,
                    alpn = alpn,
                    transport = trType,
                    wsPath = wsPath,
                    wsHost = wsHost,
                    isCustom = isCustom,
                )
            }
        }

        private fun parseProxyUri(
            uriText: String,
            proto: VpnProtocol,
            cc: String,
            isCustom: Boolean,
        ): VpnEndpoint? {
            val uri = URI(uriText)
            val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
            val port = uri.port
            if (host.isEmpty() || port !in 1..65535) return null
            val rawUser = uri.rawUserInfo?.let { urlDecode(it) }.orEmpty()
            val username = if (":" in rawUser) rawUser.substringBefore(":") else rawUser
            val password = if (":" in rawUser) rawUser.substringAfter(":") else ""
            val remark = uri.rawFragment?.let { urlDecode(it) }?.trim().orEmpty()
            return VpnEndpoint(
                id = UUID.randomUUID().toString(),
                countryCode = cc,
                label = remark.ifBlank { "${proto.badge} • $host:$port" },
                protocol = proto,
                host = host,
                port = port,
                username = username,
                password = password,
                tlsEnabled = uriText.startsWith("https://", ignoreCase = true),
                tlsSni = host,
                isCustom = isCustom,
            )
        }

        private fun splitHostPort(hp: String): Pair<String, Int>? {
            val clean = hp.trim()
            val idx = clean.lastIndexOf(':')
            if (idx <= 0 || idx >= clean.length - 1) return null
            val h = clean.substring(0, idx).removePrefix("[").removeSuffix("]")
            val p = clean.substring(idx + 1).toIntOrNull() ?: return null
            if (h.isBlank() || p !in 1..65535) return null
            return h to p
        }

        private fun parseQueryMap(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            return buildMap {
                for (part in rawQuery.split("&")) {
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val k = urlDecode(part.substring(0, eq))
                        val v = urlDecode(part.substring(eq + 1))
                        put(k, v)
                    }
                }
            }
        }

        private fun urlDecode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Throwable) {
            s
        }

        private fun isValidUuid(s: String): Boolean = try {
            UUID.fromString(s)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
