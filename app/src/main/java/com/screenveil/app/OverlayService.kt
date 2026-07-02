        package com.screenveil.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * OverlayService draws a full-screen, touch-transparent, translucent black
 * [View] on top of every other window using [WindowManager] +
 * TYPE_APPLICATION_OVERLAY (the modern SYSTEM_ALERT_WINDOW window type).
 *
 * It runs as a Foreground Service so the OS does not kill it while
 * ScreenVeil is in the background, and it posts the persistent
 * notification required for foreground services since Android 8.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var prefs: PreferencesManager

    // Handler + Runnable used only when Video Mode is on. See
    // startVideoModeRefresh() for what this does and why.
    private val videoModeHandler = Handler(Looper.getMainLooper())
    private var videoModeRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val opacity = intent.getIntExtra(EXTRA_OPACITY, prefs.getOpacity())
                // startForeground() must be called within a few seconds of
                // startForegroundService() or the OS will throw an ANR-like
                // exception - do it first, before any other work.
                startForeground(NOTIFICATION_ID, buildNotification(opacity))
                showOverlay(opacity)
                if (prefs.isVideoModeEnabled()) startVideoModeRefresh()
                prefs.setEnabled(true)
            }
            ACTION_STOP -> {
                prefs.setEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_OPACITY -> {
                val opacity = intent.getIntExtra(EXTRA_OPACITY, prefs.getOpacity())
                updateOverlayOpacity(opacity)
                refreshNotification(opacity)
            }
            ACTION_SET_VIDEO_MODE -> {
                val enabled = intent.getBooleanExtra(EXTRA_VIDEO_MODE, false)
                if (enabled) startVideoModeRefresh() else stopVideoModeRefresh()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVideoModeRefresh()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------
    // Overlay window management
    // ---------------------------------------------------------------

    /**
     * IMPORTANT PLATFORM LIMITATION - read before changing this function:
     *
     * This overlay (and ANY SYSTEM_ALERT_WINDOW-based overlay, from any app)
     * can never draw on top of the actual status bar icons/clock or the
     * pulled-down notification shade itself. Android reserves those specific
     * layers exclusively for the system (com.android.systemui), specifically
     * so third-party apps can never block or spoof them - this is a
     * deliberate security boundary, not a missing permission. An
     * AccessibilityService does not get around this either: its overlay type
     * (TYPE_ACCESSIBILITY_OVERLAY) sits in the same "below system UI" bracket
     * for the same reason. Only a rooted/system-signed build could change
     * this, which is out of scope here.
     *
     * What we CAN do: on most modern Android builds the status bar's own
     * background is transparent (only the icons/clock are opaque), showing
     * whatever is drawn behind it. By default WindowManager insets our view
     * away from that area. Telling it not to (below) lets our translucent
     * layer extend behind that transparent strip, so the area around the
     * status bar icons dims along with the rest of the screen - the icons
     * themselves just stay at fixed brightness, same as every dimmer/night
     * filter app.
     */
    private fun showOverlay(opacityPercent: Int) {
        if (overlayView != null) {
            updateOverlayOpacity(opacityPercent)
            return
        }

        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = opacityPercent.coerceIn(0, 50) / 100f

            // Lay out as if system bars reserve no space, so this view's
            // bounds extend under the status bar / navigation bar instead
            // of stopping right below them.
            @Suppress("DEPRECATION")
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE make the overlay
            // purely visual: every touch/tap passes straight through to
            // whatever app is underneath it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Extend into notch/cutout area too, on devices that have one.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun updateOverlayOpacity(opacityPercent: Int) {
        val clamped = opacityPercent.coerceIn(0, 50)
        overlayView?.alpha = clamped / 100f
        prefs.setOpacity(clamped)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                // View was already detached - safe to ignore.
            }
        }
        overlayView = null
    }

    // ---------------------------------------------------------------
    // Video Mode (best-effort, see in-app explanation for limitations)
    // ---------------------------------------------------------------

    /**
     * On some OEM ROMs (including ColorOS), fullscreen video players can
     * momentarily reorder the window stack when entering/exiting fullscreen
     * or when the player's own controls show/hide. Periodically calling
     * updateViewLayout() nudges WindowManager to keep our overlay
     * composited above the video surface.
     *
     * IMPORTANT LIMITATION: this cannot help against DRM-protected video
     * paths that render through a protected hardware overlay/hardware
     * composer layer bypassing normal window compositing - in that case
     * the video frame can be presented by the display hardware itself
     * "beneath" software-drawn windows in ways SYSTEM_ALERT_WINDOW cannot
     * influence. This is a platform-level restriction, not something an
     * app can work around.
     */
    private fun startVideoModeRefresh() {
        stopVideoModeRefresh()
        val runnable = object : Runnable {
            override fun run() {
                overlayView?.let { view ->
                    try {
                        windowManager.updateViewLayout(view, view.layoutParams)
                    } catch (e: Exception) {
                        // View may not be currently attached - ignore and retry later.
                    }
                }
                videoModeHandler.postDelayed(this, VIDEO_MODE_REFRESH_MS)
            }
        }
        videoModeRunnable = runnable
        videoModeHandler.postDelayed(runnable, VIDEO_MODE_REFRESH_MS)
    }

    private fun stopVideoModeRefresh() {
        videoModeRunnable?.let { videoModeHandler.removeCallbacks(it) }
        videoModeRunnable = null
    }

    // ---------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScreenVeil Overlay",
                NotificationManager.IMPORTANCE_LOW // LOW = silent, no heads-up popup
            ).apply {
                description = "Persistent status notification while the dimming overlay is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(opacityPercent: Int): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_STOP, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, REQUEST_CODE_OPEN, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, opacityPercent))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.notification_action_off), stopPendingIntent)
            .build()
    }

    private fun refreshNotification(opacityPercent: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(opacityPercent))
    }

    companion object {
        const val ACTION_START = "com.screenveil.app.ACTION_START"
        const val ACTION_STOP = "com.screenveil.app.ACTION_STOP"
        const val ACTION_UPDATE_OPACITY = "com.screenveil.app.ACTION_UPDATE_OPACITY"
        const val ACTION_SET_VIDEO_MODE = "com.screenveil.app.ACTION_SET_VIDEO_MODE"

        const val EXTRA_OPACITY = "extra_opacity"
        const val EXTRA_VIDEO_MODE = "extra_video_mode"

        private const val CHANNEL_ID = "screenveil_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_STOP = 100
        private const val REQUEST_CODE_OPEN = 101
        private const val VIDEO_MODE_REFRESH_MS = 3000L
    }
}
