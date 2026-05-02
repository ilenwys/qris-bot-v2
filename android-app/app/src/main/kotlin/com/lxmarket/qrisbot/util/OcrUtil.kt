package com.lxmarket.qrisbot.util

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OcrUtil"

/**
 * OCR menggunakan Google ML Kit (offline, on-device).
 * Digunakan untuk membaca:
 *  - Timer countdown ("9:59") di halaman QR aktif
 *  - Nominal harga ("Rp1") untuk konfirmasi
 */
object OcrUtil {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Baca semua teks dari Bitmap.
     * Return string gabungan semua baris teks.
     */
    suspend fun recognizeText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text
                    Log.d(TAG, "OCR result: $text")
                    cont.resume(text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }
    }

    /**
     * Cari pola timer "MM:SS" atau "M:SS" dalam teks OCR.
     * Return null jika tidak ditemukan.
     */
    fun extractTimer(ocrText: String): String? {
        // Pattern: 1-2 digit, titik dua, 2 digit → "9:59", "10:00", "1:30"
        val regex = Regex("""\b(\d{1,2}:\d{2})\b""")
        val match = regex.find(ocrText)
        return match?.value?.also { Log.d(TAG, "Timer extracted: $it") }
    }

    /**
     * Cari nominal rupiah dalam teks OCR.
     * Contoh input: "Rp1.000" → 1000, "Rp10.000" → 10000
     */
    fun extractAmount(ocrText: String): Int? {
        // Pattern: "Rp" diikuti angka dengan/tanpa titik/koma
        val regex = Regex("""Rp\s*([\d.,]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(ocrText) ?: return null
        val raw = match.groupValues[1]
            .replace(".", "")
            .replace(",", "")
        return raw.toIntOrNull()?.also { Log.d(TAG, "Amount extracted: $it") }
    }

    /**
     * Crop region tertentu dari Bitmap untuk OCR lebih akurat.
     * Region dinyatakan sebagai persentase dari ukuran layar.
     *
     * @param bitmap    Source bitmap
     * @param xPct      X start (0.0 - 1.0)
     * @param yPct      Y start (0.0 - 1.0)
     * @param wPct      Width (0.0 - 1.0)
     * @param hPct      Height (0.0 - 1.0)
     */
    fun cropRegion(bitmap: Bitmap, xPct: Float, yPct: Float, wPct: Float, hPct: Float): Bitmap {
        val x = (bitmap.width * xPct).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * yPct).toInt().coerceIn(0, bitmap.height - 1)
        val w = (bitmap.width * wPct).toInt().coerceAtMost(bitmap.width - x)
        val h = (bitmap.height * hPct).toInt().coerceAtMost(bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }
}
