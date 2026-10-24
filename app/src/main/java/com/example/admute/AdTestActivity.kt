package com.example.admute

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// A fake "ad" screen so you can test AdMute without waiting for a real YouTube ad.
class AdTestActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var volumeText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var adLabel: TextView
    private lateinit var skipButton: Button
    private lateinit var startButton: Button

    private val endAd = Runnable { hideAd() }

    // Shows the live media volume so you can SEE the mute and the restore.
    private val updateVolume = object : Runnable {
        override fun run() {
            val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || v == 0
            var line = "Media volume: " + v + " / " + max
            if (muted) line += "  (MUTED)"
            volumeText.text = line

            val ago = SystemClock.elapsedRealtime() - AdMuteService.lastEventTime
            val agoText = if (AdMuteService.lastEventTime == 0L) "never" else (ago / 100 / 10.0).toString() + "s ago"
            logText.text = "Recent events (newest first):\n" +
                (if (AdMuteService.logLines.isEmpty()) "(none yet)"
                 else AdMuteService.logLines.takeLast(15).reversed().joinToString("\n"))

            statusText.text = "Service connected: " + AdMuteService.connected +
                "\nLast event: " + agoText + " from " + AdMuteService.lastEventPkg +
                "\nScreen read as: " + AdMuteService.lastRootPkg +
                "\nAd label seen: " + AdMuteService.lastSawAd +
                "\nMuted by AdMute: " + AdMuteService.isMuting +
                "\nMute details: " + AdMuteService.lastMuteInfo
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "Fake ad test. First set your media volume to a medium level. " +
                "Then tap Start fake ad. The volume line below should drop to MUTED while the " +
                "fake ad is showing, and return to your level about 1 second after the ad ends " +
                "(tap Skip ad, or wait 15 seconds)."
            textSize = 15f
        })
        volumeText = TextView(this).apply {
            textSize = 22f
            setPadding(0, 48, 0, 48)
        }
        layout.addView(volumeText)
        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)
        logText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(logText)

        adLabel = TextView(this).apply {
            text = "Ad \u00b7 0:15"
            textSize = 20f
            visibility = View.GONE
        }
        layout.addView(adLabel)

        skipButton = Button(this).apply {
            text = "Skip ad"
            visibility = View.GONE
            setOnClickListener { hideAd() }
        }
        layout.addView(skipButton)

        startButton = Button(this).apply {
            text = "Start fake ad"
            setOnClickListener { showAd() }
        }
        layout.addView(startButton)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun showAd() {
        adLabel.visibility = View.VISIBLE
        skipButton.visibility = View.VISIBLE
        startButton.visibility = View.GONE
        handler.removeCallbacks(endAd)
        handler.postDelayed(endAd, 15000)
    }

    private fun hideAd() {
        handler.removeCallbacks(endAd)
        adLabel.visibility = View.GONE
        skipButton.visibility = View.GONE
        startButton.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateVolume)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }
}
