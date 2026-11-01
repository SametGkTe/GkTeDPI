package com.sametgkte.gktedpi.vpn

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class VerifiedNodePool(
    val resolvedCountryCode: String,
    val primary: VpnEndpoint,
    val standbys: List<VpnEndpoint>,
    val exitIp: String,
    val latencyMs: Long,
    val fatihStealthActive: Boolean = false,
) {
    fun allOrdered(): List<VpnEndpoint> = buildList {
        add(primary)
        addAll(standbys)
    }
}

object RealVpnNodeManager {
    private const val TAG = "RealVpnNodeManager"
    private const val STORE_NAME = "gkte_real_vpn_store"
    private const val KEY_CUSTOM_LIST = "custom_endpoints_json"
    private const val KEY_PING_CACHE = "country_ping_cache_json"
    private const val FEED_CACHE_TTL_MS = 45 * 60 * 1000L // 45 min fresh, stale-while-revalidate

    private val countryPingCache = ConcurrentHashMap<String, Long>()
    private val feedMutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Educational/JS CDN mirrors FIRST so school/campus firewalls (FATİH) allow feed bootstrap! */
    private fun feedUrlsForCountry(cc: String): List<Pair<String, Boolean>> = listOf(
        "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/by-country/singbox-$cc.json" to true,
        "https://fastly.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/by-country/singbox-$cc.json" to true,
        "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/singbox-$cc.json" to true,
        "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/by-country/v2ray-base64-$cc.txt" to false,
    )

    private val GLOBAL_STEALTH_FEED_URLS: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/stable/singbox-0001.json",
        "https://fastly.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/stable/singbox-0001.json",
        "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/stable/singbox-0001.json",
    )

    /** Curated fallback seeds so first-second cold start works even if feed CDN is momentarily slow. */
    private val BOOTSTRAP_SEEDS: Map<String, List<VpnEndpoint>> by lazy {
        mapOf(
            "NL" to listOf(
                VpnEndpoint("seed_nl_1", "NL", "NL • Amsterdam #1", VpnProtocol.SHADOWSOCKS_AEAD, "82.38.31.66", 8080, cipher = "chacha20-ietf-poly1305", password = "oZIoA69Q8yhcQV8ka3Pa3A"),
                VpnEndpoint("seed_nl_2", "NL", "NL • Amsterdam #2", VpnProtocol.SHADOWSOCKS_AEAD, "82.38.31.34", 8080, cipher = "chacha20-ietf-poly1305", password = "oZIoA69Q8yhcQV8ka3Pa3A"),
                VpnEndpoint("seed_nl_3", "NL", "NL • Amsterdam #3", VpnProtocol.SHADOWSOCKS_AEAD, "82.38.31.17", 8080, cipher = "chacha20-ietf-poly1305", password = "oZIoA69Q8yhcQV8ka3Pa3A"),
            ),
            "US" to listOf(
                VpnEndpoint("seed_us_1", "US", "US • New York #1", VpnProtocol.TROJAN, "66.163.114.45", 443, password = "0ab6c98dae3b48e8b9c4a776b6c9c19a", tlsEnabled = true, tlsSni = "cdn.findconnectwithemployersandbuildprofessionalnetwork.com", tlsInsecure = true),
                VpnEndpoint("seed_us_2", "US", "US • New York #2", VpnProtocol.TROJAN, "66.23.203.21", 59689, password = "ae7eb4dwyaz25pxwgq59", tlsEnabled = true, tlsSni = "66.23.203.21", tlsInsecure = true),
            ),
            "DE" to listOf(
                VpnEndpoint("seed_de_1", "DE", "DE • Frankfurt #1", VpnProtocol.TROJAN, "193.108.119.193", 443, password = "humanity", tlsEnabled = true, tlsSni = "resources.iloverussia2026.com", tlsInsecure = true),
                VpnEndpoint("seed_de_2", "DE", "DE • Frankfurt #2", VpnProtocol.TROJAN, "193.108.119.96", 443, password = "humanity", tlsEnabled = true, tlsSni = "learn.iloverussia2026.com", tlsInsecure = true),
            ),
            "PL" to listOf(
                VpnEndpoint("seed_pl_1", "PL", "PL • Warsaw #1", VpnProtocol.SHADOWSOCKS_AEAD, "54.36.174.140", 443, cipher = "chacha20-ietf-poly1305", password = "oZIoA69Q8yhcQV8ka3Pa3A"),
                VpnEndpoint("seed_pl_2", "PL", "PL • Warsaw #2", VpnProtocol.TROJAN, "70.34.252.255", 443, password = "humanity", tlsEnabled = true, tlsSni = "browseexecutivejobopeningsandenhanceleadershipcareer.com", tlsInsecure = true),
            ),
            "JP" to listOf(
                VpnEndpoint("seed_jp_1", "JP", "JP • Tokyo #1", VpnProtocol.VLESS, "104.16.10.135", 443, uuid = "4e4f1f70-5e56-4a76-bb9d-db087f4690c4", tlsEnabled = true, tlsSni = "jp3.yohototo.top", alpn = listOf("http/1.1"), transport = "ws", wsPath = "/tyxyws", wsHost = "jp3.yohototo.top"),
                VpnEndpoint("seed_jp_2", "JP", "JP • Tokyo #2", VpnProtocol.TROJAN, "45.32.52.173", 8886, password = "humanity", tlsEnabled = true, tlsSni = "45.32.52.173", tlsInsecure = true),
            ),
        )
    }

    fun getCachedCountryPing(context: Context, countryCode: String): Long {
        val cc = countryCode.uppercase()
        countryPingCache[cc]?.let { return it }
        loadPersistedPings(context)
        return countryPingCache[cc] ?: -1L
    }

    private fun recordCountryPing(context: Context, countryCode: String, ms: Long) {
        if (ms <= 0L) return
        val cc = countryCode.uppercase()
        countryPingCache[cc] = ms
        try {
            val prefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject()
            for ((k, v) in countryPingCache) obj.put(k, v)
            prefs.edit().putString(KEY_PING_CACHE, obj.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadPersistedPings(context: Context) {
        if (countryPingCache.isNotEmpty()) return
        try {
            val raw = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PING_CACHE, null) ?: return
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optLong(k, -1L)
                if (v > 0L) countryPingCache[k] = v
            }
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // Custom VPN Endpoints (ss://, trojan://, vless://, socks5://, http://)
    // =========================================================================

    fun getCustomEndpoints(context: Context): List<VpnEndpoint> {
        val raw = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { VpnEndpoint.fromJson(it)?.let(::add) }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addCustomEndpointFromUri(context: Context, uriText: String, customLabel: String? = null): VpnEndpoint? {
        val parsed = VpnEndpoint.parseShareUri(
            rawUri = uriText,
            defaultCountry = VpnEngineState.getSelectedCountryCode(context).let { if (it == "AUTO") "NL" else it },
            isCustom = true,
        ) ?: return null
        val finalEp = if (!customLabel.isNullOrBlank()) parsed.copy(label = customLabel.trim()) else parsed
        val current = getCustomEndpoints(context).toMutableList()
        current.removeAll { it.uniqueKey() == finalEp.uniqueKey() }
        current.add(0, finalEp)
        saveCustomEndpoints(context, current)
        return finalEp
    }

    fun deleteCustomEndpoint(context: Context, id: String) {
        val current = getCustomEndpoints(context).filterNot { it.id == id }
        saveCustomEndpoints(context, current)
        if (VpnEngineState.getSelectedCustomEndpointId(context) == id) {
            VpnEngineState.setSelectedCustomEndpointId(context, null)
        }
    }

    private fun saveCustomEndpoints(context: Context, list: List<VpnEndpoint>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_LIST, arr.toString())
            .apply()
    }

    // =========================================================================
    // Country Pool Fetch, FATİH Stealth Bypass & Happy-Eyeballs Optimizer
    // =========================================================================

    suspend fun selectOptimalPool(
        context: Context,
        countryOrAuto: String,
        protectSocket: ((Socket) -> Boolean)?,
    ): VerifiedNodePool = withContext(Dispatchers.IO) {
        val userRequestedStealth = VpnEngineState.isFatihStealthEnabled(context)

        // 1. Check if user explicitly selected a custom imported VPN server
        val customId = VpnEngineState.getSelectedCustomEndpointId(context)
        if (!customId.isNullOrBlank()) {
            val customEp = getCustomEndpoints(context).firstOrNull { it.id == customId }
            if (customEp != null) {
                val probe = verifyEndpointLive(
                    context = context,
                    endpoint = customEp,
                    protectSocket = protectSocket,
                    timeoutMs = 4500,
                    stealthMode = userRequestedStealth,
                )
                return@withContext VerifiedNodePool(
                    resolvedCountryCode = probe?.detectedLoc?.ifBlank { customEp.countryCode } ?: customEp.countryCode,
                    primary = probe?.endpoint ?: customEp,
                    standbys = emptyList(),
                    exitIp = probe?.exitIp.orEmpty(),
                    latencyMs = probe?.latencyMs ?: -1L,
                    fatihStealthActive = userRequestedStealth,
                )
            }
        }

        // 2. If FATİH / School Stealth Mode is explicitly ON, go straight to Port-443 Stealth Engine!
        if (userRequestedStealth) {
            return@withContext selectStealthPool(context, countryOrAuto, protectSocket)
        }

        // 3. Standard Quick Connect ("AUTO")
        if (countryOrAuto.equals("AUTO", ignoreCase = true)) {
            val candidates = mutableListOf<VpnEndpoint>()
            coroutineScope {
                VpnCountries.QUICK_RACE_CODES.take(5).map { cc ->
                    async { loadCountryCandidates(context, cc, forceRefresh = false, stealthFetch = false).take(4) }
                }.forEach { deferred ->
                    candidates.addAll(deferred.await())
                }
            }
            val raced = raceEndpoints(
                context = context,
                candidates = diversifyCandidates(candidates, maxCount = 14),
                protectSocket = protectSocket,
                stealthMode = false,
            )
            if (raced.isNotEmpty()) {
                val winner = raced.first()
                recordCountryPing(context, winner.endpoint.countryCode, winner.latencyMs)
                recordCountryPing(context, "AUTO", winner.latencyMs)
                return@withContext VerifiedNodePool(
                    resolvedCountryCode = winner.endpoint.countryCode,
                    primary = winner.endpoint,
                    standbys = raced.drop(1).map { it.endpoint },
                    exitIp = winner.exitIp,
                    latencyMs = winner.latencyMs,
                    fatihStealthActive = false,
                )
            }
        } else {
            // 4. Standard Specific Country
            val cc = countryOrAuto.uppercase()
            var candidates = loadCountryCandidates(context, cc, forceRefresh = false, stealthFetch = false)
            var verified = raceEndpoints(context, diversifyCandidates(candidates, maxCount = 12), protectSocket, stealthMode = false)

            if (verified.isEmpty()) {
                candidates = loadCountryCandidates(context, cc, forceRefresh = true, stealthFetch = false)
                verified = raceEndpoints(context, diversifyCandidates(candidates, maxCount = 16), protectSocket, stealthMode = false)
            }

            if (verified.isNotEmpty()) {
                val best = verified.first()
                recordCountryPing(context, cc, best.latencyMs)
                persistVerifiedOrder(context, cc, verified.map { it.endpoint }, candidates)
                return@withContext VerifiedNodePool(
                    resolvedCountryCode = cc,
                    primary = best.endpoint,
                    standbys = verified.drop(1).map { it.endpoint },
                    exitIp = best.exitIp,
                    latencyMs = best.latencyMs,
                    fatihStealthActive = false,
                )
            }
        }

        // 5. AUTOMATIC SCHOOL/FIREWALL RESCUE:
        // If all standard ports/protocols failed (e.g., student connected to FATİH Wi-Fi without
        // turning on the switch first), automatically engage Port-443 TLS Stealth Engine!
        Log.i(TAG, "Standard VPN ports blocked — auto-engaging FATİH Port-443 Stealth rescue!")
        selectStealthPool(context, countryOrAuto, protectSocket)
    }

    /**
     * Dedicated MEB FATİH / Campus NGFW Stealth selector:
     *  - Uses Direct-IP DoH + fragmented HTTPS over educational CDNs to fetch fresh nodes
     *  - Filters strictly for Port 443 TLS (`WS+TLS` / `TCP+TLS`) & Port 80 `WS`
     *  - Injects Clean Cloudflare Anycast Edge IPs (`104.16.x.x` / `172.64.x.x`)
     *  - Supplements with global Port-443 stable pool & built-in emergency Stealth seeds
     */
    private suspend fun selectStealthPool(
        context: Context,
        countryOrAuto: String,
        protectSocket: ((Socket) -> Boolean)?,
    ): VerifiedNodePool = withContext(Dispatchers.IO) {
        val targetCc = countryOrAuto.uppercase()
        val rawStealthPool = ArrayList<VpnEndpoint>(40)

        if (targetCc == "AUTO") {
            coroutineScope {
                listOf("DE", "NL", "US", "FR", "PL", "JP").map { cc ->
                    async { loadCountryCandidates(context, cc, forceRefresh = false, stealthFetch = true) }
                }.forEach { rawStealthPool.addAll(it.await()) }
            }
        } else {
            rawStealthPool.addAll(loadCountryCandidates(context, targetCc, forceRefresh = false, stealthFetch = true))
        }

        var stealthCandidates = FatihStealthEngine.prepareStealthCandidates(rawStealthPool, maxCount = 16)

        // If selected country has few Port-443 TLS nodes, pull fresh + global Port-443 stable pool
        if (stealthCandidates.size < 4) {
            if (targetCc != "AUTO") {
                val refreshed = loadCountryCandidates(context, targetCc, forceRefresh = true, stealthFetch = true)
                rawStealthPool.addAll(refreshed)
            }
            rawStealthPool.addAll(loadGlobalStableStealthPool(context, protectSocket))
            rawStealthPool.addAll(
                if (targetCc == "AUTO") FatihStealthEngine.FATIH_STEALTH_SEEDS
                else FatihStealthEngine.FATIH_STEALTH_SEEDS.sortedBy { if (it.countryCode == targetCc) 0 else 1 }
            )
            stealthCandidates = FatihStealthEngine.prepareStealthCandidates(rawStealthPool, maxCount = 18)
        }

        var verified = raceEndpoints(
            context = context,
            candidates = stealthCandidates,
            protectSocket = protectSocket,
            desiredSurvivors = 3,
            maxTotalWaitMs = 5200L,
            stealthMode = true,
        )

        if (verified.isEmpty()) {
            // Final emergency sweep across global stable Port-443 WS+TLS & built-in stealth seeds
            val emergency = FatihStealthEngine.prepareStealthCandidates(
                loadGlobalStableStealthPool(context, protectSocket) + FatihStealthEngine.FATIH_STEALTH_SEEDS,
                maxCount = 18,
            )
            verified = raceEndpoints(
                context = context,
                candidates = emergency,
                protectSocket = protectSocket,
                desiredSurvivors = 2,
                maxTotalWaitMs = 5500L,
                stealthMode = true,
            )
        }

        if (verified.isEmpty()) {
            throw IllegalStateException("FATİH / Güvenlik duvarı arkasında açık Port-443 tüneli bulunamadı. Lütfen tekrar deneyin veya özel WS+TLS (Port 443) sunucu ekleyin.")
        }

        val winner = verified.first()
        val resolvedCc = winner.detectedLoc.ifBlank { winner.endpoint.countryCode }.uppercase()
        recordCountryPing(context, resolvedCc, winner.latencyMs)
        if (targetCc == "AUTO") recordCountryPing(context, "AUTO", winner.latencyMs)

        VerifiedNodePool(
            resolvedCountryCode = resolvedCc,
            primary = winner.endpoint.copy(countryCode = resolvedCc),
            standbys = verified.drop(1).map { it.endpoint },
            exitIp = winner.exitIp,
            latencyMs = winner.latencyMs,
            fatihStealthActive = true,
        )
    }

    /** Background refresh & ping measurement for country list UI. */
    suspend fun measureCountryLatencies(
        context: Context,
        countryCodes: List<String>,
        protectSocket: ((Socket) -> Boolean)? = null,
        onEachUpdated: ((String, Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val stealth = VpnEngineState.isFatihStealthEnabled(context)
        coroutineScope {
            countryCodes.filter { it != "AUTO" }.map { cc ->
                async {
                    try {
                        val raw = loadCountryCandidates(context, cc, forceRefresh = false, stealthFetch = stealth)
                        val cands = if (stealth) {
                            FatihStealthEngine.prepareStealthCandidates(raw, maxCount = 6)
                        } else {
                            diversifyCandidates(raw, maxCount = 6)
                        }
                        val res = raceEndpoints(
                            context = context,
                            candidates = cands,
                            protectSocket = protectSocket,
                            desiredSurvivors = 1,
                            maxTotalWaitMs = if (stealth) 4000L else 3200L,
                            stealthMode = stealth,
                        )
                        val bestMs = res.firstOrNull()?.latencyMs ?: -1L
                        if (bestMs > 0L) {
                            recordCountryPing(context, cc, bestMs)
                            onEachUpdated?.invoke(cc, bestMs)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }.forEach { it.await() }
        }
    }

    suspend fun loadCountryCandidates(
        context: Context,
        countryCode: String,
        forceRefresh: Boolean,
        stealthFetch: Boolean = false,
    ): List<VpnEndpoint> = withContext(Dispatchers.IO) {
        val cc = countryCode.uppercase()
        val cacheFile = File(context.filesDir, "vpn_nodes_$cc.json")
        val now = System.currentTimeMillis()

        if (!forceRefresh && cacheFile.exists()) {
            val age = now - cacheFile.lastModified()
            val cached = readCacheFile(cacheFile)
            if (cached.isNotEmpty()) {
                if (age > FEED_CACHE_TTL_MS) {
                    bgScope.launch {
                        try {
                            fetchAndSaveRemotePool(context, cc, cacheFile, stealthFetch)
                        } catch (_: Throwable) {
                        }
                    }
                }
                return@withContext cached
            }
        }

        val fetched = feedMutex.withLock {
            fetchAndSaveRemotePool(context, cc, cacheFile, stealthFetch)
        }
        if (fetched.isNotEmpty()) return@withContext fetched

        val stale = readCacheFile(cacheFile)
        if (stale.isNotEmpty()) return@withContext stale

        BOOTSTRAP_SEEDS[cc].orEmpty()
    }

    private fun loadGlobalStableStealthPool(
        context: Context,
        protectSocket: ((Socket) -> Boolean)?,
    ): List<VpnEndpoint> {
        val cacheFile = File(context.filesDir, "vpn_nodes_GLOBAL_STEALTH.json")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < FEED_CACHE_TTL_MS) {
            val cached = readCacheFile(cacheFile)
            if (cached.isNotEmpty()) return cached
        }
        for (urlStr in GLOBAL_STEALTH_FEED_URLS) {
            try {
                val body = FatihStealthEngine.fetchHttpsTextStealth(context, urlStr, protectSocket, timeoutMs = 5500)
                val parsed = parseSingBoxFeed(body, "NL").filter { FatihStealthEngine.isStealthCompatible(it) }
                if (parsed.isNotEmpty()) {
                    writeCacheFile(cacheFile, parsed.take(80))
                    return parsed.take(80)
                }
            } catch (_: Throwable) {
            }
        }
        return readCacheFile(cacheFile)
    }

    private fun fetchAndSaveRemotePool(
        context: Context,
        cc: String,
        cacheFile: File,
        stealthFetch: Boolean,
    ): List<VpnEndpoint> {
        for ((urlStr, isSingBoxJson) in feedUrlsForCountry(cc)) {
            // 1. If stealthFetch is enabled, use DoH + 3-record TLS ClientHello fragmentation directly!
            if (stealthFetch) {
                try {
                    val body = FatihStealthEngine.fetchHttpsTextStealth(context, urlStr, null, timeoutMs = 5000)
                    val parsed = if (isSingBoxJson) parseSingBoxFeed(body, cc) else parseBase64UriFeed(body, cc)
                    if (parsed.isNotEmpty()) {
                        writeCacheFile(cacheFile, parsed)
                        return parsed
                    }
                } catch (_: Throwable) {
                }
                continue
            }

            // 2. Standard fetch, with automatic fallback to Stealth fragmented HTTPS if blocked by firewall!
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = PhysicalNetworkHelper.openPhysicalHttpConnection(context, urlStr).apply {
                    requestMethod = "GET"
                    connectTimeout = 4500
                    readTimeout = 5500
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val parsed = if (isSingBoxJson) parseSingBoxFeed(body, cc) else parseBase64UriFeed(body, cc)
                    if (parsed.isNotEmpty()) {
                        writeCacheFile(cacheFile, parsed)
                        return parsed
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Standard feed fetch warning ($cc), trying stealth HTTPS: ${e.message}")
                try {
                    val body = FatihStealthEngine.fetchHttpsTextStealth(context, urlStr, null, timeoutMs = 5000)
                    val parsed = if (isSingBoxJson) parseSingBoxFeed(body, cc) else parseBase64UriFeed(body, cc)
                    if (parsed.isNotEmpty()) {
                        writeCacheFile(cacheFile, parsed)
                        return parsed
                    }
                } catch (_: Throwable) {
                }
            } finally {
                conn?.disconnect()
            }
        }
        return emptyList()
    }

    fun parseSingBoxFeed(jsonText: String, countryCode: String): List<VpnEndpoint> {
        return try {
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("outbounds") ?: return emptyList()
            val seen = HashSet<String>()
            val result = ArrayList<VpnEndpoint>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ep = VpnEndpoint.fromSingBoxOutbound(obj, countryCode) ?: continue
                if (seen.add(ep.uniqueKey())) {
                    result.add(ep)
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseBase64UriFeed(rawText: String, countryCode: String): List<VpnEndpoint> {
        return try {
            val decoded = if ("://" in rawText) rawText else RealVpnCrypto.decodeBase64FlexibleToString(rawText.trim())
            val seen = HashSet<String>()
            val result = ArrayList<VpnEndpoint>()
            for (line in decoded.lineSequence()) {
                val ep = VpnEndpoint.parseShareUri(line, countryCode, isCustom = false) ?: continue
                if (seen.add(ep.uniqueKey())) {
                    result.add(ep)
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun diversifyCandidates(all: List<VpnEndpoint>, maxCount: Int): List<VpnEndpoint> {
        if (all.size <= maxCount) return all
        val verifiedFirst = all.filter { it.measuredLatencyMs in 1..2500 }.sortedBy { it.measuredLatencyMs }
        val rest = all.filterNot { it.measuredLatencyMs in 1..2500 }

        val chosen = ArrayList<VpnEndpoint>(maxCount)
        val usedKeys = HashSet<String>()
        val prefixCounts = HashMap<String, Int>()

        for (ep in verifiedFirst + rest) {
            if (chosen.size >= maxCount) break
            val prefix = ep.host.substringBeforeLast('.', ep.host)
            val countForPrefix = prefixCounts[prefix] ?: 0
            if (countForPrefix >= 2 && all.size > maxCount * 2) continue
            if (usedKeys.add(ep.uniqueKey())) {
                chosen.add(ep)
                prefixCounts[prefix] = countForPrefix + 1
            }
        }

        if (chosen.size < maxCount) {
            for (ep in all) {
                if (chosen.size >= maxCount) break
                if (usedKeys.add(ep.uniqueKey())) chosen.add(ep)
            }
        }
        return chosen
    }

    private data class ProbeWin(
        val endpoint: VpnEndpoint,
        val latencyMs: Long,
        val exitIp: String,
        val detectedLoc: String,
    )

    private suspend fun raceEndpoints(
        context: Context,
        candidates: List<VpnEndpoint>,
        protectSocket: ((Socket) -> Boolean)?,
        desiredSurvivors: Int = 3,
        maxTotalWaitMs: Long = 3800L,
        stealthMode: Boolean = false,
    ): List<ProbeWin> = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope emptyList()
        val wins = ConcurrentHashMap.newKeySet<ProbeWin>()
        val finishedCount = AtomicInteger(0)
        val perNodeTimeout = if (stealthMode) 3600 else 2800

        val jobs = candidates.map { ep ->
            launch(Dispatchers.IO) {
                try {
                    val res = verifyEndpointLive(context, ep, protectSocket, timeoutMs = perNodeTimeout, stealthMode = stealthMode)
                    if (res != null) {
                        wins.add(res)
                    }
                } catch (_: Throwable) {
                } finally {
                    finishedCount.incrementAndGet()
                }
            }
        }

        val t0 = SystemClock.elapsedRealtime()
        var firstWinAt = 0L
        while (SystemClock.elapsedRealtime() - t0 < maxTotalWaitMs) {
            val winCount = wins.size
            if (winCount >= desiredSurvivors) break
            if (winCount >= 1) {
                if (firstWinAt == 0L) firstWinAt = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - firstWinAt >= 350L) break
            }
            if (finishedCount.get() >= candidates.size) break
            delay(45L)
        }

        jobs.forEach { it.cancel() }
        wins.sortedBy { it.latencyMs }
    }

    /**
     * End-to-end encrypted tunnel probe:
     * Uses `ip-api.com:80` (`/line/?fields=status,countryCode,query`) so BOTH Cloudflare-fronted
     * `WS+TLS:443` nodes AND direct VPS nodes succeed in 1 RTT (Cloudflare Workers block connecting
     * back to `1.1.1.1:80`), with instant fallback to `cloudflare.com:80` (`/cdn-cgi/trace`).
     */
    private fun verifyEndpointLive(
        context: Context,
        endpoint: VpnEndpoint,
        protectSocket: ((Socket) -> Boolean)?,
        timeoutMs: Int,
        stealthMode: Boolean = false,
    ): ProbeWin? {
        probeViaIpApi(context, endpoint, protectSocket, timeoutMs, stealthMode)?.let { return it }
        return probeViaCloudflareTrace(context, endpoint, protectSocket, timeoutMs, stealthMode)
    }

    private fun probeViaIpApi(
        context: Context,
        endpoint: VpnEndpoint,
        protectSocket: ((Socket) -> Boolean)?,
        timeoutMs: Int,
        stealthMode: Boolean,
    ): ProbeWin? {
        val req = (
            "GET /line/?fields=status,countryCode,query HTTP/1.1\r\n" +
                "Host: ip-api.com\r\n" +
                "User-Agent: GkTeDPI/6.0\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)

        val t0 = SystemClock.elapsedRealtime()
        var session: TunnelSession? = null
        return try {
            session = RealVpnDialer.openTunnel(
                context = context,
                endpoint = endpoint,
                targetHost = "ip-api.com",
                targetPort = 80,
                protectSocket = protectSocket,
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
                initialPayload = req,
                stealthMode = stealthMode,
            )
            val buf = ByteArray(1024)
            val sb = StringBuilder(256)
            while (sb.length < 1536) {
                val n = session.input.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, StandardCharsets.ISO_8859_1))
                                val cur = sb.toString()
                if (cur.contains("\r\n\r\n") && cur.substringAfter("\r\n\r\n").count { ch -> ch == '\n' } >= 2) break
            }
            val raw = sb.toString()
            if (!raw.contains("\r\n\r\n")) return null
            val bodyLines = raw.substringAfter("\r\n\r\n")
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (bodyLines.size < 3 || bodyLines[0] != "success") return null

            val loc = bodyLines[1].uppercase()
            val ip = bodyLines[2]
            val rtt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            val enriched = endpoint.copy(measuredLatencyMs = rtt, verifiedExitIp = ip)
            ProbeWin(enriched, rtt, ip, loc)
        } catch (_: Throwable) {
            null
        } finally {
            session?.close()
        }
    }

    private fun probeViaCloudflareTrace(
        context: Context,
        endpoint: VpnEndpoint,
        protectSocket: ((Socket) -> Boolean)?,
        timeoutMs: Int,
        stealthMode: Boolean,
    ): ProbeWin? {
        val traceReq = (
            "GET /cdn-cgi/trace HTTP/1.1\r\n" +
                "Host: cloudflare.com\r\n" +
                "User-Agent: GkTeDPI/6.0\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)

        val t0 = SystemClock.elapsedRealtime()
        var session: TunnelSession? = null
        return try {
            session = RealVpnDialer.openTunnel(
                context = context,
                endpoint = endpoint,
                targetHost = "cloudflare.com",
                targetPort = 80,
                protectSocket = protectSocket,
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
                initialPayload = traceReq,
                stealthMode = stealthMode,
            )
            val buf = ByteArray(2048)
            val sb = StringBuilder(512)
            while (sb.length < 2048) {
                val n = session.input.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, StandardCharsets.ISO_8859_1))
                if (sb.contains("loc=")) break
            }
            val text = sb.toString()
            if (!text.contains("ip=") && !text.contains("200 OK")) return null

            var ip = ""
            var loc = ""
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("ip=")) ip = trimmed.removePrefix("ip=").trim()
                else if (trimmed.startsWith("loc=")) loc = trimmed.removePrefix("loc=").trim()
            }
            val rtt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            val enriched = endpoint.copy(measuredLatencyMs = rtt, verifiedExitIp = ip)
            ProbeWin(enriched, rtt, ip, loc)
        } catch (_: Throwable) {
            null
        } finally {
            session?.close()
        }
    }

    private fun persistVerifiedOrder(
        context: Context,
        cc: String,
        winners: List<VpnEndpoint>,
        existing: List<VpnEndpoint>,
    ) {
        try {
            val winnerMap = winners.associateBy { it.uniqueKey() }
            val merged = buildList {
                addAll(winners)
                for (ep in existing) {
                    if (ep.uniqueKey() !in winnerMap) add(ep)
                }
            }
            writeCacheFile(File(context.filesDir, "vpn_nodes_$cc.json"), merged.take(80))
        } catch (_: Throwable) {
        }
    }

    private fun readCacheFile(file: File): List<VpnEndpoint> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(StandardCharsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { VpnEndpoint.fromJson(it)?.let(::add) }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeCacheFile(file: File, list: List<VpnEndpoint>) {
        try {
            val arr = JSONArray()
            list.take(80).forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString(), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}
