package com.sametgkte.gktedpi.smart

import android.content.Context
import com.sametgkte.gktedpi.core.ByeDpiProxyCmdPreferences
import com.sametgkte.gktedpi.core.ByeDpiProxyPreferences
import com.sametgkte.gktedpi.utility.getPreferences
import java.io.File

/**
 * Native C-level (`ciadpi -H <hosts_file>`) SNI / HTTP Host Akıllı Filtre Motoru.
 *
 * 1) BYPASS_SAFE_DOMAINS (Zero-Ping Oyun & Banka Muafiyeti):
 *    Oyun sunucuları, Türk bankaları ve e-Devlet paketleri `-H <dosya> -A n <strateji>`
 *    ile C katmanında 0 paket parçalama ve 0 ms ek gecikmeyle doğrudan geçirilir;
 *    diğer tüm sitelerde DPI aşma tam güç çalışır.
 *
 * 2) ONLY_BLOCKED_DOMAINS (Sadece Engelli Domainler):
 *    Yalnızca seçili engelli domainlerde (`discord.com`, `roblox.com` vb.) ve
 *    kullanıcının eklediği özel domainlerde DPI parçalama uygulanır.
 */
object SmartSniEngine {

    const val PREF_MODE = "smart_sni_mode"
    const val PREF_PACK_GAMING = "smart_sni_pack_gaming"
    const val PREF_PACK_BANKING_GOV = "smart_sni_pack_banking"
    const val PREF_PACK_DISCORD = "smart_sni_pack_discord"
    const val PREF_PACK_SOCIAL_MEDIA = "smart_sni_pack_social"
    const val PREF_CUSTOM_DOMAINS = "smart_sni_custom_domains"

    enum class Mode(val id: String) {
        DISABLED("disabled"),
        BYPASS_SAFE_DOMAINS("bypass_safe"),
        ONLY_BLOCKED_DOMAINS("only_blocked");

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: DISABLED
        }
    }

    val GAMING_DOMAINS = listOf(
        "riotgames.com", "playvalorant.com", "leagueoflegends.com", "riotcdn.net", "pvp.net",
        "steampowered.com", "steamcommunity.com", "steamstatic.com", "steamcontent.com", "valve.net",
        "epicgames.com", "unrealengine.com", "fortnite.com", "easyanticheat.net",
        "ea.com", "origin.com", "battle.net", "blizzard.com",
        "pubg.com", "krafton.com", "supercell.com", "brawlstarsgame.com", "clashroyale.com",
        "xboxlive.com", "playstation.net", "playstation.com", "sonyentertainmentnetwork.com",
        "faceit.com", "battleye.com"
    )

    val TR_BANKING_AND_GOV_DOMAINS = listOf(
        "turkiye.gov.tr", "edevlet.gov.tr", "enabiz.gov.tr", "mhrs.gov.tr", "gib.gov.tr", "uyap.gov.tr",
        "ziraatbank.com.tr", "isbank.com.tr", "garantibbva.com.tr", "akbank.com", "yapikredi.com.tr",
        "vakifbank.com.tr", "halkbank.com.tr", "qnbfinansbank.com", "enpara.com", "denizbank.com",
        "teb.com.tr", "kuveytturk.com.tr", "turkiyefinans.com.tr", "albaraka.com.tr",
        "papara.com", "iyzico.com", "paytr.com", "bkmexpress.com.tr", "fast.tcmb.gov.tr"
    )

    val DISCORD_AND_VOICE_DOMAINS = listOf(
        "discord.com", "discordapp.com", "discordapp.net", "discord.gg", "discord.media",
        "discordcdn.com", "discord.co", "discordstatus.com", "airhorn.solutions"
    )

    val BLOCKED_WEB_AND_SOCIAL_DOMAINS = listOf(
        "roblox.com", "rbxcdn.com", "roblox.qq.com",
        "wattpad.com", "wattpad.net",
        "pastebin.com", "imgur.com",
        "instagram.com", "cdninstagram.com",
        "x.com", "twitter.com", "twimg.com", "t.co",
        "bsky.app", "reddit.com", "redd.it"
    )

    private val GLOBAL_OPTIONS_WITH_ARG = setOf(
        "-i", "--ip", "-p", "--port", "-c", "--max-conn", "-b", "--buf-size",
        "-g", "--def-ttl", "-x", "--debug", "-w", "--sf-delay", "-u", "--cache-ttl",
        "-T", "--timeout", "-I", "--conn-ip"
    )

    private val GLOBAL_FLAGS_NO_ARG = setOf(
        "-N", "--no-domain", "-U", "--no-udp", "-F", "--tfo", "-W", "--no-wait"
    )

    fun getMode(context: Context): Mode =
        Mode.fromId(context.getPreferences().getString(PREF_MODE, Mode.DISABLED.id))

    fun setMode(context: Context, mode: Mode) {
        context.getPreferences().edit().putString(PREF_MODE, mode.id).apply()
    }

    fun isPackEnabled(context: Context, key: String, defaultVal: Boolean = true): Boolean =
        context.getPreferences().getBoolean(key, defaultVal)

    fun setPackEnabled(context: Context, key: String, enabled: Boolean) {
        context.getPreferences().edit().putBoolean(key, enabled).apply()
    }

    fun getCustomDomainsRaw(context: Context): String =
        context.getPreferences().getString(PREF_CUSTOM_DOMAINS, "") ?: ""

    fun setCustomDomainsRaw(context: Context, raw: String) {
        context.getPreferences().edit().putString(PREF_CUSTOM_DOMAINS, raw.trim()).apply()
    }

    fun buildActiveDomainList(context: Context, mode: Mode = getMode(context)): List<String> {
        if (mode == Mode.DISABLED) return emptyList()
        val set = linkedSetOf<String>()
        when (mode) {
            Mode.BYPASS_SAFE_DOMAINS -> {
                if (isPackEnabled(context, PREF_PACK_GAMING, true)) set.addAll(GAMING_DOMAINS)
                if (isPackEnabled(context, PREF_PACK_BANKING_GOV, true)) set.addAll(TR_BANKING_AND_GOV_DOMAINS)
            }
            Mode.ONLY_BLOCKED_DOMAINS -> {
                if (isPackEnabled(context, PREF_PACK_DISCORD, true)) set.addAll(DISCORD_AND_VOICE_DOMAINS)
                if (isPackEnabled(context, PREF_PACK_SOCIAL_MEDIA, true)) set.addAll(BLOCKED_WEB_AND_SOCIAL_DOMAINS)
            }
            Mode.DISABLED -> Unit
        }
        val custom = getCustomDomainsRaw(context)
            .split(Regex("[\\s,;]+"))
            .map {
                it.trim()
                    .lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore("/")
                    .removePrefix("*.")
                    .removePrefix(".")
            }
            .filter { it.contains('.') && it.length >= 3 }
        set.addAll(custom)
        return set.toList()
    }

    /**
     * Aktif `ByeDpiProxyPreferences` nesnesini alıp Smart SNI açık ise
     * C motorunun `-H <hosts_file>` argümanlarıyla zenginleştirilmiş halini döndürür.
     */
    fun decoratePreferences(
        context: Context,
        base: ByeDpiProxyPreferences,
    ): ByeDpiProxyPreferences {
        val mode = getMode(context)
        if (mode == Mode.DISABLED) return base

        val rawArgs = base.toCommandArgs().toList()
        // Kullanıcı zaten manuel -H / --hosts yazdıysa çakıştırma yapma
        if (rawArgs.any { it == "-H" || it == "--hosts" || it.startsWith("--hosts=") }) {
            return base
        }

        val domains = buildActiveDomainList(context, mode)
        if (domains.isEmpty()) return base

        val hostsFile = File(context.filesDir, "gkte_smart_sni_hosts.txt")
        hostsFile.writeText(domains.joinToString("\n") + "\n")

        val transformed = rewriteArgsWithHosts(rawArgs, hostsFile.absolutePath, mode)
        return ByeDpiProxyCmdPreferences(transformed.toTypedArray())
    }

    internal fun rewriteArgsWithHosts(
        rawArgs: List<String>,
        hostsFilePath: String,
        mode: Mode,
    ): List<String> {
        if (mode == Mode.DISABLED || rawArgs.isEmpty()) return rawArgs

        val globals = mutableListOf<String>()
        val desyncTokens = mutableListOf<String>()

        var i = 0
        if (rawArgs.firstOrNull() == "ciadpi") {
            globals.add("ciadpi")
            i = 1
        } else {
            globals.add("ciadpi")
        }

        while (i < rawArgs.size) {
            val tok = rawArgs[i]
            when {
                tok in GLOBAL_OPTIONS_WITH_ARG && i + 1 < rawArgs.size -> {
                    globals.add(tok)
                    globals.add(rawArgs[i + 1])
                    i += 2
                }
                tok.startsWith("--ip=") || tok.startsWith("--port=") || tok.startsWith("--timeout=") -> {
                    globals.add(tok)
                    i += 1
                }
                tok in GLOBAL_FLAGS_NO_ARG -> {
                    globals.add(tok)
                    i += 1
                }
                else -> {
                    desyncTokens.add(tok)
                    i += 1
                }
            }
        }

        if (desyncTokens.isEmpty()) return rawArgs

        val out = mutableListOf<String>()
        out.addAll(globals)

        when (mode) {
            Mode.BYPASS_SAFE_DOMAINS -> {
                // Grup 0: Oyun & Banka domainlerinde HİÇBİR desync uygulama!
                out.add("-H")
                out.add(hostsFilePath)
                // Grup 1+: Geri kalan tüm internet için mevcut stratejiyi uygula
                if (desyncTokens.firstOrNull() == "-A" || desyncTokens.firstOrNull()?.startsWith("--auto") == true) {
                    out.addAll(desyncTokens)
                } else {
                    out.add("-A")
                    out.add("n")
                    out.addAll(desyncTokens)
                }
            }

            Mode.ONLY_BLOCKED_DOMAINS -> {
                // Her desync grubuna -H <hostsFilePath> ekle ve en sona boş -A n ekle
                if (desyncTokens.firstOrNull() != "-A" && desyncTokens.firstOrNull()?.startsWith("--auto") != true) {
                    out.add("-H")
                    out.add(hostsFilePath)
                }
                var j = 0
                while (j < desyncTokens.size) {
                    val t = desyncTokens[j]
                    out.add(t)
                    if ((t == "-A" || t == "--auto") && j + 1 < desyncTokens.size) {
                        out.add(desyncTokens[j + 1])
                        out.add("-H")
                        out.add(hostsFilePath)
                        j += 2
                    } else if (t.startsWith("--auto=")) {
                        out.add("-H")
                        out.add(hostsFilePath)
                        j += 1
                    } else {
                        j += 1
                    }
                }
                out.add("-A")
                out.add("n")
            }

            Mode.DISABLED -> out.addAll(desyncTokens)
        }
        return out
    }
}
