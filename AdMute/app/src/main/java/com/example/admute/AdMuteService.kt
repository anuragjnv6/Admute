package com.example.admute

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdMuteService : AccessibilityService() {

    // YouTube, plus AdMute's own fake-ad test screen.
    private val watched = setOf("com.google.android.youtube", "com.example.admute")
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var weMuted = false          // true only if WE muted, so we never unmute a phone the user muted
    private var savedVolume = 0          // media volume level before we muted
    private var unmuteScheduled = false

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

    override fun onServiceConnected() {
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg == null || pkg !in watched) return
        checkScreen()
    }

    private fun checkScreen() {
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        if (root == null || rootPkg == null || rootPkg !in watched) {
            // YouTube is not on screen (left the app or locked the phone): don't stay muted.
            if (weMuted) scheduleUnmute()
            return
        }
        if (containsAd(root, 0)) {
            cancelUnmute()                 // ad still showing
            if (!weMuted) mute()
        } else if (weMuted) {
            scheduleUnmute()               // no ad seen: unmute after a short delay
        }
    }

    // Starts the 0.7s countdown only once. Later checks must NOT restart it,
    // otherwise the frequent polling would keep resetting it and never unmute.
    private fun scheduleUnmute() {
        if (unmuteScheduled) return
        unmuteScheduled = true
        handler.postDelayed(unmuteRunnable, 700)
    }

    private fun cancelUnmute() {
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
        return adPatterns.any { it.matches(s.trim()) }
    }

    private fun mute() {
        try {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == 0) return                   // already silent, leave it alone
            savedVolume = current                      // remember the exact level
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            weMuted = true
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, 300)
        } catch (e: Exception) {
        }
    }

    private fun unmute() {
        if (!weMuted) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            // Some phones come back at the wrong level after unmute, so set the saved level explicitly.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        } catch (e: Exception) {
        }
        weMuted = false
        cancelUnmute()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onInterrupt() {
        unmute()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unmute()
        super.onDestroy()
    }
}
