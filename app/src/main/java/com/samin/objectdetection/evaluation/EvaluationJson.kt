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
    put("selectedDepthMethod", depth?.method?.name?.takeIf { meters != null } ?: JSONObject.NULL)
    put("rawDepthMeters", depth?.rawMeters?.takeIf { it.isFinite() && it > 0 }?.toDouble() ?: JSONObject.NULL)
    put("depthSampleCount", depth?.sampleCount ?: 0)
    put("depthFrameTimestampNs", depth?.frameTimestampNs ?: JSONObject.NULL)
    put("depthTimestampNs", depth?.depthTimestampNs ?: JSONObject.NULL)
    put("depthTrackingState", depth?.trackingState ?: JSONObject.NULL)
    put("objectLabel", detection.label)
    put("frameTimestampNs", depth?.frameTimestampNs ?: JSONObject.NULL)
    put("distanceStatus", depth?.status ?: estimate?.status ?: "NOT_ESTIMATED")
    put("groundTruthMeters", depth?.groundTruthMeters?.toDouble() ?: JSONObject.NULL)
    put("groundTruthDefinition", if (depth?.groundTruthMeters != null) "CAMERA_FORWARD_Z" else JSONObject.NULL)
    put("groundTruthStatus", depth?.groundTruthStatus ?: "NOT_SET")
    put("absoluteErrorMeters", depth?.absoluteErrorMeters?.toDouble() ?: JSONObject.NULL)
    put("relativeErrorPercent", depth?.relativeErrorPercent?.toDouble() ?: JSONObject.NULL)
    put("filteredAbsoluteErrorMeters", depth?.filteredAbsoluteErrorMeters?.toDouble() ?: JSONObject.NULL)
    put("depthFilterStatus", depth?.filterStatus ?: JSONObject.NULL)
    put("depthFilterLagMs", depth?.filterLagMs ?: JSONObject.NULL)
    put("distanceUpdateDelayMs", depth?.updateDelayMs ?: JSONObject.NULL)
    put("depthTrackId", depth?.trackId ?: JSONObject.NULL)
    put("depthAnalysis", depth?.analysis?.toJson() ?: JSONObject.NULL)
    put("distanceOpticsSource", estimate?.intrinsics?.source ?: JSONObject.NULL)
    put("distanceIntrinsics", estimate?.intrinsics?.let { k -> JSONObject()
        .put("fx", k.fx.toDouble()).put("fy", k.fy.toDouble())
        .put("cx", k.cx.toDouble()).put("cy", k.cy.toDouble())
        .put("coordinateSpace", "UPRIGHT_FULL_ANALYSIS_BUFFER") } ?: JSONObject.NULL)
}

private fun com.samin.objectdetection.distance.DepthCandidateSummary.toJson(): JSONObject = JSONObject()
    .put("depthMethod", method?.name ?: JSONObject.NULL).put("meters", meters?.toDouble() ?: JSONObject.NULL)
    .put("validPixelCount", count).put("status", status).put("depthTimestampNs", timestampNs ?: JSONObject.NULL)
    .put("confidenceMin", confidenceMin ?: JSONObject.NULL).put("confidenceMean", confidenceMean?.toDouble() ?: JSONObject.NULL)
    .put("confidenceMax", confidenceMax ?: JSONObject.NULL).put("lowConfidenceCount", lowConfidenceCount)
    .put("confidenceHistogram0to63_64to127_128to191_192to255", org.json.JSONArray(confidenceHistogram))
    .put("clusters", org.json.JSONArray(clusters.map { c -> JSONObject().put("meters", c.meters.toDouble()).put("count", c.count)
        .put("coreCount", c.coreCount).put("regions", c.regions).put("backgroundFraction", c.backgroundFraction.toDouble()).put("spreadMeters", c.spreadMeters.toDouble()) }))

private fun com.samin.objectdetection.distance.DepthAnalysis.toJson(): JSONObject = JSONObject()
    .put("full", full?.toJson() ?: JSONObject.NULL).put("raw", raw?.toJson() ?: JSONObject.NULL).put("selectionReason", selectionReason)
    .put("mappingMaxErrorPixels", maxMappingErrorPixels?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
    .put("mappingMeanErrorPixels", meanMappingErrorPixels?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
    .put("roundTripErrorPixels", roundTripErrorPixels?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
    .put("viewMappingMaxErrorPixels", maxViewMappingErrorPixels?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
    .put("samplePoints", org.json.JSONArray(points.map { p -> JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())
        .put("space", "UPRIGHT_CPU_PIXELS").put("viewNormalizedX", p.viewX?.toDouble() ?: JSONObject.NULL)
        .put("viewNormalizedY", p.viewY?.toDouble() ?: JSONObject.NULL).put("role", p.role).put("valid", p.valid).put("meters", p.meters?.toDouble() ?: JSONObject.NULL) }))

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
