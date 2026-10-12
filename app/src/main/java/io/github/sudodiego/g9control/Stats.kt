package io.github.sudodiego.g9control

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Datos rápidos de la tablet, sin permisos: los usan el Inicio de Tablet y el mini monitor del panel. */
object Stats {
    /** El núcleo más rápido ahora (MHz); null si no se puede leer. */
    fun cpuMhz(): Int? = (0..9).mapNotNull { i ->
        runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 }.getOrNull()
    }.maxOrNull()

    fun cpuText(mhz: Int?) = when {
        mhz == null -> "—"
        mhz >= 1000 -> "%.1f GHz".format(mhz / 1000f)
        else -> "$mhz MHz"
    }

    /** Zonas térmicas de CPU y GPU (se buscan una vez), como el monitor de rendimiento: lo más caliente de cada grupo. */
    private val zones: Map<String, List<java.io.File>> by lazy {
        java.io.File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { z ->
            val type = runCatching { java.io.File(z, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            when {
                type.startsWith("cpu-") || type.startsWith("cpuss-") -> "cpu"
                type.startsWith("gpuss-") -> "gpu"
                else -> null
            }?.let { it to java.io.File(z, "temp") }
        }.groupBy({ it.first }, { it.second })
    }

    /** La temperatura más alta de "cpu" o "gpu" (°C). */
    fun maxTemp(group: String): Int? =
        zones[group]?.mapNotNull { runCatching { it.readText().trim().toLong() }.getOrNull() }?.maxOrNull()?.let { (it / 1000).toInt() }

    /** Temperatura de la batería (°C); null si no se sabe. */
    fun batteryTemp(ctx: Context): Float? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
}