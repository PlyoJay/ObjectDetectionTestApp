package com.samin.objectdetection.evaluation

import com.samin.objectdetection.detector.DetectionStageCounts
import com.samin.objectdetection.detector.ModelIdentity
import com.samin.objectdetection.settings.AppSettings
import com.samin.objectdetection.settings.AppSettingsCodec
import org.json.JSONObject
import java.io.StringReader
import java.util.Properties

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
