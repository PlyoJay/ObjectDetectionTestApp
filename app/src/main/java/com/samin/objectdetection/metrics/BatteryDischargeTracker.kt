package com.samin.objectdetection.metrics

import com.samin.objectdetection.metrics.BatterySummary.Companion.n
import java.util.Locale

/** Cross-checks two device-wide fuel-gauge paths. Never infers a vendor-specific unit. */
data class BatteryAssessment(
    val currentReliability: String = "NOT_VALIDATED",
    val intervalSeconds: Double? = null,
    val chargeDropMah: Double? = null,
    val counterDischargeMa: Double? = null,
    val counterDischargePowerMw: Double? = null,
    val warnings: List<String> = listOf("CURRENT_NOT_VALIDATED", "CURRENT_AVERAGE_WINDOW_UNKNOWN")
) {
    val currentUsable: Boolean get() = currentReliability == "CONSISTENT_ESTIMATE"

    fun format(): String = "batteryCurrentReliability=$currentReliability " +
        "counterIntervalSeconds=${n(intervalSeconds)} counterChargeDropMah=${n(chargeDropMah)} " +
        "counterAverageDischargeCurrentMa=${n(counterDischargeMa)} counterEstimatedDischargePowerMw=${n(counterDischargePowerMw)} " +
        "counterReliability=UNVERIFIED_FUEL_GAUGE_ESTIMATE batteryWarnings=${warnings.joinToString(",").ifEmpty { "NONE" }}"
}

/** Constant memory. Adjacent samples only: never bridge a missing poll, reset or power transition. */
class BatteryDischargeTracker {
    private var previous: BatterySample? = null
    private var counterSeconds = 0.0
    private var counterMah = 0.0
    private var counterMwh = 0.0
    private var counterPairs = 0L
    private var voltageComplete = true
    private var pairedSeconds = 0.0
    private var pairedCounterMah = 0.0
    private var integratedCurrentMah = 0.0
    private var suspect: String? = null
    private var allDischargePairsCovered = true
    private var reliability = "NOT_VALIDATED"
    private val observedWarnings = linkedSetOf<String>()

    fun record(sample: BatterySample): BatteryAssessment {
        val before = previous
        previous = sample
        val r = sample.reading
        val warnings = mutableListOf("CURRENT_AVERAGE_WINDOW_UNKNOWN", "COUNTER_ESTIMATE_NOT_CALIBRATED")
        var seconds: Double? = null
        var drop: Double? = null
        var current: Double? = null
        var power: Double? = null
        var eligibleCurrentPair = false
        val discharging = r.chargingStatus == "Discharging" && r.pluggedType == "None"
        if (discharging && r.currentMa?.let { it > 0 } == true) suspect = "SUSPECT_CURRENT_SIGN"
        if (r.currentMa == null) warnings += "CURRENT_NOW_UNAVAILABLE"
        if (r.currentAverageMa == null) warnings += "CURRENT_AVERAGE_UNAVAILABLE"
        if (r.voltageMv == null) warnings += "VOLTAGE_UNAVAILABLE"
        if (before == null) {
            warnings += "NO_PREVIOUS_SAMPLE"
        } else {
            val b = before.reading
            val dt = (sample.elapsedNs - before.elapsedNs) / 1e9
            seconds = dt.takeIf { it > 0 }
            val reason = when {
                b.chargingStatus != r.chargingStatus || b.pluggedType != r.pluggedType -> "POWER_STATE_CHANGED"
                !discharging -> "NOT_UNPLUGGED_DISCHARGING"
                dt <= 0 -> "NON_POSITIVE_INTERVAL"
                dt > MAX_GAP_SECONDS -> "SAMPLE_GAP"
                b.chargeCounterUah == null || r.chargeCounterUah == null -> "CHARGE_COUNTER_UNAVAILABLE"
                r.chargeCounterUah > b.chargeCounterUah -> "CHARGE_COUNTER_INCREASE_OR_RESET"
                else -> null
            }
            if (reason != null) {
                warnings += reason
                if (b.chargingStatus == "Discharging" || r.chargingStatus == "Discharging") allDischargePairsCovered = false
            } else {
                // Convert before subtraction to avoid integer overflow at extreme valid counters.
                drop = (b.chargeCounterUah!!.toDouble() - r.chargeCounterUah!!.toDouble()) / 1000.0
                counterSeconds += dt
                counterMah += drop
                counterPairs++
                val mv = b.voltageMv?.let { v -> r.voltageMv?.let { (v.toDouble() + it) / 2 } }
                if (mv == null) voltageComplete = false
                else counterMwh += drop * mv / 1000.0
                if (drop > 0) {
                    current = drop * 3600 / dt
                    power = mv?.let { current * it / 1000 }
                } else warnings += "COUNTER_UNCHANGED_OR_QUANTIZED"
                if (dt < 30) warnings += "SHORT_INTERVAL_COUNTER_QUANTIZATION"
                if (b.currentMa != null && r.currentMa != null && b.currentMa <= 0 && r.currentMa <= 0) {
                    eligibleCurrentPair = true
                    pairedSeconds += dt
                    pairedCounterMah += drop
                    integratedCurrentMah += -(b.currentMa + r.currentMa) / 2 * dt / 3600
                    if (pairedSeconds >= MIN_CHECK_SECONDS && pairedCounterMah >= MIN_CHECK_MAH &&
                        ratio()?.let { it !in 0.1..10.0 } == true) suspect = "SUSPECT_CURRENT_COUNTER_MISMATCH"
                } else allDischargePairsCovered = false
            }
        }
        reliability = suspect ?: if (eligibleCurrentPair && allDischargePairsCovered &&
            pairedSeconds >= MIN_CHECK_SECONDS && pairedCounterMah >= MIN_CHECK_MAH) "CONSISTENT_ESTIMATE" else "NOT_VALIDATED"
        if (reliability != "CONSISTENT_ESTIMATE") warnings += reliability
        if (power == null && current != null) warnings += "INTERVAL_VOLTAGE_UNAVAILABLE"
        observedWarnings.addAll(warnings)
        return BatteryAssessment(reliability, seconds, drop, current, power, warnings.toList())
    }

    private fun ratio(): Double? = if (pairedCounterMah > 0) integratedCurrentMah / pairedCounterMah else null

    fun formatTotals(): String {
        val current = if (counterSeconds > 0 && counterMah > 0) counterMah * 3600 / counterSeconds else null
        val power = if (current != null && voltageComplete) counterMwh * 3600 / counterSeconds else null
        return "batteryCurrentReliability=$reliability counterEligibleIntervals=$counterPairs counterCoveredSeconds=${n(counterSeconds)} " +
            "counterTotalDischargeMah=${n(counterMah.takeIf { counterPairs > 0 })} " +
            "counterAverageDischargeCurrentMa=${n(current)} counterEstimatedDischargePowerMw=${n(power)} " +
            "pairedCurrentSeconds=${n(pairedSeconds)} pairedCounterDischargeMah=${n(pairedCounterMah.takeIf { pairedSeconds > 0 })} " +
            "integratedApiCurrentDischargeMah=${n(integratedCurrentMah.takeIf { pairedSeconds > 0 })} currentToCounterRatio=${ratio()?.let { String.format(Locale.US, "%.6f", it) } ?: "N/A"} " +
            "counterReliability=UNVERIFIED_FUEL_GAUGE_ESTIMATE observedBatteryWarnings=${observedWarnings.joinToString(",").ifEmpty { "NONE" }} " +
            "batteryWarnings=${suspect ?: if (reliability == "NOT_VALIDATED") "CURRENT_NOT_VALIDATED" else "CONSISTENCY_IS_NOT_CALIBRATION"}"
    }

    companion object {
        const val MAX_GAP_SECONDS = 120.0
        const val MIN_CHECK_SECONDS = 60.0
        const val MIN_CHECK_MAH = 1.0
    }
}
