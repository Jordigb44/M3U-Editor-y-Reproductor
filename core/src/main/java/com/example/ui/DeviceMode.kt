package com.example.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

object DeviceMode {
    /**
     * Determines whether the current device is a Television / TV Box / Fire TV / Fire OS.
     * Checks:
     * 1. UiModeManager Configuration.UI_MODE_TYPE_TELEVISION
     * 2. PackageManager.FEATURE_LEANBACK (Google TV / Android TV)
     * 3. Amazon Fire TV hardware feature ("amazon.hardware.fire_tv")
     * 4. Absence of touchscreen feature
     * 5. Amazon AFT model prefix (Fire TV Stick / Cube / Edition TVs)
     */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }

        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        ) {
            return true
        }

        val model = Build.MODEL.uppercase()
        val manufacturer = Build.MANUFACTURER.uppercase()
        return model.startsWith("AFT") || // All Amazon Fire TV models start with AFT (e.g. AFTMM, AFTSS, AFTSO, AFTT)
            (manufacturer.contains("AMAZON") && !hasTouchscreen(context)) ||
            model.contains("FIRE TV") ||
            model.contains("FIRETV") ||
            model.contains("ANDROID TV") ||
            model.contains("GOOGLE TV") ||
            model.contains("BOX") ||
            model.contains("BRAVIA") ||
            model.contains("MIBOX")
    }

    /**
     * Returns true if device has a physical touchscreen.
     */
    fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
