package com.sametgkte.gktedpi.services

import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode

data class TrafficStats(
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txSpeedBps: Long = 0L,
    val rxSpeedBps: Long = 0L,
)

@Volatile
var appStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN
    private set

@Volatile
var connectedSinceElapsedMs: Long = 0L
    private set

@Volatile
var currentTrafficStats: TrafficStats = TrafficStats()
    private set

fun setStatus(status: AppStatus, mode: Mode) {
    if (status == AppStatus.Running && appStatus.first != AppStatus.Running) {
        connectedSinceElapsedMs = android.os.SystemClock.elapsedRealtime()
    } else if (status == AppStatus.Halted) {
        connectedSinceElapsedMs = 0L
        currentTrafficStats = TrafficStats()
    }
    appStatus = status to mode
}

fun updateTrafficStats(stats: TrafficStats) {
    currentTrafficStats = stats
}
