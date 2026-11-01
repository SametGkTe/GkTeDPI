package com.sametgkte.gktedpi.services

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sametgkte.gktedpi.data.AppStatus
import com.sametgkte.gktedpi.data.Mode
import com.sametgkte.gktedpi.data.RESTART_ACTION
import com.sametgkte.gktedpi.data.START_ACTION
import com.sametgkte.gktedpi.data.STOP_ACTION
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.mode

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    fun start(context: Context, mode: Mode) {
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Starting GkTeDPI VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java).apply {
                    action = START_ACTION
                }
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Starting GkTeDPI Proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = START_ACTION
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun stop(context: Context) {
        val (_, mode) = appStatus
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Stopping GkTeDPI VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java).apply {
                    action = STOP_ACTION
                }
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Stopping GkTeDPI Proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = STOP_ACTION
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun restartIfRunning(context: Context) {
        val (status, activeMode) = appStatus
        if (status != AppStatus.Running) return
        val configuredMode = context.getPreferences().mode()
        if (activeMode != configuredMode) {
            stop(context)
            return
        }
        when (activeMode) {
            Mode.VPN -> {
                val intent = Intent(context, ByeDpiVpnService::class.java).apply {
                    action = RESTART_ACTION
                }
                ContextCompat.startForegroundService(context, intent)
            }
            Mode.Proxy -> {
                val intent = Intent(context, ByeDpiProxyService::class.java).apply {
                    action = RESTART_ACTION
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
