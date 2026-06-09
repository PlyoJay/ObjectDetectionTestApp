package com.samin.objectdetection.warning

interface WarningPlayer : AutoCloseable {
    fun playIfNeeded(decision: WarningDecision)

    override fun close() = Unit
}
