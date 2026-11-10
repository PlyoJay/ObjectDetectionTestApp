package com.samin.objectdetection.warning.output

import com.samin.objectdetection.warning.WarningCandidate

interface WarningPlayer {
    fun playIfNeeded(candidate: WarningCandidate)

    fun release() = Unit
}
