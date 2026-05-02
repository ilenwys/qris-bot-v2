package com.lxmarket.qrisbot.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "ImageMatcher"

/**
 * Template matching menggunakan normalized cross-correlation.
 * Mencari posisi gambar template di dalam screenshot.
 */
object ImageMatcher {

    private const val DEFAULT_THRESHOLD = 0.75f   // 75% kesamaan minimum
    private const val SEARCH_STEP = 4             // Pixel step saat scan (lebih besar = lebih cepat, kurang akurat)
    private const val REFINE_STEP = 1             // Refinement step setelah hit ditemukan

    data class MatchResult(
        val found: Boolean,
        val x: Int = 0,
        val y: Int = 0,
        val centerX: Int = 0,
        val centerY: Int = 0,
        val confidence: Float = 0f,
    )

    /**
     * Cari template di dalam screenshot.
     *
     * @param screenshot Bitmap layar penuh
     * @param template   Bitmap gambar yang dicari
     * @param threshold  Minimum confidence (0.0 - 1.0)
     */
    fun findTemplate(
        screenshot: Bitmap,
        template: Bitmap,
        threshold: Float = DEFAULT_THRESHOLD,
    ): MatchResult {
        val sw = screenshot.width
        val sh = screenshot.height
        val tw = template.width
        val th = template.height

        if (tw > sw || th > sh) {
            Log.w(TAG, "Template ($tw x $th) lebih besar dari screenshot ($sw x $sh)")
            return MatchResult(false)
        }

        // Extract pixel arrays untuk performa
        val sPixels = IntArray(sw * sh).also { screenshot.getPixels(it, 0, sw, 0, 0, sw, sh) }
        val tPixels = IntArray(tw * th).also { template.getPixels(it, 0, tw, 0, 0, tw, th) }

        var bestScore = 0f
        var bestX = 0
        var bestY = 0

        // Phase 1: Kasar — scan dengan step besar
        var y = 0
        while (y <= sh - th) {
            var x = 0
            while (x <= sw - tw) {
                val score = computeNCC(sPixels, sw, tPixels, tw, th, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += SEARCH_STEP
            }
            y += SEARCH_STEP
        }

        if (bestScore < threshold * 0.7f) {
            // Jauh di bawah threshold, skip refinement
            return MatchResult(false)
        }

        // Phase 2: Halus — refine di sekitar area terbaik
        val searchRadius = SEARCH_STEP * 2
        val x1 = (bestX - searchRadius).coerceAtLeast(0)
        val y1 = (bestY - searchRadius).coerceAtLeast(0)
        val x2 = (bestX + searchRadius).coerceAtMost(sw - tw)
        val y2 = (bestY + searchRadius).coerceAtMost(sh - th)

        var ry = y1
        while (ry <= y2) {
            var rx = x1
            while (rx <= x2) {
                val score = computeNCC(sPixels, sw, tPixels, tw, th, rx, ry)
                if (score > bestScore) {
                    bestScore = score
                    bestX = rx
                    bestY = ry
                }
                rx += REFINE_STEP
            }
            ry += REFINE_STEP
        }

        val found = bestScore >= threshold
        Log.d(TAG, "Match: found=$found score=${String.format("%.3f", bestScore)} pos=($bestX,$bestY)")

        return MatchResult(
            found = found,
            x = bestX,
            y = bestY,
            centerX = bestX + tw / 2,
            centerY = bestY + th / 2,
            confidence = bestScore,
        )
    }

    /**
     * Normalized Cross-Correlation antara region screenshot dan template.
     * Return nilai 0.0 (tidak cocok) - 1.0 (sempurna).
     */
    private fun computeNCC(
        sPixels: IntArray, sw: Int,
        tPixels: IntArray, tw: Int, th: Int,
        ox: Int, oy: Int,
    ): Float {
        var sumS = 0.0
        var sumT = 0.0
        var count = 0

        // Sampling — check setiap 2 pixel untuk performa
        for (ty in 0 until th step 2) {
            for (tx in 0 until tw step 2) {
                val sp = sPixels[(oy + ty) * sw + (ox + tx)]
                val tp = tPixels[ty * tw + tx]

                // Gunakan luminance
                val sl = luminance(sp)
                val tl = luminance(tp)

                sumS += sl
                sumT += tl
                count++
            }
        }

        if (count == 0) return 0f

        val meanS = sumS / count
        val meanT = sumT / count

        var numerator = 0.0
        var denomS = 0.0
        var denomT = 0.0

        for (ty in 0 until th step 2) {
            for (tx in 0 until tw step 2) {
                val sp = sPixels[(oy + ty) * sw + (ox + tx)]
                val tp = tPixels[ty * tw + tx]

                val sl = luminance(sp) - meanS
                val tl = luminance(tp) - meanT

                numerator += sl * tl
                denomS += sl * sl
                denomT += tl * tl
            }
        }

        val denom = sqrt(denomS * denomT)
        return if (denom < 1e-10) 0f else (numerator / denom).toFloat().coerceIn(0f, 1f)
    }

    private fun luminance(pixel: Int): Double {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /**
     * Load template dari assets folder.
     */
    fun loadFromAssets(context: Context, filename: String): Bitmap? {
        return try {
            context.assets.open(filename).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal load template '$filename': ${e.message}")
            null
        }
    }

    /**
     * Decode Bitmap dari Base64 string.
     */
    fun decodeBase64(base64: String): Bitmap? {
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal decode base64 bitmap: ${e.message}")
            null
        }
    }
}
