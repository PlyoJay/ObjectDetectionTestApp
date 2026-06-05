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
        if (!decision.shouldVibrate) return
        val obstacle = decision.obstacle ?: return

        val now = System.currentTimeMillis()
        if (now - lastPlayedAtMs < cooldownMs) return

        val pattern = when (obstacle.proximityLevel) {
            ProximityLevel.VERY_NEAR -> VERY_NEAR_PATTERN
            ProximityLevel.NEAR -> NEAR_PATTERN
            ProximityLevel.MID,
            ProximityLevel.FAR -> return
        }

        val currentVibrator = vibrator ?: return
        try {
            if (!currentVibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentVibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, NO_REPEAT)
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
        private val VERY_NEAR_PATTERN = longArrayOf(0, 250, 100, 250)
        private val NEAR_PATTERN = longArrayOf(0, 180)
    }
}
