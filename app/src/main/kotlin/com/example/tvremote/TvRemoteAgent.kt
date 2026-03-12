package com.example.tvremote

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "TvRemoteAgent"
private const val SERVER_URL = "wss://tv-remote-bot-production.up.railway.app/ws/tv/living-room"
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

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started, connecting...")
        connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        webSocket?.cancel()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun connect() {
        Log.i(TAG, "Connecting to $SERVER_URL")
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened!")
                ws.send(JSONObject().put("auth", CLIENT_SECRET).toString())
                updateNotification("Connected ✓")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Message: $text")
                try {
                    val json = JSONObject(text)
                    if (json.has("command")) {
                        val cmd = json.getString("command")
                        val value = if (json.has("value") && !json.isNull("value")) json.getInt("value") else null
                        executeCommand(cmd, value)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                updateNotification("Disconnected - retrying...")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code")
                updateNotification("Disconnected - retrying...")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable { connect() }
        handler.postDelayed(reconnectRunnable!!, 5000)
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
            val ch = NotificationChannel(NOTIF_CHANNEL, "TV Remote", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}

fun executeCommand(command: String, value: Int?) {
    val acc = TvAccessibilityService.instance ?: run {
        Log.e(TAG, "Accessibility not running!")
        return
    }
    when (command) {
        "change_channel" -> value?.toString()?.forEach { digit ->
            acc.injectKey(digitToKeyCode(digit))
            Thread.sleep(400)
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

private fun digitToKeyCode(digit: Char) = when (digit) {
    '0' -> KeyEvent.KEYCODE_0; '1' -> KeyEvent.KEYCODE_1
    '2' -> KeyEvent.KEYCODE_2; '3' -> KeyEvent.KEYCODE_3
    '4' -> KeyEvent.KEYCODE_4; '5' -> KeyEvent.KEYCODE_5
    '6' -> KeyEvent.KEYCODE_6; '7' -> KeyEvent.KEYCODE_7
    '8' -> KeyEvent.KEYCODE_8; '9' -> KeyEvent.KEYCODE_9
    else -> KeyEvent.KEYCODE_UNKNOWN
}

class TvAccessibilityService : AccessibilityService() {
    companion object { var instance: TvAccessibilityService? = null }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility connected ✓")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun injectKey(keyCode: Int) {
        try {
            val im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
            val now = SystemClock.uptimeMillis()
            val inject = im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            inject.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0), 0)
            Thread.sleep(50)
            inject.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0), 0)
        } catch (e: Exception) {
            Log.e(TAG, "injectKey failed: ${e.message}")
        }
    }
}
