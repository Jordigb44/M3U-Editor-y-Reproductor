package com.example.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceMode {
    /**
     * Determines whether the current device is a Television / TV Box / Fire TV.
     * Checks:
     * 1. UiModeManager Configuration.UI_MODE_TYPE_TELEVISION
     * 2. PackageManager.FEATURE_LEANBACK (Google TV / Android TV)
     * 3. Amazon Fire TV hardware feature ("amazon.hardware.fire_tv")
     * 4. Absence of touchscreen feature
     */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }

        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * Returns true if device has a physical touchscreen.
     */
    fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
