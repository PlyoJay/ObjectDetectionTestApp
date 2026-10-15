package com.samin.objectdetection.metrics

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SystemMetricsSample(
    val elapsedNs: Long,
    val unixMs: Long,
    val thermalStatus: Int?,
    val thermalHeadroom: Float?,
    val headroomSampleElapsedNs: Long?,
    val processCpuUsage: Double?,
    val cpuUsageWindowMs: Double?,
    val cpuFrequency: String?,
    val collectionDurationMs: Double
)

data class ThermalStatusEvent(val elapsedNs: Long, val unixMs: Long, val previous: Int?, val status: Int?, val source: String)

/** All implementations are queried only on the diagnostics executor, never on CameraX/UI. */
interface SystemMetricSource {
    fun battery(): BatteryReading? = null
    fun thermalStatus(): Int?
    fun thermalHeadroom(): Float?
    fun processCpuTimeMs(): Long?
    fun cpuFrequency(): String?
    fun listenThermal(executor: Executor, onStatus: (Int) -> Unit): AutoCloseable? = null
}

class ProcessCpuUsageMeter {
    private var previous: Pair<Long, Long>? = null
    fun measure(elapsedNs: Long, cpuTimeMs: Long?): Pair<Double?, Double?> {
        val before = previous
        previous = cpuTimeMs?.takeIf { it >= 0 }?.let { elapsedNs to it }
        if (cpuTimeMs == null || before == null || elapsedNs <= before.first || cpuTimeMs < before.second) return null to null
        val windowMs = (elapsedNs - before.first) / 1e6
        // Sum of all process threads; 100% = one core, so multicore values can exceed 100%.
        return ((cpuTimeMs - before.second) * 100.0 / windowMs) to windowMs
    }
}

/** Deterministic sampling logic, with API/permission failures isolated per metric. */
class SystemMetricsReader(
    private val source: SystemMetricSource,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
    private val onThermal: (ThermalStatusEvent) -> Unit
) {
    private val cpu = ProcessCpuUsageMeter()
    private var lastHeadroomNs: Long? = null
    private var headroom: Float? = null
    private var status: Int? = null
    private var statusObserved = false

    fun thermalChanged(value: Int?, sourceName: String) {
        val valid = value?.takeIf { it in 0..6 }
        if (!statusObserved || valid != status) {
            onThermal(ThermalStatusEvent(elapsedNow(), wallNow(), status, valid, sourceName))
            status = valid; statusObserved = true
        }
    }

    fun sample(): SystemMetricsSample {
        val start = elapsedNow()
        val wallStart = wallNow()
        thermalChanged(safe { source.thermalStatus() }, "poll")
        val previousHeadroom = lastHeadroomNs
        if (previousHeadroom == null || start - previousHeadroom >= HEADROOM_MIN_INTERVAL_NS) {
            headroom = safe { source.thermalHeadroom() }?.takeIf { it.isFinite() && it >= 0f }
            lastHeadroomNs = start // throttle retries too, including NaN/denied/unsupported
        }
        val cpuTime = safe { source.processCpuTimeMs() }
        val cpuResult = cpu.measure(elapsedNow(), cpuTime)
        val frequency = safe { source.cpuFrequency() }
        return SystemMetricsSample(start, wallStart, status, headroom, lastHeadroomNs.takeIf { headroom != null },
            cpuResult.first, cpuResult.second, frequency, (elapsedNow() - start).coerceAtLeast(0) / 1e6)
    }

    private fun <T> safe(read: () -> T?): T? = try { read() } catch (_: Exception) { null } catch (_: LinkageError) { null }
    companion object { const val HEADROOM_MIN_INTERVAL_NS = 10_000_000_000L }
}

/** Single background owner for sampling, thermal callbacks and 30-second previews.
 * stop rejects late callbacks, unregisters the listener and completes after accepted work exits. */
class SystemPerformanceDiagnostics(
    private val source: SystemMetricSource?, // null means OFF: interval previews only; no system API queries
    private val intervalMs: Long,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
    private val onSample: (SystemMetricsSample) -> Unit,
    private val onThermal: (ThermalStatusEvent) -> Unit,
    private val onTick: (Long) -> Unit,
    private val batteryEnabled: Boolean = false,
    private val onBattery: (BatterySample) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "PerformanceDiagnostics").apply { isDaemon = true }
    }
    private val stopping = AtomicBoolean()
    private val started = AtomicBoolean()
    private val completion = CompletableFuture<Unit>()
    private var scheduled: ScheduledFuture<*>? = null
    private var listener: AutoCloseable? = null
    private val batteryReader = source?.takeIf { batteryEnabled }?.let { BatteryMetricsReader(it, elapsedNow) }
    private var lastSystem: SystemMetricsSample? = null
    private val callbackExecutor = Executor { task ->
        if (!stopping.get()) try { executor.execute { if (!stopping.get()) task.run() } }
        catch (_: RejectedExecutionException) { /* late platform callback */ }
    }

    fun start() {
        if (!started.compareAndSet(false, true) || stopping.get()) return
        executor.execute {
            if (stopping.get()) return@execute
            val reader = source?.let { SystemMetricsReader(it, elapsedNow, wallNow, onThermal) }
            listener = try { source?.listenThermal(callbackExecutor) { reader?.thermalChanged(it, "listener_receipt") } }
                catch (_: Exception) { null } catch (_: LinkageError) { null }
            fun tick() {
                if (stopping.get()) return
                // A failed diagnostics callback cannot cancel subsequent collection or escape to inference.
                runCatching { reader?.sample()?.let { lastSystem = it; onSample(it) } }
                runCatching { batteryReader?.sample(lastSystem)?.let(onBattery) }
                runCatching { onTick(elapsedNow()) }
            }
            tick()
            if (!stopping.get()) scheduled = executor.scheduleWithFixedDelay(
                { tick() }, if (source == null) 30_000L else intervalMs.coerceAtLeast(1_000L),
                if (source == null) 30_000L else intervalMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        }
    }

    fun stop(): CompletableFuture<Unit> {
        if (stopping.compareAndSet(false, true)) {
            executor.execute {
                scheduled?.cancel(false)
                // Final battery endpoint runs here, never on the caller/CameraX thread.
                if (started.get()) runCatching { batteryReader?.sample(lastSystem, force = true)?.let(onBattery) }
                runCatching { listener?.close() }
                listener = null
                executor.shutdown()
                completion.complete(Unit)
            }
        }
        return completion
    }
}
