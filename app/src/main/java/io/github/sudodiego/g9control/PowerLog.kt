package io.github.sudodiego.g9control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/**
 * Consumo de la tablet: lo de ahora fluctúa mucho (una escena pesada sube, un menú baja), así que además se guarda
 * un promedio corto (el último minuto) y uno por app: cada 5 s, con la pantalla encendida y a batería (enchufada lo
 * que se mide es la carga, no el consumo), lo que se gasta se suma a la app al frente. Lo lleva el servicio de
 * accesibilidad, que es el que sabe qué app está al frente; se guarda cada minuto.
 */
object PowerLog {
    private const val STEP_MS = 5_000L
    private const val RECENT = 12            // 12 × 5 s = el último minuto

    /** Lo de una app: energía sumada (Wh) y tiempo medido (s). */
    data class Usage(val pkg: String, val wh: Double, val seconds: Long) {
        val avgWatts get() = if (seconds > 0) wh * 3600 / seconds else 0.0
    }

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private val usage = mutableMapOf<String, Usage>()
    private val recent = ArrayDeque<Double>()
    private var ticks = 0

    /** Promedio del último minuto a batería (null si todavía no hay muestras o está enchufada). */
    val recentAvg: Double? get() = synchronized(recent) { recent.takeIf { it.size >= 3 }?.average() }

    fun start(svc: AutoModeService) {
        if (ctx != null) return
        ctx = svc.applicationContext
        load(svc)
        main.postDelayed(object : Runnable {
            override fun run() {
                val c = ctx ?: return
                sample(c, svc.foregroundApp)
                if (++ticks % 12 == 0) save(c)
                if (ticks % 6 == 0) Memory.checkReturns(c)
                main.postDelayed(this, STEP_MS)
            }
        }, STEP_MS)
    }

    fun stop() { ctx?.let { save(it); Sessions.close(it) }; ctx = null; main.removeCallbacksAndMessages(null) }

    private fun sample(c: Context, app: String?) {
        val screenOn = c.getSystemService(PowerManager::class.java).isInteractive
        val plugged = ZuiBattery.plugged(c)
        // Sesiones de juego (modos y Hz): también enchufada, para las temperaturas; los W solo a batería.
        Sessions.sample(c, app, if (plugged) null else ZuiBattery.watts(c), screenOn)
        if (!screenOn || plugged) { synchronized(recent) { recent.clear() }; return }
        val w = ZuiBattery.watts(c) ?: return
        synchronized(recent) { recent.addLast(w); while (recent.size > RECENT) recent.removeFirst() }
        if (app == null) return
        synchronized(usage) {
            val u = usage[app] ?: Usage(app, 0.0, 0)
            usage[app] = Usage(app, u.wh + w * STEP_MS / 3_600_000.0, u.seconds + STEP_MS / 1000)
        }
    }

    /** Las apps medidas, la que más tiempo tiene primero (con al menos un minuto medido). */
    fun apps(c: Context): List<Usage> {
        if (ctx == null) load(c)
        return synchronized(usage) { usage.values.filter { it.seconds >= 60 }.sortedByDescending { it.seconds } }
    }

    fun of(pkg: String?): Usage? = pkg?.let { synchronized(usage) { usage[it] } }?.takeIf { it.seconds >= 60 }

    fun reset(c: Context) {
        synchronized(usage) { usage.clear() }
        save(c)
    }

    private fun sp(c: Context) = c.getSharedPreferences("power_log", Context.MODE_PRIVATE)

    private fun load(c: Context) {
        val json = runCatching { JSONObject(sp(c).getString("apps", "{}")!!) }.getOrNull() ?: return
        synchronized(usage) {
            usage.clear()
            json.keys().forEach { k ->
                val o = json.getJSONObject(k)
                usage[k] = Usage(k, o.optDouble("wh", 0.0), o.optLong("s", 0))
            }
        }
    }

    private fun save(c: Context) {
        val json = JSONObject()
        synchronized(usage) { usage.values.forEach { json.put(it.pkg, JSONObject().put("wh", it.wh).put("s", it.seconds)) } }
        sp(c).edit().putString("apps", json.toString()).apply()
    }
}
