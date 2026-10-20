package com.samin.objectdetection.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class AndroidBatteryMetricSource(context: Context) {
    private val app = context.applicationContext
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val manager by lazy { safe { app.getSystemService(BatteryManager::class.java) } }

    fun read(): BatteryReading {
        // Null receiver reads the sticky snapshot without registering a callback or retaining an Activity.
        val intent = safe { app.registerReceiver(null, filter) }
        fun extra(key: String): Int? = intent?.takeIf { it.hasExtra(key) }?.getIntExtra(key, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
        val status = when (extra(BatteryManager.EXTRA_STATUS)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NotCharging"
            else -> "Unknown"
        }
        val plugged = extra(BatteryManager.EXTRA_PLUGGED)?.let { value ->
            if (value < 0) "Unknown" else if (value == 0) "None" else buildList {
                if (value and BatteryManager.BATTERY_PLUGGED_USB != 0) add("USB")
                if (value and BatteryManager.BATTERY_PLUGGED_AC != 0) add("AC")
                if (value and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) add("Wireless")
                if (value and BatteryManager.BATTERY_PLUGGED_DOCK != 0) add("Dock")
                if (value and 15.inv() != 0) add("Unknown")
            }.joinToString("+")
        } ?: "Unknown"
        val present = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_PRESENT) }
            ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        if (present == false) return BatteryReading(chargingStatus = status, pluggedType = plugged)
        // Read each property once. Keep the exact API result, including unsupported sentinels.
        val currentNowRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAverageRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        return BatteryReading(
            BatteryUnits.levelPct(extra(BatteryManager.EXTRA_LEVEL), extra(BatteryManager.EXTRA_SCALE)),
            BatteryUnits.temperatureC(extra(BatteryManager.EXTRA_TEMPERATURE)),
            extra(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it in 1..100_000 },
            BatteryUnits.currentMa(currentNowRaw),
            BatteryUnits.currentMa(currentAverageRaw),
            BatteryUnits.chargeUah(property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)),
            BatteryUnits.energyNwh(safe { manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }),
            status, plugged, currentNowRaw, currentAverageRaw
        )
    }

    private fun property(id: Int): Int? = safe { manager?.getIntProperty(id) }
    private fun <T> safe(block: () -> T?): T? = try { block() } catch (_: Exception) { null } catch (_: LinkageError) { null }
}
