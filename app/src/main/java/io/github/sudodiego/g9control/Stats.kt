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

    /** Temperatura de la batería (°C); null si no se sabe. */
    fun batteryTemp(ctx: Context): Float? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
}