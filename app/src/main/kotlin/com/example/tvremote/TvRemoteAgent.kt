package com.example.tvremote

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG           = "TvRemoteAgent"
private const val SERVER_URL    = "wss://tv-remote-bot-production.up.railway.app/ws/tv/living-room"
private const val CLIENT_SECRET = "NAGARAJKALPANASHRAVANISANDEEP"
private const val NOTIF_CHANNEL = "tv_remote"
private const val NOTIF_ID      = 1001
private const val PING_INTERVAL = 30_000L

// ─── Boot Receiver ─────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val svc = Intent(context, TvRemoteService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}

// ─── Foreground Service ─────────────────────────────────────────────────────────

class TvRemoteService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        ws?.cancel()
        super.onDestroy()
    }

    private fun connect() {
        reconnectJob?.cancel()
        val request = Request.Builder().url(SERVER_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened, authenticating…")
                webSocket.send(JSONObject().put("auth", CLIENT_SECRET).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                updateNotification("Disconnected — reconnecting…")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code $reason")
                updateNotification("Disconnected — reconnecting…")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when {
                json.has("status") -> {
                    if (json.getString("status") == "authenticated") {
                        Log.i(TAG, "Authenticated ✓")
                        updateNotification("Connected ✓")
                    }
                }
                json.has("type") && json.getString("type") == "pong" -> { }
                json.has("command") -> {
                    val cmd = json.getString("command")
                    val value = if (json.has("value")) json.getInt("value") else null
                    Log.i(TAG, "Executing command: $cmd value=$value")
                    executeCommand(cmd, value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob = scope.launch {
            delay(5_000)
            connect()
        }
    }

    private fun buildNotification(status: String): Notification {
        createNotificationChannel()
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("TV Remote Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
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

// ─── Command Executor ──────────────────────────────────────────────────────────

fun executeCommand(command: String, value: Int?) {
    val acc = TvAccessibilityService.instance ?: run {
        Log.e(TAG, "Accessibility service not running!")
        return
    }
    when (command) {
        "change_channel" -> {
            value?.toString()?.forEach { digit ->
                val keyCode = digitToKeyCode(digit)
                acc.injectKey(keyCode)
                Thread.sleep(300)
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
    '0' -> KeyEvent.KEYCODE_0
    '1' -> KeyEvent.KEYCODE_1
    '2' -> KeyEvent.KEYCODE_2
    '3' -> KeyEvent.KEYCODE_3
    '4' -> KeyEvent.KEYCODE_4
    '5' -> KeyEvent.KEYCODE_5
    '6' -> KeyEvent.KEYCODE_6
    '7' -> KeyEvent.KEYCODE_7
    '8' -> KeyEvent.KEYCODE_8
    '9' -> KeyEvent.KEYCODE_9
    else -> KeyEvent.KEYCODE_UNKNOWN
}

// ─── Accessibility Service ─────────────────────────────────────────────────────

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
            val clazz  = Class.forName("android.hardware.input.InputManager")
            val method = clazz.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val im = clazz.getMethod("getInstance").invoke(null)
            method.invoke(im, KeyEvent(KeyEvent.ACTION_DOWN, keyCode), 0)
            method.invoke(im, KeyEvent(KeyEvent.ACTION_UP,   keyCode), 0)
            Log.d(TAG, "Injected keyCode=$keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "injectKey failed: ${e.message}")
            // Fallback for volume/media keys
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
            sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, down)
            })
            sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, up)
            })
        }
    }
}
