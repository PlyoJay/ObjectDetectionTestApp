package com.samin.objectdetection.warning

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

interface WarningPlayer {
    fun playIfNeeded(decision: WarningDecision)

    fun release() = Unit
}

class CompositeWarningPlayer(
    private val players: List<WarningPlayer>
) : WarningPlayer {

    override fun playIfNeeded(decision: WarningDecision) {
        players.forEach { player ->
            try {
                player.playIfNeeded(decision)
            } catch (e: Exception) {
                Log.w(TAG, "warning player failed: ${player.javaClass.simpleName}", e)
            }
        }
    }

    override fun release() {
        players.forEach { player ->
            try {
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "warning player release failed: ${player.javaClass.simpleName}", e)
            }
        }
    }

    companion object {
        private const val TAG = "CompositeWarningPlayer"
    }
}

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

class VibrationWarningPlayer(
    context: Context,
    private val cooldownMs: Long = 2000L
) : WarningPlayer {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var lastPlayedAtMs = 0L

    override fun playIfNeeded(decision: WarningDecision) {
        if (decision.vibrationLevel == FeedbackLevel.NONE) return

        val now = System.currentTimeMillis()
        if (now - lastPlayedAtMs < cooldownMs) return

        val (pattern, amplitudes) = when (decision.vibrationLevel) {
            FeedbackLevel.LOW -> LOW_PATTERN to LOW_AMPLITUDES
            FeedbackLevel.MEDIUM -> MEDIUM_PATTERN to MEDIUM_AMPLITUDES
            FeedbackLevel.HIGH -> HIGH_PATTERN to HIGH_AMPLITUDES
            FeedbackLevel.NONE -> return
        }

        val currentVibrator = vibrator ?: return
        try {
            if (!currentVibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentVibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, amplitudes, NO_REPEAT)
                )
            } else {
                @Suppress("DEPRECATION")
                currentVibrator.vibrate(pattern, NO_REPEAT)
            }
            lastPlayedAtMs = now
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    companion object {
        private const val TAG = "VibrationWarningPlayer"
        private const val NO_REPEAT = -1
        private val LOW_PATTERN = longArrayOf(0, 120)
        private val MEDIUM_PATTERN = longArrayOf(0, 180, 100, 180)
        private val HIGH_PATTERN = longArrayOf(0, 230, 80, 230, 80, 230)
        private val LOW_AMPLITUDES = intArrayOf(0, 90)
        private val MEDIUM_AMPLITUDES = intArrayOf(0, 170, 0, 170)
        private val HIGH_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
    }
}
