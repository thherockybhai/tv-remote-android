package com.example.tvremote

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import java.net.URL

private const val TAG = "TvRemoteAgent"
private const val SERVER_WS_URL = "wss://tv-remote-bot-production.up.railway.app/ws/tv/living-room"
private const val SERVER_HTTP_URL = "https://tv-remote-bot-production.up.railway.app/health"
private const val CLIENT_SECRET = "NAGARAJKALPANASHRAVANISANDEEP"
private const val NOTIF_CHANNEL = "tv_remote"
private const val NOTIF_ID = 1001

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            startTvService(context)
        }
    }
}

fun startTvService(context: Context) {
    val svc = Intent(context, TvRemoteService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(svc)
    } else {
        context.startService(svc)
    }
}

class TvRemoteService : Service() {

    private var executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private var wsThread: Thread? = null
    private var running = false
    private var ws: android.net.http.HttpResponseCache? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        running = true
        connectWithRetry()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        running = false
        executor.shutdown()
        super.onDestroy()
    }

    private fun connectWithRetry() {
        executor.execute {
            while (running) {
                try {
                    Log.i(TAG, "Attempting WebSocket connection to $SERVER_WS_URL")
                    updateNotification("Connecting...")
                    connectWebSocket()
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed: ${e.javaClass.simpleName}: ${e.message}")
                    updateNotification("Reconnecting in 5s...")
                }
                if (running) {
                    Log.i(TAG, "Waiting 5s before reconnect...")
                    Thread.sleep(5000)
                }
            }
        }
    }

    private fun connectWebSocket() {
        val uri = URI(SERVER_WS_URL)
        val host = uri.host
        val port = if (uri.port == -1) 443 else uri.port

        val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val socket = factory.createSocket(host, port) as javax.net.ssl.SSLSocket
        socket.soTimeout = 0 // no timeout for reads

        // WebSocket handshake
        val key = android.util.Base64.encodeToString(
            java.security.SecureRandom().generateSeed(16),
            android.util.Base64.NO_WRAP
        )
        val path = uri.path + if (uri.query != null) "?${uri.query}" else ""

        val writer = PrintWriter(socket.outputStream, true)
        val reader = BufferedReader(InputStreamReader(socket.inputStream))

        // Send HTTP upgrade request
        writer.print("GET $path HTTP/1.1\r\n")
        writer.print("Host: $host\r\n")
        writer.print("Upgrade: websocket\r\n")
        writer.print("Connection: Upgrade\r\n")
        writer.print("Sec-WebSocket-Key: $key\r\n")
        writer.print("Sec-WebSocket-Version: 13\r\n")
        writer.print("\r\n")
        writer.flush()

        // Read response
        val statusLine = reader.readLine()
        Log.i(TAG, "WebSocket handshake: $statusLine")

        if (statusLine == null || !statusLine.contains("101")) {
            socket.close()
            throw Exception("WebSocket handshake failed: $statusLine")
        }

        // Skip headers
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            line = reader.readLine()
        }

        Log.i(TAG, "WebSocket connected! Sending auth...")
        updateNotification("Authenticating...")

        // Send auth message
        val authMsg = JSONObject().put("auth", CLIENT_SECRET).toString()
        sendWsFrame(socket.outputStream, authMsg)

        updateNotification("Connected ✓")
        Log.i(TAG, "Authenticated! Waiting for commands...")

        // Read messages
        val inputStream = socket.inputStream
        while (running && !socket.isClosed) {
            try {
                val message = readWsFrame(inputStream) ?: break
                Log.d(TAG, "Received: $message")
                handleMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Read error: ${e.message}")
                break
            }
        }

        socket.close()
        Log.i(TAG, "WebSocket disconnected")
    }

    private fun sendWsFrame(out: java.io.OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = mutableListOf<Byte>()

        // FIN + text opcode
        frame.add(0x81.toByte())

        // Mask bit + payload length
        val len = payload.size
        when {
            len < 126 -> frame.add((0x80 or len).toByte())
            len < 65536 -> {
                frame.add((0x80 or 126).toByte())
                frame.add((len shr 8).toByte())
                frame.add((len and 0xFF).toByte())
            }
        }

        // Masking key
        val mask = ByteArray(4)
        java.security.SecureRandom().nextBytes(mask)
        frame.addAll(mask.toList())

        // Masked payload
        payload.forEachIndexed { i, b ->
            frame.add((b.toInt() xor mask[i % 4].toInt()).toByte())
        }

        out.write(frame.toByteArray())
        out.flush()
    }

    private fun readWsFrame(input: java.io.InputStream): String? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        if (opcode == 8) return null // close frame

        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or input.read().toLong() }
        }

        val payload = ByteArray(len.toInt())
        var offset = 0
        while (offset < len) {
            val read = input.read(payload, offset, (len - offset).toInt())
            if (read == -1) return null
            offset += read
        }

        return String(payload, Charsets.UTF_8)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when {
                json.has("status") -> {
                    Log.i(TAG, "Server status: ${json.getString("status")}")
                }
                json.has("command") -> {
                    val cmd = json.getString("command")
                    val value = if (json.has("value") && !json.isNull("value")) json.getInt("value") else null
                    Log.i(TAG, "Executing: $cmd value=$value")
                    executeCommand(cmd, value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    private fun buildNotification(status: String): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("TV Remote Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "TV Remote", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}

fun executeCommand(command: String, value: Int?) {
    val acc = TvAccessibilityService.instance
    if (acc == null) {
        Log.e(TAG, "Accessibility service not running!")
        return
    }
    when (command) {
        "change_channel" -> {
            value?.toString()?.forEach { digit ->
                acc.injectKey(digitToKeyCode(digit))
                Thread.sleep(400)
            }
        }
        "channel_up"   -> acc.injectKey(KeyEvent.KEYCODE_CHANNEL_UP)
        "channel_down" -> acc.injectKey(KeyEvent.KEYCODE_CHANNEL_DOWN)
        "volume_up"    -> acc.injectKey(KeyEvent.KEYCODE_VOLUME_UP)
        "volume_down"  -> acc.injectKey(KeyEvent.KEYCODE_VOLUME_DOWN)
        "mute"         -> acc.injectKey(KeyEvent.KEYCODE_VOLUME_MUTE)
        "power"        -> acc.injectKey(KeyEvent.KEYCODE_POWER)
        else           -> Log.w(TAG, "Unknown command: $command")
    }
}

private fun digitToKeyCode(digit: Char): Int = when (digit) {
    '0' -> KeyEvent.KEYCODE_0; '1' -> KeyEvent.KEYCODE_1
    '2' -> KeyEvent.KEYCODE_2; '3' -> KeyEvent.KEYCODE_3
    '4' -> KeyEvent.KEYCODE_4; '5' -> KeyEvent.KEYCODE_5
    '6' -> KeyEvent.KEYCODE_6; '7' -> KeyEvent.KEYCODE_7
    '8' -> KeyEvent.KEYCODE_8; '9' -> KeyEvent.KEYCODE_9
    else -> KeyEvent.KEYCODE_UNKNOWN
}

class TvAccessibilityService : AccessibilityService() {
    companion object {
        var instance: TvAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected ✓")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun injectKey(keyCode: Int) {
        try {
            val im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
            val method = im.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val now = SystemClock.uptimeMillis()
            method.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0), 0)
            Thread.sleep(50)
            method.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0), 0)
            Log.d(TAG, "Injected key: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "injectKey failed: ${e.message}")
        }
    }
}
