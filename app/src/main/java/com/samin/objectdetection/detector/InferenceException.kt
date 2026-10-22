package com.samin.objectdetection.detector

/** An empty successful result is different from an unsuccessful detector invocation. */
class InferenceException(
    val stage: String,
    val inferenceStartedAtMs: Long?,
    val inferenceStartedElapsedNs: Long?,
    cause: Throwable,
    val inferenceEndedElapsedNs: Long? = null
) : RuntimeException("$stage: ${cause.message ?: cause.javaClass.simpleName}", cause)
