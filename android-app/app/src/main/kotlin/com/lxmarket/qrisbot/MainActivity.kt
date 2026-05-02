package com.lxmarket.qrisbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lxmarket.qrisbot.databinding.ActivityMainBinding
import com.lxmarket.qrisbot.network.WebSocketManager
import com.lxmarket.qrisbot.service.ScreenCaptureService

private const val TAG = "MainActivity"
private const val REQ_MEDIA_PROJECTION = 100
private const val REQ_ACCESSIBILITY = 101

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Default server URL (bisa diubah user)
        binding.etServerUrl.setText("ws://192.168.1.100:8765")

        setupListeners()
        setupWebSocketCallbacks()
        checkAccessibilityService()
    }

    private fun setupListeners() {
        // Tombol Connect ke server
        binding.btnConnect.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isBlank()) {
                toast("Masukkan URL server terlebih dahulu")
                return@setOnClickListener
            }
            connectToServer(url)
        }

        // Tombol Disconnect
        binding.btnDisconnect.setOnClickListener {
            WebSocketManager.disconnect()
            updateStatus("Terputus")
        }

        // Tombol minta izin screenshot
        binding.btnRequestScreenshot.setOnClickListener {
            requestMediaProjectionPermission()
        }

        // Tombol buka Accessibility Settings
        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun connectToServer(url: String) {
        updateStatus("Menghubungkan ke $url...")
        WebSocketManager.connect(url)
        log("Connecting to $url")
    }

    private fun setupWebSocketCallbacks() {
        WebSocketManager.onConnectionStatus = { connected, message ->
            runOnUiThread {
                updateStatus(if (connected) "✅ $message" else "❌ $message")
                binding.btnConnect.isEnabled = !connected
                binding.btnDisconnect.isEnabled = connected
            }
        }
    }

    // ── MediaProjection Permission ────────────────────────────────────────────
    private fun requestMediaProjectionPermission() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Start ScreenCaptureService dengan token MediaProjection
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(serviceIntent)
                log("ScreenCaptureService dimulai")
                toast("Izin screenshot diberikan ✅")
                binding.tvScreenshotStatus.text = "Screenshot: ✅ Aktif"
            } else {
                toast("Izin screenshot ditolak")
                binding.tvScreenshotStatus.text = "Screenshot: ❌ Belum diizinkan"
            }
        }
    }

    // ── Accessibility ─────────────────────────────────────────────────────────
    private fun checkAccessibilityService() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            binding.tvAccessibilityStatus.text = "Accessibility: ✅ Aktif"
        } else {
            binding.tvAccessibilityStatus.text = "Accessibility: ❌ Belum aktif"
            toast("Aktifkan Accessibility Service untuk QRIS Bot")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/.service.QrisAccessibilityService"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(serviceName)
    }

    private fun openAccessibilitySettings() {
        startActivityForResult(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            REQ_ACCESSIBILITY
        )
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────
    private fun updateStatus(msg: String) {
        binding.tvConnectionStatus.text = msg
        log(msg)
    }

    private fun log(msg: String) {
        val current = binding.tvLog.text.toString()
        val lines = current.split("\n").takeLast(50)  // Max 50 baris log
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        binding.tvLog.text = (lines + "[$timestamp] $msg").joinToString("\n")
        Log.d(TAG, msg)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
    }
}
