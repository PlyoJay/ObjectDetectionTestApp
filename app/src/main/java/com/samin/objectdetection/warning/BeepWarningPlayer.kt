package com.samin.objectdetection.warning

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

class BeepWarningPlayer(
    private val cooldownMs: Long = 800L
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
        if (decision.beepLevel == FeedbackLevel.NONE) {
            Log.d(TAG, "skip beep: level=NONE")
            return
        }
        val toneGenerator = toneGenerator
        if (toneGenerator == null) {
            Log.w(TAG, "skip beep: ToneGenerator is null")
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMs = now - lastPlayedAtMs
        if (elapsedMs < cooldownMs) {
            Log.d(TAG, "skip beep: cooldown remaining=${cooldownMs - elapsedMs}ms")
            return
        }

        val delays = when (decision.beepLevel) {
            FeedbackLevel.LOW -> longArrayOf(0L)
            FeedbackLevel.MEDIUM -> longArrayOf(0L, MEDIUM_INTERVAL_MS)
            FeedbackLevel.HIGH -> longArrayOf(0L, HIGH_INTERVAL_MS, HIGH_INTERVAL_MS * 2)
            FeedbackLevel.NONE -> return
        }

        delays.forEach { delayMs ->
            handler.postDelayed({
                try {
                    Log.d(
                        TAG,
                        "play beep: level=${decision.beepLevel}, delay=${delayMs}ms, duration=${BEEP_DURATION_MS}ms"
                    )
                    toneGenerator.startTone(TONE_TYPE, BEEP_DURATION_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "beep failed", e)
                }
            }, delayMs)
        }
        lastPlayedAtMs = now
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
    }

    companion object {
        private const val TAG = "BeepWarningPlayer"
        private const val TONE_VOLUME = 100
        private const val TONE_TYPE = ToneGenerator.TONE_PROP_BEEP
        private const val BEEP_DURATION_MS = 120
        private const val MEDIUM_INTERVAL_MS = 240L
        private const val HIGH_INTERVAL_MS = 200L
    }
}
