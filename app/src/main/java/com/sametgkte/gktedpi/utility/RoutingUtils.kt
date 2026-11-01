package com.sametgkte.gktedpi.utility

object RoutingUtils {
    data class CidrRoute(val address: String, val prefixLength: Int)

    /**
     * Public IPv4 routes covering 0.0.0.0/0 while excluding RFC1918 private LAN subnets
     * (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), loopback (127.0.0.0/8),
     * link-local (169.254.0.0/16), and multicast (224.0.0.0/3).
     * Works on all Android API levels (API 21+).
     */
    val LAN_BYPASS_IPV4_ROUTES: List<CidrRoute> = listOf(
        CidrRoute("1.0.0.0", 8),
        CidrRoute("2.0.0.0", 7),
        CidrRoute("4.0.0.0", 6),
        CidrRoute("8.0.0.0", 7),
        CidrRoute("11.0.0.0", 8),
        CidrRoute("12.0.0.0", 6),
        CidrRoute("16.0.0.0", 4),
        CidrRoute("32.0.0.0", 3),
        CidrRoute("64.0.0.0", 2),
        CidrRoute("128.0.0.0", 3),
        CidrRoute("160.0.0.0", 5),
        CidrRoute("168.0.0.0", 6),
        CidrRoute("172.0.0.0", 12),
        CidrRoute("172.32.0.0", 11),
        CidrRoute("172.64.0.0", 10),
        CidrRoute("172.128.0.0", 9),
        CidrRoute("173.0.0.0", 8),
        CidrRoute("174.0.0.0", 7),
        CidrRoute("176.0.0.0", 4),
        CidrRoute("192.0.0.0", 9),
        CidrRoute("192.128.0.0", 11),
        CidrRoute("192.160.0.0", 13),
        CidrRoute("192.169.0.0", 16),
        CidrRoute("192.170.0.0", 15),
        CidrRoute("192.172.0.0", 14),
        CidrRoute("192.176.0.0", 12),
        CidrRoute("192.192.0.0", 10),
        CidrRoute("193.0.0.0", 8),
        CidrRoute("194.0.0.0", 7),
        CidrRoute("196.0.0.0", 6),
        CidrRoute("200.0.0.0", 5),
        CidrRoute("208.0.0.0", 4),
    )

    val DNS_PRESETS: Map<String, Pair<String, String>> = mapOf(
        "cloudflare" to ("1.1.1.1" to "1.0.0.1"),
        "cloudflare_security" to ("1.1.1.2" to "1.0.0.2"),
        "adguard" to ("94.140.14.14" to "94.140.15.15"),
        "quad9" to ("9.9.9.9" to "149.112.112.112"),
        "google" to ("8.8.8.8" to "8.8.4.4"),
        "mullvad" to ("194.242.2.2" to "194.242.2.3"),
        "controld" to ("76.76.2.0" to "76.76.10.0"),
    )
}
