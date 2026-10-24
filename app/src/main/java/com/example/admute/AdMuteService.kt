package com.example.admute

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdMuteService : AccessibilityService() {

    // Live status shown on the fake-ad test screen, to help find problems.
    companion object {
        @Volatile var connected = false
        @Volatile var lastEventTime = 0L
        @Volatile var lastEventPkg = "none"
        @Volatile var lastRootPkg = "none"
        @Volatile var lastSawAd = false
        @Volatile var isMuting = false
        @Volatile var lastMuteInfo = "not run yet"
        val logLines = ArrayList<String>()   // recent events, shown on the test screen
    }

    // YouTube, plus AdMute's own fake-ad test screen.
    private val watched = setOf("com.google.android.youtube", "com.example.admute")
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var weMuted = false          // true only if WE muted, so we never unmute a phone the user muted
    private var savedVolume = 0          // media volume level before we muted
    private var unmuteScheduled = false
    private var lastLabel = ""           // the ad label we last matched, for the log
    private var moreAds = false          // true after "Ad 1 of 2": another ad is coming, so unmute later
    private val podRegex = Regex("""(?i)^ad\s+(\d+)\s+of\s+(\d+).*""")
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    private fun note(msg: String) {
        logLines.add(timeFormat.format(java.util.Date()) + " " + msg)
        while (logLines.size > 40) logLines.removeAt(0)
    }

    // Whole-label patterns for text that appears only while a YouTube ad is on screen.
    // They must match the WHOLE label, so a video titled "How to skip ads" will not trigger a mute.
    // Add more languages here. YouTube changes these, so test and adjust.
    private val adPatterns = listOf(
        Regex("""(?i)^ad(\s*[·•].*|\s+\d.*)?$"""),   // "Ad", "Ad · 0:15", "Ad 1 of 2", "Ad 0:15"
        Regex("""(?i)^skip ads?(\s+in\s+\d+)?$"""),  // "Skip ad", "Skip ads", "Skip ad in 5"
        Regex("""(?i)^visit advertiser$""")
    )

    private val unmuteRunnable = Runnable {
        unmuteScheduled = false
        unmute()
    }

    // While muted, re-check every 0.3s so we catch the end of the ad quickly.
    private val pollRunnable = object : Runnable {
        override fun run() {
            checkScreen()
            if (weMuted) handler.postDelayed(this, 300)
        }
    }

    // Checks the screen twice a second while YouTube is open, even if Android sends no screen-change
    // event. Some ads show their "Ad" label without any event, so waiting for events can be late.
    private var idlePolling = false
    private var misses = 0               // checks in a row where YouTube was not on screen
    private val idlePollRunnable = object : Runnable {
        override fun run() {
            checkScreen()
            if (misses < 6) {
                handler.postDelayed(this, 500)
            } else {
                idlePolling = false
            }
        }
    }

    private fun startIdlePoll() {
        if (idlePolling) return
        idlePolling = true
        misses = 0
        handler.postDelayed(idlePollRunnable, 500)
    }

    override fun onServiceConnected() {
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        lastEventTime = SystemClock.elapsedRealtime()
        lastEventPkg = pkg ?: "none"
        if (pkg == null || pkg !in watched) return
        startIdlePoll()
        checkScreen()
    }

    private fun checkScreen() {
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        lastRootPkg = rootPkg ?: "none"
        if (root == null || rootPkg == null || rootPkg !in watched) {
            misses++
            // YouTube is not on screen (left the app or locked the phone): don't stay muted.
            if (weMuted) scheduleUnmute("screen is " + (rootPkg ?: "unreadable"), 2000)
            return
        }
        misses = 0
        val sawAd = containsAd(root, 0)
        lastSawAd = sawAd
        if (sawAd) {
            cancelUnmute()                 // ad still showing
            if (!weMuted) mute()
        } else if (weMuted) {
            // No ad seen. If the last label said "Ad 1 of 2", wait longer for the next ad to start.
            scheduleUnmute("no ad label on screen", if (moreAds) 4000 else 700)
        }
    }

    // Starts the countdown only once. Later checks must NOT restart it,
    // otherwise the frequent polling would keep resetting it and never unmute.
    private fun scheduleUnmute(reason: String, delayMs: Long) {
        if (unmuteScheduled) return
        unmuteScheduled = true
        note("unmute countdown (" + delayMs + "ms): " + reason)
        handler.postDelayed(unmuteRunnable, delayMs)
    }

    private fun cancelUnmute() {
        if (unmuteScheduled) note("ad label is back, countdown cancelled")
        unmuteScheduled = false
        handler.removeCallbacks(unmuteRunnable)
    }

    private fun containsAd(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25) return false
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (matches(text) || matches(desc)) return true
        for (i in 0 until node.childCount) {
            if (containsAd(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    private fun matches(s: String?): Boolean {
        if (s.isNullOrBlank() || s.length > 60) return false   // ad labels are short
        val label = s.trim()
        val hit = adPatterns.any { it.matches(label) }
        if (hit) {
            lastLabel = label
            val m = podRegex.matchEntire(label)
            if (m != null) {
                val k = m.groupValues[1].toIntOrNull() ?: 0
                val n = m.groupValues[2].toIntOrNull() ?: 0
                moreAds = k < n
            }
        }
        return hit
    }

    private fun vol(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun isSilent(): Boolean =
        audio.isStreamMute(AudioManager.STREAM_MUSIC) || vol() == 0

    // Tries the mute command first, then setting volume 0, then pressing "volume down" step by step.
    // Some phones ignore some of these, so each step is checked and recorded for the test screen.
    private fun mute() {
        val sb = StringBuilder()
        try {
            val current = vol()
            sb.append("before=").append(current)
            if (current == 0) {
                lastMuteInfo = sb.toString() + " (already silent, skipped)"
                return
            }
            savedVolume = current                      // remember the exact level
            note("MUTE (saw '" + lastLabel + "')")
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            sb.append(" | mute cmd: vol=").append(vol())
                .append(" muted=").append(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            if (!isSilent()) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                sb.append(" | set 0: vol=").append(vol())
            }
            if (!isSilent()) {
                val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                for (i in 0 until steps) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                }
                sb.append(" | lower steps: vol=").append(vol())
            }
            // Set even if every step failed, so these commands are not repeated on every check.
            weMuted = true
            isMuting = isSilent()
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, 300)
        } catch (e: Exception) {
            sb.append(" | ERROR ").append(e.javaClass.simpleName)
        }
        lastMuteInfo = sb.toString()
    }

    private fun unmute() {
        if (!weMuted) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            // If the phone ignored that, step the volume back up like pressing the volume button.
            var guard = 0
            while (vol() < savedVolume && guard < 30) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                guard++
            }
        } catch (e: Exception) {
        }
        note("UNMUTE")
        weMuted = false
        isMuting = false
        moreAds = false
        cancelUnmute()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onInterrupt() {
        unmute()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unmute()
        connected = false
        super.onDestroy()
    }
}
