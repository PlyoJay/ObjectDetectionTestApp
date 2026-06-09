package com.samin.objectdetection.warning

class CompositeWarningPlayer(
    private val players: List<WarningPlayer>
) : WarningPlayer {

    override fun playIfNeeded(decision: WarningDecision) {
        players.forEach { it.playIfNeeded(decision) }
    }

    override fun close() {
        players.forEach { it.close() }
    }
}
