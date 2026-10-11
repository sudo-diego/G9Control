package io.github.sudodiego.g9control

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Bypass charging: el cargador alimenta la tablet y la batería no se carga (no se calienta jugando enchufado). Es lo
 * mismo que hace Game Assistant en la Y700 Gen 3: el servicio de batería de ZUI (`zuibattery`,
 * `setBypassChargingStatus`). Ese servicio solo acepta apps del sistema (o una app de pruebas de Lenovo y SystemUI):
 * G9 Control no puede, pero shell sí (com.android.shell es del sistema), así que va con Shizuku.
 */
object ZuiBattery {
    /** Lo último que se supo (al leerlo o cambiarlo): el panel arma sus ítems al instante. */
    @Volatile var bypassNow: Boolean? = null
        private set

    /** Lee el bypass (null sin Shizuku). La respuesta de `service call` es `Parcel(00000000 0000000X ...)`. */
    suspend fun readBypass(): Boolean? =
        Shell.run(Shell.BYPASS_GET)?.let { parse(it) }?.also { bypassNow = it }

    /**
     * El usuario lo cambió a mano: el bypass automático (y el límite de carga) no lo tocan hasta desconectar el
     * cargador (si no, lo volverían a cambiar en el siguiente aviso de la batería). Se guarda: dura aunque se cambie
     * de app o el servicio se reinicie (al actualizar la app).
     */
    @Volatile var manualOverride = false
        set(v) { field = v; prefs?.edit()?.putBoolean("bypass_manual", v)?.apply() }
    private var prefs: android.content.SharedPreferences? = null

    /** Al arrancar el servicio: la elección manual que había, si sigue enchufada. */
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE)
        manualOverride = plugged(ctx) && prefs!!.getBoolean("bypass_manual", false)
    }

    /** El bypass encendido lo puso el automático (o el límite propio), no el usuario: para mostrarlo en el panel. */
    @Volatile var byAuto = false
        private set

    /** Volver al automático desde el panel: lo que esté ahora pasa a ser suyo (lo apaga si ya no corresponde). */
    fun returnToAuto() { manualOverride = false; byAuto = bypassNow == true }

    suspend fun setBypass(on: Boolean, manual: Boolean = true): Boolean {
        val ok = Shell.run(if (on) Shell.BYPASS_ON else Shell.BYPASS_OFF)?.let { parse(it) } == true
        if (ok) { bypassNow = on; byAuto = on && !manual; if (manual) manualOverride = true }
        Log.d(AutoModeService.TAG, "bypass ${if (on) "activado" else "desactivado"}: $ok")
        return ok
    }

    /**
     * Límite de carga de ZUI ("Maximum charge limit" en Ajustes → Batería → Charge optimization): el nivel (80 %) o
     * null si está apagado o no se sabe. Con el límite, al llegar ZUI deja de cargar y no vuelve hasta bajar de ~95 %
     * de ese nivel; Android dice "no carga" y la barra muestra un enchufe (no es el bypass).
     */
    suspend fun readChargeLimit(): Int? {
        val on = Shell.run(Shell.LIMIT_GET)?.let { parse(it) } ?: return null
        val level = if (on) Shell.run(Shell.LIMIT_LEVEL)?.let { parseInt(it) }?.takeIf { it in 1..99 } else null
        // Se recuerda (0 = apagado) para el tiempo de carga cuando Shizuku no está.
        prefs?.edit()?.putInt("zui_limit", level ?: 0)?.apply()
        return level
    }

    /**
     * Hasta dónde va a cargar: el límite propio, el de ZUI (lo último que se leyó) o 100. El tiempo que falta se
     * calcula hasta ahí: hasta 100 lo da Android; hasta un límite, con lo que entra ahora y la capacidad de la batería.
     */
    fun chargeTarget(ctx: Context): Int {
        val zui = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE).getInt("zui_limit", 0).takeIf { it in 1..99 }
        return listOfNotNull(ownLimit(ctx), zui, 100).min()
    }

    /** Milisegundos para llegar a [chargeTarget] cargando; null si no se sabe o ya llegó. */
    fun chargeTimeLeft(ctx: Context): Long? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val target = chargeTarget(ctx)
        if (level < 0 || level >= target) return null
        val bm = ctx.getSystemService(BatteryManager::class.java)
        if (target >= 100) bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { return it }
        val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 } ?: return null
        // La corriente promedio de esta tablet da casi 0 (y el tiempo quedaba vacío): la de ahora.
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != 0 && it != Int.MIN_VALUE } ?: return null
        val current = kotlin.math.abs(ua.toDouble()).takeIf { it > 50_000 } ?: return null
        val capacity = uah * 100.0 / level.coerceAtLeast(1)
        return ((target - level) / 100.0 * capacity / current * 3_600_000).toLong()
    }

    /** Lo que entra o sale de la batería ahora, en W (positivo). Null si Android no da la corriente. */
    fun watts(ctx: Context): Double? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val ua = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != 0 && it != Int.MIN_VALUE } ?: return null
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).takeIf { it > 0 } ?: return null
        return kotlin.math.abs(ua.toDouble()) / 1e6 * mv / 1e3
    }

    /** Lo que dura la batería al consumo de ahora (ms); null si casi no consume (enchufada en bypass) o no se sabe. */
    fun dischargeTimeLeft(ctx: Context): Long? {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 } ?: return null
        // La corriente promedio de esta tablet no sirve (da casi 0): la de ahora.
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != 0 && it != Int.MIN_VALUE } ?: return null
        val current = kotlin.math.abs(ua.toDouble()).takeIf { it > 50_000 } ?: return null
        return (uah / current * 3_600_000).toLong()
    }

    /** "1 h 20 min" o "35 min". */
    fun formatTime(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    /** Niveles para el límite propio de G9 Control. */
    val LIMITS = listOf(70, 75, 80, 85, 90)

    /**
     * Límite de carga propio (null = apagado): al llegar a ese nivel enchufada se activa el bypass, así que la batería
     * deja de cargar y el cargador alimenta la tablet. El nivel del límite de ZUI no se puede cambiar: su servicio
     * acepta otro número (`setMaxBatteryChargingLevel`), pero el driver sigue cortando en 80 (probado).
     */
    fun ownLimit(ctx: Context): Int? =
        ctx.getSharedPreferences("g9", Context.MODE_PRIVATE).getInt("charge_limit", 0).takeIf { it in LIMITS }
    fun setOwnLimit(ctx: Context, level: Int?) =
        ctx.getSharedPreferences("g9", Context.MODE_PRIVATE).edit().putInt("charge_limit", level ?: 0).apply()

    private fun parseInt(out: String): Int? =
        Regex("Parcel\\(\\s*[0-9a-f]{8} ([0-9a-f]{8})").find(out)?.groupValues?.get(1)?.toLong(16)?.toInt()

    private fun parse(out: String): Boolean? = parseInt(out)?.let { it != 0 }

    /** La tablet está enchufada (el bypass solo tiene sentido así). */
    fun plugged(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
