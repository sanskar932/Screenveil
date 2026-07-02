package com.screenveil.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Listens for ACTION_BOOT_COMPLETED. If the overlay was enabled before the
 * last shutdown/reboot (per SharedPreferences), automatically restarts
 * OverlayService so the user doesn't have to manually re-enable it.
 *
 * Note: on Android 12+, SYSTEM_ALERT_WINDOW permission survives reboots
 * once granted, so no re-prompt is needed here - we just double check
 * with Settings.canDrawOverlays() before starting, since a user could in
 * theory have revoked it from system settings between reboots.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PreferencesManager(context)
        if (!prefs.isEnabled()) return

        val hasPermission = Settings.canDrawOverlays(context)
        if (!hasPermission) return // Can't draw the overlay without it; user must re-grant manually.

        val serviceIntent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_OPACITY, prefs.getOpacity())
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
