package com.lxmarket.qrisbot.util

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.util.Base64

private const val TAG = "QrDecoder"

/**
 * Decode QR code dari Bitmap dan crop region QR dari screenshot.
 */
object QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true))
    }

    /**
     * Decode QR code dari Bitmap.
     * Return decoded text atau null jika gagal.
     */
    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        return try {
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)
            Log.d(TAG, "QR decoded: ${result.text.take(80)}...")
            result.text
        } catch (e: NotFoundException) {
            Log.d(TAG, "QR not found in bitmap")
            null
        } catch (e: Exception) {
            Log.e(TAG, "QR decode error: ${e.message}")
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * Crop area QR code dari screenshot (posisi relatif).
     * Berdasarkan layout GoPay: QR ada di bagian tengah layar.
     *
     * @param screenshot Full screen bitmap
     * @param xPct Start X (0.0-1.0), default 0.05 (5%)
     * @param yPct Start Y (0.0-1.0), default 0.25 (25%)
     * @param wPct Width (0.0-1.0), default 0.90 (90%)
     * @param hPct Height (0.0-1.0), default 0.40 (40%)
     */
    fun cropQrRegion(
        screenshot: Bitmap,
        xPct: Float = 0.05f,
        yPct: Float = 0.28f,
        wPct: Float = 0.90f,
        hPct: Float = 0.38f,
    ): Bitmap {
        val x = (screenshot.width * xPct).toInt()
        val y = (screenshot.height * yPct).toInt()
        val w = (screenshot.width * wPct).toInt().coerceAtMost(screenshot.width - x)
        val h = (screenshot.height * hPct).toInt().coerceAtMost(screenshot.height - y)
        return Bitmap.createBitmap(screenshot, x, y, w, h)
    }

    /**
     * Encode Bitmap ke Base64 PNG string.
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 90): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, quality, baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
