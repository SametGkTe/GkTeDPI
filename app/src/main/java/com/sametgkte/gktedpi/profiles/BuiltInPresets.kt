package com.sametgkte.gktedpi.profiles

data class StrategyPreset(
    val id: String,
    val nameTr: String,
    val nameEn: String,
    val descriptionTr: String,
    val descriptionEn: String,
    val tag: String,
    val tagEn: String = tag,
    val commandArgs: String,
    val recommendedDns: String = "1.1.1.1",
    val recommendedMtu: Int = 8500,
) {
    fun localizedName(isTurkish: Boolean): String = if (isTurkish) nameTr else nameEn
    fun localizedDescription(isTurkish: Boolean): String = if (isTurkish) descriptionTr else descriptionEn
    fun localizedTag(isTurkish: Boolean): String = if (isTurkish) tag else tagEn
}

/**
 * Basitleştirilmiş, kullanıcı dostu 5 temel DPI modu.
 * Teknik karmaşıklığı gizler; eski preset kimlikleriyle %100 geriye dönük uyumludur.
 */
object BuiltInPresets {
    val ALL: List<StrategyPreset> = listOf(
        StrategyPreset(
            id = "tr_universal",
            nameTr = "⚡ Otomatik (Önerilen)",
            nameEn = "⚡ Auto (Recommended)",
            descriptionTr = "Çoğu ev ve mobil internette engelli siteleri hız kaybı olmadan açar.",
            descriptionEn = "Unblocks websites and apps on most networks with zero speed loss.",
            tag = "HIZ KAYIPSIZ • ÖNERİLEN",
            tagEn = "ZERO SPEED LOSS • RECOMMENDED",
            commandArgs = "-d 1 -r 1+s -M h,d -a 2 -A t,r,s -T 2.5 -f -1 -S -t 8",
            recommendedDns = "1.1.1.1",
        ),
        StrategyPreset(
            id = "discord_voice_ultra",
            nameTr = "💬 Discord & Sesli Sohbet",
            nameEn = "💬 Discord & Voice Chat",
            descriptionTr = "Ses odaları, ekran paylaşımı ve mesajlaşma için kopmasız net bağlantı.",
            descriptionEn = "Stable, crystal-clear voice channels, streaming, and messaging.",
            tag = "SES & SOHBET",
            tagEn = "VOICE & CHAT",
            commandArgs = "-d 1+s -r 1+s -a 5 -F",
            recommendedDns = "1.1.1.1",
        ),
        StrategyPreset(
            id = "gaming_low_ping",
            nameTr = "🎮 Oyun Modu (Düşük Ping)",
            nameEn = "🎮 Gaming Mode (Low Ping)",
            descriptionTr = "Roblox, Steam ve çevrimiçi oyunlarda ekstra gecikme yaratmadan çalışır.",
            descriptionEn = "Zero added latency for Roblox, Steam, and competitive online games.",
            tag = "SIFIR EK PİNG",
            tagEn = "LOWEST PING",
            commandArgs = "-s 1+s -r 2+s -F -a 2",
            recommendedDns = "1.1.1.1",
        ),
        StrategyPreset(
            id = "tr_superonline_v1",
            nameTr = "🛡️ Güçlü Erişim (Sıkı Ağlar)",
            nameEn = "🛡️ Strong Access (Strict Wi-Fi)",
            descriptionTr = "Otomatik modun açamadığı katı ev interneti ve kısıtlı ağlar için.",
            descriptionEn = "For strict home fiber or restricted Wi-Fi where Auto mode isn't enough.",
            tag = "GÜÇLÜ MOD",
            tagEn = "STRONG BYPASS",
            commandArgs = "-s 1+s -d 3+s -r 1+s -M h,d,r -a 3 -A t,r,s -T 2.5 -f -1 -S -t 6",
            recommendedDns = "94.140.14.14",
            recommendedMtu = 1450,
        ),
        StrategyPreset(
            id = "tr_mobile_45g",
            nameTr = "📱 Mobil Veri",
            nameEn = "📱 Mobile Data",
            descriptionTr = "4.5G ve 5G mobil hatlarda pil ve veri dostu hızlı erişim.",
            descriptionEn = "Battery-friendly, fast access optimized for 4.5G and 5G carriers.",
            tag = "4.5G / 5G MOBİL",
            tagEn = "4.5G / 5G MOBILE",
            commandArgs = "-o 1+s -r 1+s -M h,d -a 2 -A t,s -T 3 -d 1",
            recommendedDns = "1.1.1.1",
            recommendedMtu = 1400,
        ),
        StrategyPreset(
            id = "school_fatih_wifi",
            nameTr = "🏫 Okul & Kampüs (FATİH / KYK)",
            nameEn = "🏫 School & Campus (FATİH / Dorm)",
            descriptionTr = "Çoklu TLS SNI parçalama ile okul/yurt ağlarındaki SNI filtrelerini aşar (Tam güvenlik duvarı için Gerçek VPN + FATİH Modunu açın).",
            descriptionEn = "Multi-record TLS SNI splitting for campus/dorm Wi-Fi (for strict port-blocking firewalls, use Real VPN + FATİH Stealth).",
            tag = "OKUL / KAMPÜS WI-FI",
            tagEn = "SCHOOL / CAMPUS WI-FI",
            commandArgs = "-s 1 -d 2 -r 1+s -f -1 -t 4 -M h,d,r -a 4 -A t,r,s -T 2.0 -S",
            recommendedDns = "8.8.8.8",
            recommendedMtu = 1400,
        ),
    )

    private val LEGACY_ID_ALIASES = mapOf(
        "tr_turktelekom" to "tr_universal",
        "tr_turknet_kablonet" to "tr_universal",
        "smart_only_blocked" to "tr_universal",
        "tr_superonline_v2" to "tr_superonline_v1",
        "disoob_combo" to "tr_superonline_v1",
    )

    fun findById(id: String?): StrategyPreset? {
        if (id.isNullOrBlank()) return null
        val normalized = id.trim().lowercase()
        ALL.firstOrNull { it.id.lowercase() == normalized }?.let { return it }
        val alias = LEGACY_ID_ALIASES[normalized] ?: return null
        return ALL.firstOrNull { it.id == alias }
    }
}
