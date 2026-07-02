package com.screenveil.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenveil.app.databinding.ActivityMainBinding

/**
 * Single-screen UI: ON/OFF toggle, opacity slider + label, overlay
 * permission button, and the Video Mode toggle. All state is persisted
 * via [PreferencesManager] and mirrored to [OverlayService] while it is
 * running.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    // Android 13+ requires runtime permission to post notifications; the
    // persistent status notification won't show without it.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.notification_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        requestNotificationPermissionIfNeeded()
        restoreUiState()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // The overlay permission can be revoked from system Settings while
        // the app is backgrounded, so re-check every time we come forward.
        updatePermissionButtonState()
    }

    /** Loads saved preferences into the UI on launch. */
    private fun restoreUiState() {
        val opacity = prefs.getOpacity()
        binding.sliderOpacity.value = opacity.toFloat()
        binding.textOpacityValue.text = getString(R.string.opacity_value, opacity)
        binding.switchEnable.isChecked = prefs.isEnabled() && hasOverlayPermission()
        binding.switchVideoMode.isChecked = prefs.isVideoModeEnabled()
    }

    private fun setupListeners() {
        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!hasOverlayPermission()) {
                    Toast.makeText(this, getString(R.string.grant_permission_first), Toast.LENGTH_LONG).show()
                    binding.switchEnable.isChecked = false
                    requestOverlayPermission()
                } else {
                    startOverlay()
                }
            } else {
                stopOverlay()
            }
        }

        binding.sliderOpacity.addOnChangeListener { _, value, _ ->
            val opacity = value.toInt()
            binding.textOpacityValue.text = getString(R.string.opacity_value, opacity)
            prefs.setOpacity(opacity)
            if (prefs.isEnabled()) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_OPACITY
                    putExtra(OverlayService.EXTRA_OPACITY, opacity)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }

        binding.switchVideoMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.setVideoModeEnabled(isChecked)
            if (prefs.isEnabled()) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SET_VIDEO_MODE
                    putExtra(OverlayService.EXTRA_VIDEO_MODE, isChecked)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }

        binding.buttonPermission.setOnClickListener { requestOverlayPermission() }
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_OPACITY, prefs.getOpacity())
        }
        ContextCompat.startForegroundService(this, intent)
        prefs.setEnabled(true)
    }

    private fun stopOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
        prefs.setEnabled(false)
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun updatePermissionButtonState() {
        val granted = hasOverlayPermission()
        binding.buttonPermission.text = if (granted) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.grant_permission)
        }
        binding.buttonPermission.isEnabled = !granted

        // If permission was revoked while the overlay was supposedly on,
        // reflect reality in the UI and stop trying to run the service.
        if (!granted && binding.switchEnable.isChecked) {
            binding.switchEnable.isChecked = false
            stopOverlay()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
