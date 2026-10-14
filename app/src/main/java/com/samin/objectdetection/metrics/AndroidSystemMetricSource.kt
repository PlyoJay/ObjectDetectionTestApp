package com.samin.objectdetection.metrics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Public APIs + best-effort read-only sysfs. No new permissions or privileged commands. */
class AndroidSystemMetricSource(context: Context) : SystemMetricSource {
    private val applicationContext = context.applicationContext
    private val power by lazy { runCatching { applicationContext.getSystemService(PowerManager::class.java) }.getOrNull() }
    private val frequencies = CpuFrequencyReader()

    override fun thermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null

    override fun thermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val manager = power ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        // Keep the limit across rapid stop/start and Activity recreation as well as within a session.
        val before = lastHeadroomCallNs.get()
        if (before != Long.MIN_VALUE && now - before < SystemMetricsReader.HEADROOM_MIN_INTERVAL_NS) return null
        if (!lastHeadroomCallNs.compareAndSet(before, now)) return null
        return manager.getThermalHeadroom(0)
    }

    override fun processCpuTimeMs(): Long = Process.getElapsedCpuTime()
    override fun cpuFrequency(): String? = frequencies.read()

    override fun listenThermal(executor: Executor, onStatus: (Int) -> Unit): AutoCloseable? {
        if (Build.VERSION.SDK_INT < 29) return null
        val manager = power ?: return null
        val listener = PowerManager.OnThermalStatusChangedListener(onStatus)
        manager.addThermalStatusListener(executor, listener)
        return AutoCloseable { manager.removeThermalStatusListener(listener) }
    }

    companion object { private val lastHeadroomCallNs = AtomicLong(Long.MIN_VALUE) }
}

/** policy sysfs is device/system scope, not a frequency attributed to our process.
 * Discover once on the diagnostics worker; cache denied attributes for this session.
 * scaling_cur_freq is a driver-reported policy value, not a measured effective clock. */
class CpuFrequencyReader(
    private val root: File = File("/sys/devices/system/cpu"),
    private val readAttribute: (File) -> String? = { file -> file.bufferedReader().use { it.readLine() } }
) {
    private var directories: List<File>? = null
    private val denied = mutableSetOf<String>()

    fun read(): String? {
        val policies = directories ?: discover().also { directories = it }
        val values = policies.mapNotNull { directory ->
            val scaling = positive(directory, "scaling_cur_freq")
            val hardware = if (scaling == null) positive(directory, "cpuinfo_cur_freq") else null
            val current = scaling ?: hardware
            val maximum = positive(directory, "scaling_max_freq")
            val hardwareMaximum = positive(directory, "cpuinfo_max_freq")
            val related = text(directory, "related_cpus")
            val governor = text(directory, "scaling_governor")
            if (current == null && maximum == null && hardwareMaximum == null && related == null && governor == null) null
            else "${directory.parentFile?.name?.takeIf { directory.name == "cpufreq" } ?: directory.name}" +
                ":currentKHz=${current ?: "N/A"},source=${if (scaling != null) "scaling_cur_freq" else if (hardware != null) "cpuinfo_cur_freq" else "N/A"}" +
                ",scalingMaxKHz=${maximum ?: "N/A"},cpuinfoMaxKHz=${hardwareMaximum ?: "N/A"}" +
                ",relatedCpus=${related ?: "N/A"},governor=${governor ?: "N/A"}"
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString(";")
    }

    private fun discover(): List<File> = try {
        val policies = File(root, "cpufreq").listFiles()?.filter { it.isDirectory && it.name.matches(Regex("policy\\d+")) }.orEmpty()
        (policies.ifEmpty {
            root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("cpu\\d+")) }
                ?.map { File(it, "cpufreq") }.orEmpty()
        }).sortedBy { it.absolutePath }.take(64)
    } catch (_: Exception) { emptyList() }

    private fun positive(directory: File, name: String) = text(directory, name)?.toLongOrNull()?.takeIf { it > 0 }
    private fun text(directory: File, name: String): String? {
        val file = File(directory, name)
        if (file.absolutePath in denied) return null
        return try { readAttribute(file)?.trim()?.take(256)?.replace(Regex("[\\r\\n|]"), " ")?.takeIf { it.isNotBlank() } }
        catch (_: Exception) { denied += file.absolutePath; null }
    }
}
