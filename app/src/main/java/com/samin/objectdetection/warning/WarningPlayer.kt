package com.samin.objectdetection.warning

interface WarningPlayer {
    fun playIfNeeded(decision: WarningDecision)

    fun release() = Unit
}
