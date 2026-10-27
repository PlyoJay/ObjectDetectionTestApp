package com.samin.objectdetection.evaluation

import com.samin.objectdetection.metrics.FrameTrace
import org.json.JSONObject

fun JSONObject.putTrace(trace: FrameTrace): JSONObject = apply {
    put("sessionId", trace.sessionId); put("frameId", trace.frameId); put("status", trace.status)
    put("cameraBackend", trace.cameraBackend)
    put("cameraTimestampNs", trace.cameraTimestampNs); put("captureTimestampReliable", trace.captureTimestampReliable)
    put("analyzerReceivedMs", trace.analyzerReceivedMs); put("analyzerReceivedElapsedNs", trace.analyzerReceivedElapsedNs)
    put("inferenceStartedAtMs", trace.inferenceStartedAtMs ?: JSONObject.NULL)
    put("inferenceStartedElapsedNs", trace.inferenceStartedElapsedNs ?: JSONObject.NULL)
    put("inferenceEndedElapsedNs", trace.inferenceEndedElapsedNs ?: JSONObject.NULL)
    put("pipelineCompletedElapsedNs", trace.completedElapsedNs?.takeIf { trace.status == "SUCCESS" } ?: JSONObject.NULL)
    put("completedAtMs", trace.completedAtMs ?: JSONObject.NULL); put("completedElapsedNs", trace.completedElapsedNs ?: JSONObject.NULL)
    put("frameAgeAtInferenceMs", trace.frameAgeAtInferenceMs); put("analyzerToInferenceMs", trace.analyzerToInferenceMs)
    put("errorStage", trace.errorStage ?: JSONObject.NULL); put("error", trace.error ?: JSONObject.NULL)
}
