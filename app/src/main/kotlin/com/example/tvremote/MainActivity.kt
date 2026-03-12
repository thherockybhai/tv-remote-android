package com.example.tvremote

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "TV Remote Agent\n\nService is running in background.\n\nEnable Accessibility:\nSettings → Accessibility → TV Remote Agent → ON"
            textSize = 16f
            setPadding(40, 60, 40, 40)
        }
        setContentView(tv)
        startTvService(this)
    }
}
