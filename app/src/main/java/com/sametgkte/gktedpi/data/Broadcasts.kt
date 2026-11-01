package com.sametgkte.gktedpi.data

const val STARTED_BROADCAST = "com.sametgkte.gktedpi.STARTED"
const val STOPPED_BROADCAST = "com.sametgkte.gktedpi.STOPPED"
const val FAILED_BROADCAST = "com.sametgkte.gktedpi.FAILED"

const val SENDER = "sender"

enum class Sender(val senderName: String) {
    Proxy("SOCKS5 Proxy"),
    VPN("GkTeDPI VPN")
}
