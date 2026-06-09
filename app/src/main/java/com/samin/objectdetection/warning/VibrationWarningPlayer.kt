package com.samin.objectdetection.warning

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

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
