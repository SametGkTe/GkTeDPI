package com.sametgkte.gktedpi

import android.app.Application
import com.sametgkte.gktedpi.smart.IspAutoSwitchManager
import com.sametgkte.gktedpi.utility.ThemeUtils

class GkTeDpiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeUtils.applySavedTheme(this)
        IspAutoSwitchManager.registerMonitor(this)
    }
}
