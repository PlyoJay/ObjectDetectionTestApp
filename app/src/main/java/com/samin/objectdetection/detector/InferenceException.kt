package com.samin.objectdetection.detector

/** An empty successful result is different from an unsuccessful detector invocation. */
class InferenceException(
    val stage: String,
    val inferenceStartedAtMs: Long?,
    val inferenceStartedElapsedNs: Long?,
    cause: Throwable
) : RuntimeException("$stage: ${cause.message ?: cause.javaClass.simpleName}", cause)
