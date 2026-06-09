package com.samin.objectdetection.warning

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

class BeepWarningPlayer(
    private val cooldownMs: Long = 1200L
) : WarningPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        Log.w(TAG, "ToneGenerator init failed", e)
        null
    }
    private var lastPlayedAtMs = 0L

    override fun playIfNeeded(decision: WarningDecision) {
        if (decision.beepLevel == FeedbackLevel.NONE) return
        val toneGenerator = toneGenerator ?: return

        val now = System.currentTimeMillis()
        if (now - lastPlayedAtMs < cooldownMs) return

        val delays = when (decision.beepLevel) {
            FeedbackLevel.LOW -> longArrayOf(0L)
            FeedbackLevel.MEDIUM -> longArrayOf(0L, 180L)
            FeedbackLevel.HIGH -> longArrayOf(0L, 120L, 240L)
            FeedbackLevel.NONE -> return
        }

        delays.forEach { delayMs ->
            handler.postDelayed({
                try {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "beep failed", e)
                }
            }, delayMs)
        }
        lastPlayedAtMs = now
    }

    override fun close() {
        toneGenerator?.release()
    }

    companion object {
        private const val TAG = "BeepWarningPlayer"
        private const val TONE_VOLUME = 80
        private const val BEEP_DURATION_MS = 100
    }
}
