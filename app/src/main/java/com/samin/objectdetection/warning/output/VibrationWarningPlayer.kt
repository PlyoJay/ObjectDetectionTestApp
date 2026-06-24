package com.samin.objectdetection.warning.output

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.WarningCandidate

class VibrationWarningPlayer(
    context: Context
) : WarningPlayer {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun playIfNeeded(candidate: WarningCandidate) {
        play(candidate.feedback.vibrationLevel)
    }

    fun play(vibrationLevel: FeedbackLevel): Boolean {
        if (vibrationLevel == FeedbackLevel.NONE) return false

        val currentVibrator = vibrator ?: return false
        if (!currentVibrator.hasVibrator()) return false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateOreoAndAbove(currentVibrator, vibrationLevel)
            } else {
                vibrateLegacy(currentVibrator, vibrationLevel)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed", e)
            return false
        }
    }

    private fun vibrateOreoAndAbove(vibrator: Vibrator, vibrationLevel: FeedbackLevel) {
        val effect = when (vibrationLevel) {
            FeedbackLevel.LOW -> VibrationEffect.createOneShot(
                LOW_DURATION_MS,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            FeedbackLevel.MEDIUM -> VibrationEffect.createWaveform(MEDIUM_PATTERN, NO_REPEAT)
            FeedbackLevel.HIGH -> VibrationEffect.createWaveform(HIGH_PATTERN, NO_REPEAT)
            FeedbackLevel.NONE -> return
        }
        vibrator.vibrate(effect)
    }

    @Suppress("DEPRECATION")
    private fun vibrateLegacy(vibrator: Vibrator, vibrationLevel: FeedbackLevel) {
        when (vibrationLevel) {
            FeedbackLevel.LOW -> vibrator.vibrate(LOW_DURATION_MS)
            FeedbackLevel.MEDIUM -> vibrator.vibrate(MEDIUM_PATTERN, NO_REPEAT)
            FeedbackLevel.HIGH -> vibrator.vibrate(HIGH_PATTERN, NO_REPEAT)
            FeedbackLevel.NONE -> return
        }
    }

    companion object {
        private const val TAG = "GotoroVibration"
        private const val NO_REPEAT = -1
        private const val LOW_DURATION_MS = 120L
        private val MEDIUM_PATTERN = longArrayOf(0, 180, 200, 180)
        private val HIGH_PATTERN = longArrayOf(0, 250, 200, 250, 200, 250)
    }
}
