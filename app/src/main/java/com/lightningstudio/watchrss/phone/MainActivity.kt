package com.lightningstudio.watchrss.phone

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = TextView(this).apply {
            text = "WatchRSS Phone Companion"
            textSize = 22f
            setPadding(48, 72, 48, 72)
        }
        setContentView(view)
    }
}
