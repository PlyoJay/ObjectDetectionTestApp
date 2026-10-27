package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.logging.OrderedLogWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Opt-in, at most one line per accepted YOLO frame (<=4Hz). Separate from all existing CSV logs. */
class ArYoloJsonLog(private val directory: File) {
    private var writer: OrderedLogWriter? = null
    private var completion = CompletableFuture.completedFuture(Unit)
    var latestFile: File? = null
        private set
    @Synchronized fun recording() = writer != null
    @Synchronized fun saving() = !completion.isDone
    @Synchronized fun failed() = completion.isCompletedExceptionally || writer?.failure != null
    @Synchronized fun start() {
        check(writer == null && completion.isDone) { "실험 로그 저장 중입니다." }
        check(directory.isDirectory || directory.mkdirs()) { "실험 로그 폴더 생성 실패" }
        val file = File(directory, "arcore_yolo_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jsonl")
        writer = OrderedLogWriter(file.bufferedWriter())
        completion = CompletableFuture.completedFuture(Unit)
        latestFile = file
    }
    @Synchronized fun append(frame: ArYoloFrame) { writer?.append { json(frame).toString() } }
    @Synchronized fun stop(): CompletableFuture<Unit> {
        writer?.let { completion = it.close(); writer = null }
        return completion
    }
    companion object {
        fun json(frame: ArYoloFrame): JSONObject = JSONObject()
            .put("mode", "EXPERIMENTAL_YOLO_ARCORE").put("distanceSource", "ARCORE_DEPTH")
            .put("distanceDefinition", "CAMERA_FORWARD_Z").put("frameTimestampNs", frame.frameTimestampNs)
            .put("cameraTimestampNs", frame.cameraTimestampNs).put("depthTimestampNs", frame.depthTimestampNs ?: JSONObject.NULL)
            .put("coordinateEpoch", frame.epoch)
            .put("trackingState", frame.tracking).put("imageWidth", frame.imageWidth).put("imageHeight", frame.imageHeight)
            .put("depthWidth", frame.depthWidth).put("depthHeight", frame.depthHeight).put("inferenceMs", frame.inferenceMs)
            .put("detections", JSONArray(frame.objects.map { obj ->
                JSONObject().put("label", obj.detection.label).put("confidence", obj.detection.confidence.toDouble())
                    .put("bbox", JSONArray(listOf(obj.detection.left, obj.detection.top, obj.detection.right, obj.detection.bottom)))
                    .put("bboxSpace", "CPU_IMAGE_PIXELS").put("viewportBox", JSONArray(listOf(obj.viewBox.left, obj.viewBox.top, obj.viewBox.right, obj.viewBox.bottom)))
                    .put("rawDepthMeters", obj.depth.rawMeters?.toDouble() ?: JSONObject.NULL)
                    .put("estimatedDistanceMeters", obj.depth.meters?.toDouble() ?: JSONObject.NULL)
                    .put("depthSampleCount", obj.depth.sampleCount).put("distanceStatus", obj.depth.status)
                    .put("samplePoint", obj.depth.samplePoint?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            }))
    }
}
