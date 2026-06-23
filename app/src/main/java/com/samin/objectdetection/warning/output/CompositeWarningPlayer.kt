package com.samin.objectdetection.warning.output

import android.util.Log
import com.samin.objectdetection.warning.WarningCandidate

interface WarningPlayer {
    fun playIfNeeded(candidate: WarningCandidate)

    fun release() = Unit
}

class CompositeWarningPlayer(
    private val players: List<WarningPlayer>
) : WarningPlayer {

    override fun playIfNeeded(candidate: WarningCandidate) {
        players.forEach { player ->
            try {
                player.playIfNeeded(candidate)
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
