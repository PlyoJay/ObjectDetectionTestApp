package com.samin.objectdetection.metrics

import java.util.Locale

/** Device battery measurements, never app-attributed power or CPU/GPU temperature. */
data class BatteryReading(
    val levelPct: Double? = null,
    val temperatureC: Double? = null,
    val voltageMv: Int? = null,
    val currentMa: Double? = null,
    val currentAverageMa: Double? = null,
    val chargeCounterUah: Int? = null,
    val energyCounterNwh: Long? = null,
    val chargingStatus: String = "Unknown",
    val pluggedType: String = "Unknown"
) {
    val estimatedPowerMw: Double? get() = currentMa?.let { current -> voltageMv?.let { current * it / 1000.0 } }
}

/** Preserve the Android sign: positive into battery, negative out. Zero is a valid reading. */
object BatteryUnits {
    fun currentMa(value: Int?): Double? = value?.takeUnless { it == Int.MIN_VALUE || it == Int.MAX_VALUE }?.div(1000.0)
    fun chargeUah(value: Int?): Int? = value?.takeIf { it >= 0 && it != Int.MAX_VALUE }
    fun energyNwh(value: Long?): Long? = value?.takeIf { it >= 0 && it != Long.MAX_VALUE }
    fun levelPct(level: Int?, scale: Int?): Double? =
        if (level != null && scale != null && scale > 0 && level in 0..scale) level * 100.0 / scale else null
    fun temperatureC(tenths: Int?): Double? = tenths?.takeIf { it in -1000..2000 }?.div(10.0)
}

data class BatterySample(val elapsedNs: Long, val reading: BatteryReading, val system: SystemMetricsSample?, val collectionDurationMs: Double = 0.0)

/** Shares the system executor/tick; faster system ticks cannot poll the battery faster than 5s. */
class BatteryMetricsReader(private val source: SystemMetricSource, private val elapsedNow: () -> Long) {
    private var lastNs: Long? = null
    fun sample(system: SystemMetricsSample?, force: Boolean = false): BatterySample? {
        val now = elapsedNow()
        if (!force && lastNs?.let { now - it < MIN_INTERVAL_NS } == true) return null
        lastNs = now
        val reading = try { source.battery() } catch (_: Exception) { null } catch (_: LinkageError) { null }
        return BatterySample(now, reading ?: BatteryReading(), system, (elapsedNow() - now).coerceAtLeast(0) / 1e6)
    }
    companion object { const val MIN_INTERVAL_NS = 5_000_000_000L }
}

/** Constant memory, including long recordings; means are sample means, separately by status. */
class BatterySummary {
    private var first: BatterySample? = null
    private var last: BatterySample? = null
    private var maxTemperature: Double? = null
    private var count = 0L
    private var currentValid = 0L
    private var powerValid = 0L
    private var temperatureValid = 0L
    private var complete = 0L
    private var chargingChanged = false
    private var pluggedChanged = false
    private var thermalChanged = false
    private var lastThermal: Int? = null
    private val groups = linkedMapOf<String, Means>()

    fun thermal(event: ThermalStatusEvent) {
        event.status?.let { status ->
            if (lastThermal != null && lastThermal != status) thermalChanged = true
            lastThermal = status
        }
    }

    fun record(sample: BatterySample) {
        val r = sample.reading
        if (first == null) first = sample
        last?.reading?.let {
            if (it.chargingStatus != r.chargingStatus) chargingChanged = true
            if (it.pluggedType != r.pluggedType) pluggedChanged = true
        }
        last = sample; count++
        r.temperatureC?.let { temperatureValid++; maxTemperature = maxOf(maxTemperature ?: it, it) }
        if (r.currentMa != null) currentValid++
        if (r.estimatedPowerMw != null) powerValid++
        if (r.levelPct != null && r.temperatureC != null && r.voltageMv != null && r.currentMa != null &&
            r.currentAverageMa != null && r.chargeCounterUah != null && r.energyCounterNwh != null &&
            r.chargingStatus != "Unknown" && r.pluggedType != "Unknown") complete++
        groups.getOrPut(r.chargingStatus) { Means() }.add(r)
    }

    fun format(header: PerformanceLogHeader, stoppedNs: Long): String = buildString {
        val start = first?.reading; val end = last?.reading
        append("BATTERY_SUMMARY | sessionId=${header.sessionId} measurementDurationSeconds=${n((stoppedNs - header.startedElapsedNs).coerceAtLeast(0) / 1e9)} ")
        append("firstSampleSeconds=${n(first?.let { (it.elapsedNs - header.startedElapsedNs) / 1e9 })} lastSampleSeconds=${n(last?.let { (it.elapsedNs - header.startedElapsedNs) / 1e9 })} ")
        append("sampleSpanSeconds=${n(first?.let { f -> last?.let { (it.elapsedNs - f.elapsedNs).coerceAtLeast(0) / 1e9 } })} ")
        append("startBatteryLevelPct=${n(start?.levelPct)} endBatteryLevelPct=${n(end?.levelPct)} batteryLevelDeltaPct=${n(delta(start?.levelPct, end?.levelPct))} ")
        append("startBatteryTemperatureC=${n(start?.temperatureC)} endBatteryTemperatureC=${n(end?.temperatureC)} maxBatteryTemperatureC=${n(maxTemperature)} batteryTemperatureDeltaC=${n(delta(start?.temperatureC, end?.temperatureC))} ")
        append("samples=$count validSamples=$complete unavailableSamples=${count - complete} currentValidSamples=$currentValid currentUnavailableSamples=${count - currentValid} powerValidSamples=$powerValid powerUnavailableSamples=${count - powerValid} temperatureValidSamples=$temperatureValid ")
        append("chargingStatusChanged=$chargingChanged pluggedTypeChanged=$pluggedChanged thermalStatusChanged=$thermalChanged scope=device_battery power=estimated_signed sampleMean=true")
        groups.forEach { (status, mean) -> append("\nBATTERY_STATUS_SUMMARY | sessionId=${header.sessionId} chargingStatus=$status ${mean.format()}") }
    }

    private class Means {
        var current = 0.0; var currents = 0L; var power = 0.0; var powers = 0L
        fun add(r: BatteryReading) {
            r.currentMa?.let { current += it; currents++ }
            r.estimatedPowerMw?.let { power += it; powers++ }
        }
        fun format() = "averageBatteryCurrentMa=${n(if (currents > 0) current / currents else null)} currentValidSamples=$currents " +
            "averageEstimatedBatteryPowerMw=${n(if (powers > 0) power / powers else null)} powerValidSamples=$powers scope=device_battery"
    }

    companion object {
        private fun delta(start: Double?, end: Double?): Double? = start?.let { s -> end?.minus(s) }
        fun n(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.3f", it) } ?: "N/A"
        fun formatSample(header: PerformanceLogHeader, sample: BatterySample): String = with(sample.reading) {
            "BATTERY_SAMPLE | sessionId=${header.sessionId} elapsedSeconds=${n((sample.elapsedNs - header.startedElapsedNs) / 1e9)} " +
                "batteryLevelPct=${n(levelPct)} batteryTemperatureC=${n(temperatureC)} batteryVoltageMv=${voltageMv ?: "N/A"} " +
                "batteryCurrentMa=${n(currentMa)} batteryCurrentAverageMa=${n(currentAverageMa)} batteryChargeCounterUah=${chargeCounterUah ?: "N/A"} batteryEnergyCounterNwh=${energyCounterNwh ?: "N/A"} " +
                "chargingStatus=$chargingStatus pluggedType=$pluggedType estimatedBatteryPowerMw=${n(estimatedPowerMw)} " +
                "thermalStatus=${PerformanceLogFormatter.thermal(sample.system?.thermalStatus)} thermalHeadroom=${n(sample.system?.thermalHeadroom?.toDouble())} " +
                "headroomAgeMs=${n(sample.system?.headroomSampleElapsedNs?.let { (sample.elapsedNs - it) / 1e6 })} " +
                "batteryCollectionDurationMs=${n(sample.collectionDurationMs)} " +
                "scope=device_battery power=estimated_signed temperature=battery_not_cpu_gpu"
        }
    }
}
