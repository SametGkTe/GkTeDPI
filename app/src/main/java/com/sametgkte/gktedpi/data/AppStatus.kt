package com.sametgkte.gktedpi.data

enum class AppStatus {
    Halted,
    Running,
}

enum class Mode {
    Proxy,
    VPN;

    companion object {
        fun fromSender(sender: Sender): Mode = when (sender) {
            Sender.Proxy -> Proxy
            Sender.VPN -> VPN
        }

        fun fromString(name: String): Mode = when (name) {
            "proxy" -> Proxy
            "vpn" -> VPN
            else -> VPN
        }
    }
}

enum class SplitTunnelMode(val value: String) {
    Disabled("disabled"),
    Whitelist("whitelist"),
    Blacklist("blacklist");

    companion object {
        fun fromString(value: String?): SplitTunnelMode = when (value) {
            "whitelist" -> Whitelist
            "blacklist" -> Blacklist
            else -> Disabled
        }
    }
}
