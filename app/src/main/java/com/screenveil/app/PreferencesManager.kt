package com.screenveil.app

import android.content.Context

/**
 * Thin wrapper around SharedPreferences used to persist the overlay's
 * enabled state, opacity level, and Video Mode flag between app launches
 * and device reboots. No cloud sync, no external storage - everything
 * stays on-device.
 */
class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the overlay should be considered "on". */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Opacity as a whole-number percentage, 0-50. */
    fun getOpacity(): Int = prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY)

    fun setOpacity(opacity: Int) {
        prefs.edit().putInt(KEY_OPACITY, opacity.coerceIn(MIN_OPACITY, MAX_OPACITY)).apply()
    }

    /** Whether Video Mode's overlay-refresh behavior is enabled. */
    fun isVideoModeEnabled(): Boolean = prefs.getBoolean(KEY_VIDEO_MODE, false)

    fun setVideoModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_MODE, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "screenveil_prefs"
        private const val KEY_ENABLED = "key_enabled"
        private const val KEY_OPACITY = "key_opacity"
        private const val KEY_VIDEO_MODE = "key_video_mode"

        const val DEFAULT_OPACITY = 20
        const val MIN_OPACITY = 0
        const val MAX_OPACITY = 50
    }
}
