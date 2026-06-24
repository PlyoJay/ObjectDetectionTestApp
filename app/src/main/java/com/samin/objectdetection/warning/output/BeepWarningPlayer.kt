package com.samin.objectdetection.warning.output

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.WarningCandidate

class BeepWarningPlayer : WarningPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        Log.w(TAG, "ToneGenerator init failed", e)
        null
    }
    private var isPlaying = false

    override fun playIfNeeded(candidate: WarningCandidate) {
        play(candidate.feedback.beepLevel)
    }

    fun play(beepLevel: FeedbackLevel): Boolean {
        if (beepLevel == FeedbackLevel.NONE || beepLevel == FeedbackLevel.LOW) return false

        val toneGenerator = toneGenerator
        if (toneGenerator == null) {
            Log.w(TAG, "skip beep: ToneGenerator is null")
            return false
        }
        if (isPlaying) return false

        val delays = when (beepLevel) {
            FeedbackLevel.MEDIUM -> longArrayOf(0L, MEDIUM_INTERVAL_MS)
            FeedbackLevel.HIGH -> longArrayOf(0L, HIGH_INTERVAL_MS, HIGH_INTERVAL_MS * 2)
            FeedbackLevel.LOW,
            FeedbackLevel.NONE -> return false
        }

        isPlaying = true
        delays.forEach { delayMs ->
            handler.postDelayed({
                try {
                    val durationMs = if (beepLevel == FeedbackLevel.HIGH) HIGH_BEEP_DURATION_MS else MEDIUM_BEEP_DURATION_MS
                    Log.d(TAG, "play beep: level=$beepLevel, delay=${delayMs}ms, duration=${durationMs}ms")
                    toneGenerator.startTone(TONE_TYPE, durationMs)
                } catch (e: Exception) {
                    Log.w(TAG, "beep failed", e)
                }
            }, delayMs)
        }
        handler.postDelayed({ isPlaying = false }, delays.last() + BEEP_GUARD_RELEASE_DELAY_MS)
        return true
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
    }

    companion object {
        private const val TAG = "GotoroBeep"
        private const val TONE_VOLUME = 100
        private const val TONE_TYPE = ToneGenerator.TONE_PROP_BEEP
        private const val MEDIUM_BEEP_DURATION_MS = 120
        private const val HIGH_BEEP_DURATION_MS = 100
        private const val MEDIUM_INTERVAL_MS = 150L
        private const val HIGH_INTERVAL_MS = 120L
        private const val BEEP_GUARD_RELEASE_DELAY_MS = 180L
    }
}
