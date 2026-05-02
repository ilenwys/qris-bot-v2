package com.lxmarket.qrisbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.Base64

private const val TAG = "ScreenCaptureService"
private const val CHANNEL_ID = "qris_bot_channel"
private const val NOTIFICATION_ID = 1001

/**
 * Foreground service yang memegang MediaProjection token
 * untuk capture screenshot kapan saja dibutuhkan.
 */
class ScreenCaptureService : Service() {

    companion object {
        // Singleton instance agar dapat diakses dari mana saja
        @Volatile
        private var instance: ScreenCaptureService? = null

        fun getInstance(): ScreenCaptureService? = instance

        const val ACTION_START = "START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && resultData != null) {
                    startForegroundService()
                    initMediaProjection(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid MediaProjection data")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QRIS Bot Aktif")
            .setContentText("Monitoring layar untuk otomasi QRIS...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupImageReader()
        Log.i(TAG, "MediaProjection initialized: ${screenWidth}x${screenHeight} @${screenDensity}dpi")
    }

    private fun setupImageReader() {
        // Clean up existing resources first
        virtualDisplay?.release()
        imageReader?.close()

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2  // Max images in queue
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "QrisBotCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    /**
     * Ambil screenshot saat ini dan return sebagai Bitmap.
     * Harus dipanggil dari coroutine / background thread.
     */
    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: run {
            Log.e(TAG, "ImageReader null — MediaProjection belum siap")
            return null
        }

        var image: Image? = null
        return try {
            // Retry sampai 5x dengan delay 100ms untuk mendapat frame terbaru
            repeat(5) { attempt ->
                image = reader.acquireLatestImage()
                if (image != null) return@repeat
                Thread.sleep(100)
            }

            val img = image ?: run {
                Log.w(TAG, "No image available")
                return null
            }

            val planes = img.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop padding jika ada
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    .also { bitmap.recycle() }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen error: ${e.message}", e)
            null
        } finally {
            image?.close()
        }
    }

    /**
     * Ambil screenshot dan encode sebagai Base64 PNG dengan delay opsional.
     */
    fun captureScreenBase64(delayMs: Long = 0): String? {
        if (delayMs > 0) Thread.sleep(delayMs)

        val bitmap = captureScreen() ?: return null
        return try {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
                bitmap.recycle()
                Base64.getEncoder().encodeToString(baos.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode error: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
        Log.i(TAG, "ScreenCaptureService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification Channel ─────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QRIS Bot",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "QRIS Bot background service"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
