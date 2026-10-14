package com.samin.objectdetection.metrics

import com.samin.objectdetection.logging.OrderedLogWriter
import java.io.BufferedWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** No Android dependencies: session ordering, stop/drain and restricted-source behavior are testable. */
class PerformanceLogSession(
    private val header: PerformanceLogHeader,
    output: BufferedWriter,
    source: SystemMetricSource?,
    private val elapsedNow: () -> Long,
    wallNow: () -> Long,
    onFailure: (Throwable) -> Unit = {},
    queueCapacity: Int = 512
) {
    private val lock = Any()
    private val stats = PerformanceIntervalCollector(header.startedElapsedNs)
    private val sequence = AtomicLong()
    private var closing = false
    private var stoppedElapsedNs = header.startedElapsedNs
    private var completion: CompletableFuture<Unit>? = null
    private var nextPreviewIndex = 0L
    private val writer: OrderedLogWriter = OrderedLogWriter(output, queueCapacity) { error ->
        diagnostics.stop()
        runCatching { onFailure(error) }
    }
    private val diagnostics: SystemPerformanceDiagnostics = SystemPerformanceDiagnostics(
        if (header.systemDiagnosticsEnabled) source else null, header.systemSampleIntervalMs,
        elapsedNow, wallNow,
        onSample = { sample -> synchronized(lock) {
            if (!closing) {
                stats.recordSystem(sample)
                writer.append { PerformanceLogFormatter.formatSystemSample(header, sample) }
            }
        } },
        onThermal = { event -> synchronized(lock) {
            if (!closing) writer.append { PerformanceLogFormatter.formatThermalEvent(header, event) }
        } },
        onTick = { now ->
            val completedWindows = ((now - header.startedElapsedNs).coerceAtLeast(0) / PerformanceIntervalCollector.INTERVAL_NS)
            while (nextPreviewIndex < completedWindows) {
                val interval = stats.interval(nextPreviewIndex++, now)
                synchronized(lock) {
                    if (!closing) writer.append { PerformanceLogFormatter.formatInterval(header, interval, "INTERVAL_PREVIEW") }
                }
            }
        }
    )

    val droppedLogLines: Long get() = writer.droppedLines.get()
    fun start() = diagnostics.start()

    fun recordFrame(frame: PerformanceFrameRecord) = synchronized(lock) {
        if (closing) return@synchronized
        val completed = frame.trace?.completedElapsedNs ?: elapsedNow()
        stats.record(frame, completed)
        val indexed = frame.copy(frameIndex = sequence.incrementAndGet(),
            elapsedSeconds = (completed - header.startedElapsedNs).coerceAtLeast(0) / 1e9)
        writer.append { PerformanceLogFormatter.formatFrame(indexed) }
    }

    fun append(line: () -> String) = synchronized(lock) {
        if (!closing) writer.append(line)
    }

    fun close(snapshot: PerformanceSnapshot): CompletableFuture<Unit> = synchronized(lock) {
        completion?.let { return@synchronized it }
        closing = true
        stoppedElapsedNs = elapsedNow()
        // Cleanup is serialized behind in-flight collection; then writer drains all accepted lines.
        diagnostics.stop().thenCompose {
            writer.close {
                PerformanceLogFormatter.formatFinalSummary(snapshot, writer.droppedLines.get()) +
                    "\n--- EXACT SESSION AND 30-SECOND INTERVAL SUMMARIES ---\n" +
                    stats.finalSummaries(stoppedElapsedNs).joinToString("\n") {
                        PerformanceLogFormatter.formatInterval(header, it,
                            if (it.index == null) "CUMULATIVE_SUMMARY" else "INTERVAL_SUMMARY")
                    } + "\n--- END INTERVAL SUMMARIES ---"
            }
        }.also { completion = it }
    }
}
