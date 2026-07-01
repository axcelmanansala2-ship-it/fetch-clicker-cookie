package com.smartsystem.autoclicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartsystem.autoclicker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnManageTargets.setOnClickListener {
            startActivity(Intent(this, TargetSetupActivity::class.java))
        }
        binding.btnStartService.setOnClickListener { startOverlayService() }
        binding.btnStopService.setOnClickListener { stopOverlayService() }

        // NEW: Account Checker
        binding.btnAccountChecker.setOnClickListener {
            startActivity(Intent(this, AccountCheckerActivity::class.java))
        }
    }

    private fun refreshPermissionStatus() {
        val overlayOk = hasOverlayPermission()
        val accessibilityOk = isAccessibilityEnabled()

        binding.iconOverlay.setImageResource(
            if (overlayOk) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
        binding.iconAccessibility.setImageResource(
            if (accessibilityOk) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )

        val allReady = overlayOk && accessibilityOk
        binding.btnStartService.isEnabled = allReady
        binding.tvStatus.text = if (allReady)
            getString(R.string.status_ready)
        else
            getString(R.string.status_missing)
        binding.tvStatus.setTextColor(
            if (allReady) getColor(R.color.colorSuccess) else getColor(R.color.colorWarning)
        )
    }

    private fun hasOverlayPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun startOverlayService() {
        if (!hasOverlayPermission() || !isAccessibilityEnabled()) {
            Toast.makeText(this, getString(R.string.status_missing), Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this,
            Intent(this, FloatingOverlayService::class.java))
        Toast.makeText(this, getString(R.string.msg_service_started), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun stopOverlayService() {
        startService(Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        })
        Toast.makeText(this, getString(R.string.msg_service_stopped), Toast.LENGTH_SHORT).show()
    }
}
