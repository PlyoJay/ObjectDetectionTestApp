package com.samin.objectdetection.warning

import android.util.Log

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
