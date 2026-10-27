package com.samin.objectdetection.evaluation

import com.samin.objectdetection.detector.DetectionStageCounts
import com.samin.objectdetection.detector.ModelIdentity
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.settings.AppSettings
import com.samin.objectdetection.settings.AppSettingsCodec
import org.json.JSONObject
import java.io.StringReader
import java.util.Properties

/** Additive keys shared by capture JSON and detection JSONL; existing parser keys stay intact. */
internal fun JSONObject.putDistance(detection: DetectionResult): JSONObject = apply {
    val estimate = detection.distanceEstimation
    val depth = detection.depthEstimation
    val meters = detection.estimatedDistanceMeters?.takeIf { it.isFinite() && it > 0f }
    put("estimatedDistanceMeters", meters?.toDouble() ?: JSONObject.NULL)
    put("distanceEstimationMethod", if (depth != null) "ARCORE_DEPTH" else estimate?.method ?: JSONObject.NULL)
    put("distanceEstimationValid", meters != null)
    put("distanceEstimationStatus", depth?.status ?: estimate?.status ?: "NOT_ESTIMATED")
    put("distanceSource", if (depth != null) "ARCORE_DEPTH" else if (estimate != null) "GROUND_PLANE" else JSONObject.NULL)
    put("distanceDefinition", if (depth != null) "CAMERA_FORWARD_Z" else JSONObject.NULL)
    put("depthMethod", depth?.method?.name ?: JSONObject.NULL)
    put("rawDepthMeters", depth?.rawMeters?.takeIf { it.isFinite() && it > 0 }?.toDouble() ?: JSONObject.NULL)
    put("depthSampleCount", depth?.sampleCount ?: 0)
    put("depthFrameTimestampNs", depth?.frameTimestampNs ?: JSONObject.NULL)
    put("depthTimestampNs", depth?.depthTimestampNs ?: JSONObject.NULL)
    put("depthTrackingState", depth?.trackingState ?: JSONObject.NULL)
    put("distanceOpticsSource", estimate?.intrinsics?.source ?: JSONObject.NULL)
    put("distanceIntrinsics", estimate?.intrinsics?.let { k -> JSONObject()
        .put("fx", k.fx.toDouble()).put("fy", k.fy.toDouble())
        .put("cx", k.cx.toDouble()).put("cy", k.cy.toDouble())
        .put("coordinateSpace", "UPRIGHT_FULL_ANALYSIS_BUFFER") } ?: JSONObject.NULL)
}

/** Opt-in performance logging reuses the same additive object distance keys as evaluation JSONL. */
internal fun distanceFrameJson(detections: List<DetectionResult>): String = org.json.JSONArray(detections.map { d ->
    JSONObject().put("label", d.label).put("confidence", d.confidence.toDouble())
        .put("bbox", org.json.JSONArray(listOf(d.left, d.top, d.right, d.bottom)))
        .putDistance(d)
}).toString()

internal fun ModelIdentity.toJson(): JSONObject = JSONObject()
    .put("assetName", assetName)
    .put("assetSizeBytes", assetSizeBytes)
    .put("sha256", sha256)
    .put("inputShape", inputShape)
    .put("outputShape", outputShape)
    .put("inputType", inputType)
    .put("outputType", outputType)
    .put("classCount", classCount)
    .put("coordinateScale", coordinateScale)

/** Reuse the settings codec so newly added UI settings are also captured in evaluation data. */
internal fun AppSettings.toJson(): JSONObject {
    val properties = Properties().apply { load(StringReader(AppSettingsCodec.encode(this@toJson))) }
    val result = JSONObject()
    for (key in properties.stringPropertyNames().sorted()) {
        val text = properties.getProperty(key)
        val value: Any = when {
            key == "presetName" -> text
            text == "true" -> true
            text == "false" -> false
            else -> text.toLongOrNull()
                ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: text
        }
        val section = key.substringBefore('.', "")
        if (section.isEmpty()) {
            result.put(key, value)
        } else {
            val group = result.optJSONObject(section) ?: JSONObject().also { result.put(section, it) }
            group.put(key.substringAfter('.'), value)
        }
    }
    return result
}

internal fun DetectionStageCounts.toJson(): JSONObject = JSONObject()
    .put("rawCandidateCount", rawCandidateCount ?: JSONObject.NULL)
    .put("confidencePassedCount", confidencePassedCount ?: JSONObject.NULL)
    .put("invalidBoxCount", invalidBoxCount ?: JSONObject.NULL)
    .put("detectorSizeRejectedCount", detectorSizeRejectedCount ?: JSONObject.NULL)
    .put("nmsInputCount", nmsInputCount ?: JSONObject.NULL)
    .put("nmsPassedCount", nmsPassedCount)
    .put("geometryPassedCount", geometryPassedCount)
    .put("sizeFilterPassedCount", sizeFilterPassedCount)
    .put("overlayClassPassedCount", overlayClassPassedCount)
    .put("temporalPassedCount", temporalPassedCount)
    .put("finalDetectionCount", finalDetectionCount)
