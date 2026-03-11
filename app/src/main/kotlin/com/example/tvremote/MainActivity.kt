package com.example.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "TV Remote Agent is running in background.\n\nMake sure to enable Accessibility Service in:\nSettings → Accessibility → TV Remote Agent"
            textSize = 16f
            setPadding(40, 40, 40, 40)
        }
        setContentView(tv)

        // Start the background service
        val svc = Intent(this, TvRemoteService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }
}
