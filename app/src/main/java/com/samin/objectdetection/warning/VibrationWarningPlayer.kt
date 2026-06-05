package com.samin.objectdetection.warning

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationWarningPlayer(
    context: Context,
    private val minIntervalMs: Long = 1200L
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

        val now = System.currentTimeMillis()
        if (now - lastPlayedAtMs < minIntervalMs) return

        val currentVibrator = vibrator ?: return
        if (!currentVibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentVibrator.vibrate(
                VibrationEffect.createOneShot(
                    VIBRATION_DURATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            currentVibrator.vibrate(VIBRATION_DURATION_MS)
        }
        lastPlayedAtMs = now
    }

    companion object {
        private const val VIBRATION_DURATION_MS = 250L
    }
}
