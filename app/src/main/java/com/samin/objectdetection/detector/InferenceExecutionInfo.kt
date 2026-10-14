package com.samin.objectdetection.detector

/** Configuration acceptance is observable; delegate partition coverage is not exposed by Java. */
data class InferenceExecutionInfo(
    val requestedBackend: String = "CPU",
    val appliedBackend: String = "CPU",
    val cpuThreads: Int,
    val xnnpackRequested: Boolean,
    val initialization: String = "INTERPRETER_CREATED",
    val delegateVerification: String = "UNAVAILABLE_JAVA_API",
    val lastInference: String = "NOT_RUN",
    val inferenceErrors: Long = 0,
    val cpuFallbackCount: Long = 0,
    val gpuSupport: String = "UNSUPPORTED_INTERPRETER_LITERT_2_1_1",
    val runtimeVersion: String = "unknown"
) {
    fun logFields(): String =
        "requestedBackend=$requestedBackend appliedBackend=$appliedBackend cpuThreads=$cpuThreads " +
            "xnnpackRequested=$xnnpackRequested initialization=$initialization " +
            "delegateVerification=$delegateVerification lastInference=$lastInference " +
            "inferenceErrors=$inferenceErrors cpuFallbackCount=$cpuFallbackCount gpuSupport=$gpuSupport " +
            "runtimeVersion=${runtimeVersion.replace(' ', '_')}"
}
