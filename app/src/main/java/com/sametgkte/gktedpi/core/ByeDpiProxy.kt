package com.sametgkte.gktedpi.core

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ByeDpiProxy {
    companion object {
        private const val TAG = "ByeDpiProxy"

        init {
            System.loadLibrary("byedpi")
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var fd = -1

    suspend fun startProxy(preferences: ByeDpiProxyPreferences): Int {
        val fd = createSocket(preferences)
        if (fd < 0) {
            Log.e(TAG, "Failed to create proxy socket (fd=$fd)")
            return -1
        }
        return jniStartProxy(fd)
    }

    suspend fun stopProxy(): Int {
        mutex.withLock {
            if (fd < 0) {
                Log.w(TAG, "Proxy is not running")
                return -1
            }

            val currentFd = fd
            fd = -1
            val result = jniStopProxy(currentFd)
            return result
        }
    }

    private suspend fun createSocket(preferences: ByeDpiProxyPreferences): Int =
        mutex.withLock {
            if (fd >= 0) {
                throw IllegalStateException("Proxy is already running with fd=$fd")
            }

            val args = preferences.toCommandArgs()
            Log.i(TAG, "Creating socket with args: ${args.joinToString(" ")}")

            val createdFd = jniCreateSocketWithCommandLine(args)
            if (createdFd < 0) {
                return -1
            }

            this.fd = createdFd
            createdFd
        }

    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    private external fun jniStopProxy(fd: Int): Int
}
