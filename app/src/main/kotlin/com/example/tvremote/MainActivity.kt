package com.example.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        val title = TextView(this).apply {
            text = "📺 TV Remote Agent"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }

        statusText = TextView(this).apply {
            text = "Starting service..."
            textSize = 16f
            setTextColor(Color.parseColor("#00ff88"))
            setPadding(0, 0, 0, 20)
        }

        val info = TextView(this).apply {
            text = "⚠️ Required: Enable Accessibility Service\n\nGo to:\nSettings → Accessibility → TV Remote Agent → ON\n\nThen check the notification bar — it should show 'Connected ✓'"
            textSize = 14f
            setTextColor(Color.parseColor("#aaaaaa"))
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(info)
        setContentView(layout)

        // Start service
        startTvService(this)

        // Update status every 2 seconds
        handler.postDelayed(object : Runnable {
            override fun run() {
                val accRunning = TvAccessibilityService.instance != null
                statusText.text = if (accRunning) {
                    "✅ Accessibility: ON\n🔄 Connecting to server..."
                } else {
                    "❌ Accessibility: OFF\nPlease enable in Settings!"
                }
                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }
}
