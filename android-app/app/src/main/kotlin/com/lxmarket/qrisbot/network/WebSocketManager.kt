package com.lxmarket.qrisbot.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "WebSocketManager"

/**
 * Singleton WebSocket manager.
 * Maintains persistent connection ke Python server.
 */
object WebSocketManager {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // Disable read timeout untuk WebSocket
        .pingInterval(20, TimeUnit.SECONDS)       // Keep-alive ping
        .build()

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""

    /** Callback dipanggil saat server kirim command */
    var onCommandReceived: ((JsonObject) -> Unit)? = null

    /** Callback status koneksi */
    var onConnectionStatus: ((Boolean, String) -> Unit)? = null

    // ── Connect ──────────────────────────────────────────────────────────────
    fun connect(url: String) {
        serverUrl = url
        if (webSocket != null) disconnect()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
        Log.i(TAG, "Connecting to $url")
    }

    fun disconnect() {
        webSocket?.close(1000, "Manual disconnect")
        webSocket = null
    }

    fun isConnected(): Boolean = webSocket != null

    // ── Send ─────────────────────────────────────────────────────────────────
    fun sendMessage(data: Map<String, Any>): Boolean {
        val json = gson.toJson(data)
        return webSocket?.send(json) ?: false
    }

    fun sendJson(json: String): Boolean = webSocket?.send(json) ?: false

    /** Send status update ke server */
    fun sendStatus(requestId: String, message: String) {
        sendMessage(mapOf(
            "type" to "status",
            "request_id" to requestId,
            "message" to message,
        ))
    }

    /** Send error ke server */
    fun sendError(requestId: String, message: String) {
        sendMessage(mapOf(
            "type" to "error",
            "request_id" to requestId,
            "message" to message,
        ))
    }

    /** Send QRIS result ke server */
    fun sendQrisResult(
        requestId: String,
        amount: Int,
        timer: String,
        qrBase64: String,
    ) {
        sendMessage(mapOf(
            "type" to "qris_result",
            "request_id" to requestId,
            "amount" to amount,
            "timer" to timer,
            "qr_image_base64" to qrBase64,
        ))
    }

    // ── WebSocket Listener ────────────────────────────────────────────────────
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")
            onConnectionStatus?.invoke(true, "Terhubung ke server")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Message received: $text")
            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                val type = json.get("type")?.asString

                if (type == "heartbeat") {
                    // Auto reply heartbeat
                    sendMessage(mapOf("type" to "heartbeat"))
                    return
                }
                onCommandReceived?.invoke(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed")
            onConnectionStatus?.invoke(false, "Koneksi terputus")
            this@WebSocketManager.webSocket = null
            // Auto reconnect setelah 3 detik
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error: ${t.message}")
            onConnectionStatus?.invoke(false, "Error: ${t.message}")
            this@WebSocketManager.webSocket = null
            scheduleReconnect()
        }
    }

    // ── Auto Reconnect ────────────────────────────────────────────────────────
    private var reconnectJob: Thread? = null

    private fun scheduleReconnect() {
        reconnectJob?.interrupt()
        reconnectJob = Thread {
            try {
                Thread.sleep(3000)
                if (serverUrl.isNotBlank()) {
                    Log.i(TAG, "Reconnecting...")
                    connect(serverUrl)
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }
}
