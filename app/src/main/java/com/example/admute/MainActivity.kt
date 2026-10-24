package com.example.admute

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        statusText = TextView(this).apply {
            textSize = 22f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)
        layout.addView(TextView(this).apply {
            text = "AdMute mutes your volume while YouTube ads play.\n\n" +
                "Tap the first button, find \"AdMute\" in the list, and turn it on."
            textSize = 16f
        })
        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "Test with a fake ad"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AdTestActivity::class.java))
            }
        })
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val on = enabled.contains(packageName + "/")
        statusText.text = if (on) "Service is ON" else "Service is OFF"
        statusText.setTextColor(Color.parseColor(if (on) "#2E7D32" else "#C62828"))
    }
}
