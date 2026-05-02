package com.lxmarket.qrisbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.gson.JsonObject
import com.lxmarket.qrisbot.network.WebSocketManager
import com.lxmarket.qrisbot.util.ImageMatcher
import com.lxmarket.qrisbot.util.OcrUtil
import com.lxmarket.qrisbot.util.QrDecoder
import kotlinx.coroutines.*

private const val TAG = "QrisAccessibilityService"

/**
 * Accessibility Service utama.
 * Menangani perintah dari server dan mengeksekusi alur otomasi:
 *
 * 1. Screenshot → Cari tombol QR code (template2)
 * 2. Tap tombol QR → Tunggu halaman pembayaran (template3)
 * 3. Cari tombol "Tambah Nominal" (template4) → Tap
 * 4. Input nominal digit per digit
 * 5. Verifikasi nominal dengan OCR
 * 6. Cari tombol "Terapkan" (template6) → Tap
 * 7. Tunggu halaman QR aktif (template7) → OCR timer + capture QR
 * 8. Kirim hasil ke server
 */
class QrisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: QrisAccessibilityService? = null

        fun getInstance(): QrisAccessibilityService? = instance
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // Template bitmaps (dimuat dari assets saat service start)
    private var tplQrButton: Bitmap? = null        // Template 2: tombol QR hijau
    private var tplTambahNominal: Bitmap? = null   // Template 4: tombol "Tambah Nominal"
    private var tplTerapkan: Bitmap? = null        // Template 6: tombol "Terapkan" hijau

    // State
    private var currentRequestId: String = ""
    private var currentAmount: Int = 0
    private var isRunning: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected")

        // Load templates dari assets
        loadTemplates()

        // Setup WebSocket command handler
        WebSocketManager.onCommandReceived = { json ->
            handleServerCommand(json)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak diperlukan — kita pakai screenshot + template matching
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mainScope.cancel()
        isRunning = false
    }

    // ── Template Loading ──────────────────────────────────────────────────────
    private fun loadTemplates() {
        mainScope.launch(Dispatchers.IO) {
            tplQrButton = ImageMatcher.loadFromAssets(applicationContext, "tpl_qr_button.png")
            tplTambahNominal = ImageMatcher.loadFromAssets(applicationContext, "tpl_tambah_nominal.png")
            tplTerapkan = ImageMatcher.loadFromAssets(applicationContext, "tpl_terapkan.png")

            val loaded = listOfNotNull(
                if (tplQrButton != null) "qr_button" else null,
                if (tplTambahNominal != null) "tambah_nominal" else null,
                if (tplTerapkan != null) "terapkan" else null,
            )
            Log.i(TAG, "Templates loaded: $loaded")

            if (loaded.size < 3) {
                Log.e(TAG, "Beberapa template tidak ditemukan di assets! Pastikan file PNG ada di app/src/main/assets/")
            }
        }
    }

    // ── Command Handler ───────────────────────────────────────────────────────
    private fun handleServerCommand(json: JsonObject) {
        val type = json.get("type")?.asString ?: return
        val requestId = json.get("request_id")?.asString ?: ""

        if (type == "send_qris") {
            val amount = json.get("amount")?.asInt ?: 0
            if (amount <= 0) {
                WebSocketManager.sendError(requestId, "Amount harus lebih dari 0")
                return
            }

            if (isRunning) {
                WebSocketManager.sendError(requestId, "Otomasi sedang berjalan, tunggu selesai")
                return
            }

            Log.i(TAG, "Command: send_qris amount=$amount reqId=$requestId")
            mainScope.launch { executeQrisFlow(requestId, amount) }
        }
    }

    // ── Main Automation Flow ──────────────────────────────────────────────────
    private suspend fun executeQrisFlow(requestId: String, amount: Int) {
        isRunning = true
        currentRequestId = requestId
        currentAmount = amount

        try {
            WebSocketManager.sendStatus(requestId, "Memulai otomasi QRIS amount=$amount")

            // Step 1: Screenshot dan cari tombol QR
            WebSocketManager.sendStatus(requestId, "Mencari tombol QR code...")
            val qrBtnPos = waitForTemplate(tplQrButton, "tpl_qr_button", requestId, timeoutMs = 15_000)
                ?: return

            // Step 2: Tap tombol QR
            WebSocketManager.sendStatus(requestId, "Menekan tombol QR (${qrBtnPos.centerX}, ${qrBtnPos.centerY})")
            performTap(qrBtnPos.centerX.toFloat(), qrBtnPos.centerY.toFloat())
            delay(1500)

            // Step 3: Cari tombol "Tambah Nominal" di halaman Pembayaran
            WebSocketManager.sendStatus(requestId, "Mencari tombol Tambah Nominal...")
            val tambahPos = waitForTemplate(tplTambahNominal, "tpl_tambah_nominal", requestId, timeoutMs = 15_000)
                ?: return

            // Step 4: Tap "Tambah Nominal"
            WebSocketManager.sendStatus(requestId, "Menekan Tambah Nominal")
            performTap(tambahPos.centerX.toFloat(), tambahPos.centerY.toFloat())
            delay(1000)

            // Step 5: Input nominal
            WebSocketManager.sendStatus(requestId, "Menginput nominal: $amount")
            inputAmount(amount, requestId)
            delay(800)

            // Step 6: Verifikasi OCR nominal
            val screenshot = captureCurrentScreen()
            if (screenshot != null) {
                val ocrText = OcrUtil.recognizeText(screenshot)
                val detected = OcrUtil.extractAmount(ocrText)
                Log.i(TAG, "OCR amount: $detected (expected: $amount)")
                if (detected != null && detected != amount) {
                    WebSocketManager.sendError(requestId, "Nominal tidak sesuai: OCR=$detected, expected=$amount")
                    return
                }
            }

            // Step 7: Cari dan tap tombol "Terapkan"
            WebSocketManager.sendStatus(requestId, "Mencari tombol Terapkan...")
            val terapkanPos = waitForTemplate(tplTerapkan, "tpl_terapkan", requestId, timeoutMs = 10_000)
                ?: return

            WebSocketManager.sendStatus(requestId, "Menekan Terapkan")
            performTap(terapkanPos.centerX.toFloat(), terapkanPos.centerY.toFloat())
            delay(2000)

            // Step 8: Tunggu QR aktif muncul, OCR timer, capture QR
            WebSocketManager.sendStatus(requestId, "Menunggu QR aktif...")
            val qrResult = waitForQrActive(requestId, amount, timeoutMs = 20_000)
                ?: return

            // Step 9: Kirim hasil ke server
            Log.i(TAG, "✅ Mengirim hasil: timer=${qrResult.first} qrSize=${qrResult.second.length}")
            WebSocketManager.sendQrisResult(
                requestId = requestId,
                amount = amount,
                timer = qrResult.first,
                qrBase64 = qrResult.second,
            )

        } catch (e: CancellationException) {
            Log.w(TAG, "Flow cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Flow error: ${e.message}", e)
            WebSocketManager.sendError(requestId, "Error: ${e.message}")
        } finally {
            isRunning = false
        }
    }

    // ── Step Helpers ──────────────────────────────────────────────────────────

    /** Retry screenshot + template matching sampai ketemu atau timeout. */
    private suspend fun waitForTemplate(
        template: Bitmap?,
        name: String,
        requestId: String,
        timeoutMs: Long,
        intervalMs: Long = 800,
        threshold: Float = 0.72f,
    ): ImageMatcher.MatchResult? {
        if (template == null) {
            WebSocketManager.sendError(requestId, "Template '$name' tidak ada di assets")
            return null
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val screen = captureCurrentScreen()
            if (screen != null) {
                val result = withContext(Dispatchers.Default) {
                    ImageMatcher.findTemplate(screen, template, threshold)
                }
                screen.recycle()
                if (result.found) {
                    Log.i(TAG, "✔ Template '$name' found at (${result.centerX}, ${result.centerY}) conf=${result.confidence}")
                    return result
                }
            }
            delay(intervalMs)
        }

        WebSocketManager.sendError(requestId, "Timeout: '$name' tidak ditemukan setelah ${timeoutMs}ms")
        return null
    }

    /** Input angka ke keypad GoPay digit per digit. */
    private suspend fun inputAmount(amount: Int, requestId: String) {
        val digits = amount.toString()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // Layout keypad GoPay (estimasi dari screenshot referensi)
        // Row 1: 1 2 3   ~65% dari atas
        // Row 2: 4 5 6   ~73%
        // Row 3: 7 8 9   ~81%
        // Row 4: 0 000 ⌫ ~89%
        val keyMap = buildKeyMap(screenW, screenH)

        for (digit in digits) {
            val pos = keyMap[digit]
            if (pos != null) {
                Log.d(TAG, "Tap digit '$digit' at (${pos.x}, ${pos.y})")
                performTap(pos.x.toFloat(), pos.y.toFloat())
                delay(150)  // Delay antar digit
            } else {
                Log.w(TAG, "Digit '$digit' tidak ada di keymap")
            }
        }
    }

    /** Buat map koordinat keypad dari ukuran layar. */
    private fun buildKeyMap(w: Int, h: Int): Map<Char, android.graphics.Point> {
        val col1 = (w * 0.17f).toInt()
        val col2 = (w * 0.50f).toInt()
        val col3 = (w * 0.83f).toInt()
        val row1 = (h * 0.65f).toInt()
        val row2 = (h * 0.73f).toInt()
        val row3 = (h * 0.81f).toInt()
        val row4 = (h * 0.89f).toInt()

        return mapOf(
            '1' to android.graphics.Point(col1, row1),
            '2' to android.graphics.Point(col2, row1),
            '3' to android.graphics.Point(col3, row1),
            '4' to android.graphics.Point(col1, row2),
            '5' to android.graphics.Point(col2, row2),
            '6' to android.graphics.Point(col3, row2),
            '7' to android.graphics.Point(col1, row3),
            '8' to android.graphics.Point(col2, row3),
            '9' to android.graphics.Point(col3, row3),
            '0' to android.graphics.Point(col1, row4),
        )
    }

    /**
     * Tunggu halaman QR aktif, OCR timer, capture QR image.
     * Return Pair(timerText, qrBase64) atau null jika timeout.
     */
    private suspend fun waitForQrActive(
        requestId: String,
        amount: Int,
        timeoutMs: Long,
    ): Pair<String, String>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val screen = captureCurrentScreen() ?: run { delay(1000); continue }

            // OCR bagian bawah untuk cari timer
            val timerRegion = OcrUtil.cropRegion(screen, 0f, 0.75f, 1f, 0.15f)
            val ocrText = OcrUtil.recognizeText(timerRegion)
            val timer = OcrUtil.extractTimer(ocrText)

            if (timer != null) {
                Log.i(TAG, "Timer ditemukan: $timer")

                // Delay 20ms sesuai permintaan, lalu capture QR
                delay(20)
                val freshScreen = captureCurrentScreen() ?: screen

                // Crop area QR dan encode
                val qrRegion = QrDecoder.cropQrRegion(freshScreen)
                val qrBase64 = QrDecoder.bitmapToBase64(qrRegion)

                // Decode QR untuk verifikasi (opsional)
                val qrText = QrDecoder.decode(qrRegion)
                Log.d(TAG, "QR content: ${qrText?.take(60)}")

                screen.recycle()
                if (freshScreen != screen) freshScreen.recycle()
                qrRegion.recycle()

                return Pair(timer, qrBase64)
            }

            screen.recycle()
            delay(800)
        }

        WebSocketManager.sendError(requestId, "Timeout: halaman QR aktif tidak muncul")
        return null
    }

    // ── Gesture Helpers ───────────────────────────────────────────────────────

    /** Tap di koordinat layar menggunakan AccessibilityService gesture. */
    private fun performTap(x: Float, y: Float, durationMs: Long = 100) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** Capture screenshot via ScreenCaptureService. */
    private suspend fun captureCurrentScreen(): Bitmap? {
        return withContext(Dispatchers.IO) {
            ScreenCaptureService.getInstance()?.captureScreen()
        }
    }
}
