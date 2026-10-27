package com.samin.objectdetection.arcore

import com.samin.objectdetection.logging.OrderedLogWriter
import java.io.File
import java.io.BufferedWriter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

object DepthCsv {
    const val HEADER = "timestamp,frameTimestampNs,trackingState,depthWidth,depthHeight,centerX,centerY,rawDepthMeters,filteredDepthMeters,validSampleCount,depthTimestampNs,depthStatus,groundTruthMeters,absoluteErrorMeters,relativeErrorPercent,source,distanceDefinition"
    fun row(value: DepthMeasurement): String = with(value.sample) {
        listOf(Instant.ofEpochMilli(timestampMs).toString(), frameTimestampNs, trackingState, depthWidth, depthHeight,
            centerX, centerY, rawDepthMeters, value.filteredDepthMeters, validSampleCount, depthTimestampNs, depthStatus,
            value.groundTruthMeters, value.absoluteErrorMeters, value.relativeErrorPercent, "ARCORE_DEPTH", "CAMERA_FORWARD_Z")
            .joinToString(",") { cell ->
                val text = when (cell) { null -> ""; is Double -> if (cell.isFinite()) cell.toString() else ""; else -> cell.toString() }
                if (text.any { it == ',' || it == '\n' || it == '\r' || it == '"' }) "\"${text.replace("\"", "\"\"")}\"" else text
            }
    }
}

/** Independent directory and writer; streaming preserves raw readings without an in-memory session list. */
class DepthCsvRecorder(private val directory: File,
    private val openWriter: (File) -> BufferedWriter = { it.bufferedWriter(Charsets.UTF_8) }) {
    private var writer: OrderedLogWriter? = null
    private var closing: CompletableFuture<Unit>? = null
    var latestFile: File? = null
        private set
    @Volatile var error: String? = null
        private set
    @Synchronized fun isRecording(): Boolean = writer != null
    @Synchronized fun isClosing(): Boolean = closing?.isDone == false
    @Synchronized fun droppedLines(): Long = writer?.droppedLines?.get() ?: lastDropped
    private var lastDropped = 0L

    @Synchronized fun start(): File {
        check(writer == null && !isClosing()) { "이전 측정 로그 저장이 끝날 때까지 기다려 주세요." }
        check(directory.isDirectory || directory.mkdirs()) { "측정 로그 폴더를 만들 수 없습니다." }
        val file = File(directory, "arcore_depth_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.csv")
        check(DepthLogFiles.incompleteMarker(file).createNewFile()) { "CSV 기록 상태를 저장할 수 없습니다." }
        val buffered = openWriter(file)
        error = null
        lastDropped = 0
        writer = OrderedLogWriter(buffered, onFailure = { error = it.message ?: "CSV write failed" }).also { it.append { DepthCsv.HEADER } }
        latestFile = file
        return file
    }

    @Synchronized fun append(value: DepthMeasurement) { writer?.append { DepthCsv.row(value) } }

    @Synchronized fun stop(): CompletableFuture<Unit> {
        val current = writer ?: return closing ?: CompletableFuture.completedFuture(Unit)
        writer = null
        val file = requireNotNull(latestFile)
        val completion = current.close().thenApply {
            val marker = DepthLogFiles.incompleteMarker(file)
            check(!marker.exists() || marker.delete()) { "CSV 저장 완료 상태를 기록할 수 없습니다: ${file.name}" }
            Unit
        }
        closing = completion
        completion.whenComplete { _, failure -> synchronized(this) {
            if (latestFile == file) {
                lastDropped = current.droppedLines.get()
                if (failure != null) error = failure.cause?.message ?: failure.message ?: "CSV close failed"
            }
        } }
        return completion
    }
}
