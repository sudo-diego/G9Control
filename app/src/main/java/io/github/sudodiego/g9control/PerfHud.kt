package io.github.sudodiego.g9control

import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Monitor de rendimiento sobre el juego: una barra compacta (FPS, CPU, GPU, batería) en la esquina elegida. Se
 * actualiza cada segundo, fuera del hilo principal. Casi todo se lee sin permisos (las reglas de SELinux de las
 * apps dejan leer `gpubusy`, las frecuencias de la CPU y las zonas térmicas, aunque el shell no pueda): CPU = la
 * frecuencia más alta y la temperatura más alta de sus núcleos; GPU = % ocupado y su temperatura más alta. Los FPS
 * de otra app solo los sabe SurfaceFlinger: con Shizuku, de los tiempos de los últimos cuadros de la capa del juego.
 * Dos estilos: la barra (una o dos líneas) o la lista, como MangoHUD (una fila por dato y, con FPS, el gráfico de
 * los tiempos de cuadro: cada tirón es un pico). No recibe toques.
 */
class PerfHud(private val svc: AutoModeService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null
    private var view: TextView? = null
    private var graph: FrameGraph? = null

    val isOn get() = view != null

    /** Según la opción: encendido o apagado (también al cambiar la posición). */
    fun update() {
        hide()
        if (AutoRules.perfHud(svc)) show()
    }

    private fun show() {
        val dp = svc.resources.displayMetrics.density
        val v = TextView(svc).apply {
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Las dos líneas centradas una sobre la otra (si no, la corta quedaba cargada a un lado).
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(3 * dp, 1f)
            setTextColor(android.graphics.Color.WHITE)
            text = "…"
        }
        val list = AutoRules.perfList(svc)
        if (list) { v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); v.gravity = Gravity.START; v.textSize = 12f }
        val g = FrameGraph(svc, dp).apply { visibility = android.view.View.GONE }
        // La barra (o la lista) y, debajo, el gráfico de tiempos de cuadro (solo en la lista y con FPS).
        val box = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xB80E1424.toInt()); cornerRadius = 12 * dp }
            addView(v)
            addView(g, android.widget.LinearLayout.LayoutParams((150 * dp).toInt(), (30 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
        }
        val pos = AutoRules.perfPos(svc)
        // La ventana ocupa todo el ancho (transparente, no recibe toques) y la barra va adentro a un lado: así la
        // ventana no cambia de tamaño cuando cambia el texto (ZUI animaba ese cambio como un deslizamiento).
        val frame = android.widget.FrameLayout(svc).apply {
            addView(box, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                when (pos.h) { -1 -> Gravity.START; 1 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
            ).apply { marginStart = (12 * dp).toInt(); marginEnd = (12 * dp).toInt() })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (pos.v) { -1 -> Gravity.TOP; 1 -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
            y = (12 * dp).toInt()
            windowAnimations = android.R.style.Animation_Toast   // aparece y se va desvaneciéndose
        }
        if (runCatching { wm.addView(frame, lp) }.onFailure { Log.w(AutoModeService.TAG, "monitor de rendimiento", it) }.isFailure) return
        root = frame
        view = v
        graph = g
        listStyle = list
        task = worker.scheduleWithFixedDelay({ runCatching { sample() }.onFailure { Log.w(AutoModeService.TAG, "monitor: lectura", it) } },
            0, 1, TimeUnit.SECONDS)
    }

    fun hide() {
        task?.cancel(false)
        task = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        view = null
        graph = null
    }

    /** El estilo con que se armó (si cambia en Ajustes, se rearma). */
    private var listStyle = false


    /** La ventana (todo el ancho) que contiene la barra. */
    private var root: android.view.View? = null

    fun release() { hide(); worker.shutdownNow() }

    // ——— Lecturas ———

    private val cpus = (0..15).map { File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }.filter { it.exists() }
    private val gpuBusy = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
    private var lastGpu: Int? = null
    /** Zonas térmicas por grupo (se buscan una vez): núcleos de CPU y GPU. */
    private val zones: Map<String, List<File>> by lazy {
        val all = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }.orEmpty()
        all.mapNotNull { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val group = when {
                type.startsWith("cpu-") || type.startsWith("cpuss-") -> "cpu"
                type.startsWith("gpuss-") -> "gpu"
                else -> return@mapNotNull null
            }
            group to File(z, "temp")
        }.groupBy({ it.first }, { it.second })
    }

    private fun readLong(f: File) = runCatching { f.readText().trim().toLong() }.getOrNull()
    private fun maxTemp(group: String) = zones[group]?.mapNotNull { readLong(it) }?.maxOrNull()?.let { (it / 1000).toInt() }

    private fun sample() {
        if (AutoRules.perfList(svc) != listStyle) { main.post { update() }; return }
        val cpuGhz = cpus.mapNotNull { readLong(it) }.maxOrNull()?.let { it / 1_000_000.0 }
        // "ocupado total" desde la lectura anterior: si el contador se acaba de reiniciar (total 0) no hay dato nuevo y
        // se mantiene el último (antes salía un 0 % falso).
        val gpu = runCatching {
            val (busy, total) = gpuBusy.readText().trim().split(Regex("\\s+")).map { it.toLong() }
            if (total > 0) (busy * 100 / total).toInt().coerceIn(0, 100).also { lastGpu = it } else lastGpu
        }.getOrNull()
        val bat = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        val batTemp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let { it / 10 }
        val fps = fps()

        val sb = SpannableStringBuilder()
        fun colored(s: String, color: Int) { val st = sb.length; sb.append(s); sb.setSpan(ForegroundColorSpan(color), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun label(s: String) = colored(s, ACCENT)
        fun sep() = colored("  │  ", MUTED)
        /** Temperatura en color: blanca, amarilla desde [warm], roja desde [hot]. */
        fun temp(t: Int?, warm: Int, hot: Int) { t?.let { colored("  $it°", if (it >= hot) HOT else if (it >= warm) WARM else WHITE) } }
        val charging0 = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING
        val plugged0 = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        // Lista (MangoHUD): una fila por dato, la etiqueta en una columna fija.
        if (listStyle) {
            fun row(name: String) { if (sb.isNotEmpty()) sb.append("\n"); label(name.padEnd(5)) }
            if (fps != null) { row("FPS"); sb.append("%3d".format(fps)); colored("  %.1f ms".format(frameMs ?: (1000.0 / fps.coerceAtLeast(1))), MUTED) }
            row("CPU"); sb.append(cpuGhz?.let { "%.1f GHz".format(it) } ?: "—"); temp(maxTemp("cpu"), 75, 90)
            row("GPU"); sb.append(gpu?.let { "%3d %%".format(it) } ?: "—"); temp(maxTemp("gpu"), 70, 85)
            if (AutoRules.perfFull(svc)) { row("RAM"); sb.append(ramFree()) }
            row("BAT"); sb.append(level?.let { "%3d %%".format(it) } ?: "—"); temp(batTemp, 40, 45)
            if (AutoRules.perfFull(svc)) {
                if (!(plugged0 && !charging0)) watts(bat)?.let { colored("  " + (if (charging0) "+" else "−") + "%.1f W".format(it), MUTED) }
                if (!(plugged0 && !charging0)) remaining(bat, charging0)?.let { row(""); colored(it, MUTED) }
                hz()?.let { row("Hz"); sb.append("$it") }
                thermal()?.let { if (sb.isNotEmpty()) sb.append("\n"); colored(it, HOT) }
            }
            val times = frameTimes
            main.post {
                view?.text = sb
                graph?.let { g -> if (fps != null && times.size > 2) { g.visibility = android.view.View.VISIBLE; g.set(times) } else g.visibility = android.view.View.GONE }
            }
            return
        }
        if (fps != null) { sb.append("$fps "); label("FPS"); sep() }
        label("CPU "); sb.append(cpuGhz?.let { "%.1f GHz".format(it) } ?: "—"); temp(maxTemp("cpu"), 75, 90)
        sep()
        label("GPU "); sb.append(gpu?.let { "$it %" } ?: "—"); temp(maxTemp("gpu"), 70, 85)
        sep()
        // Batería como en el panel: el ícono con su nivel, con rayo cargando y con enchufe si está enchufada sin cargar.
        val charging = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val icon = BatteryIcon(level ?: 0, charging, plugged && !charging, svc.resources.displayMetrics.density)
        val st = sb.length
        sb.append("  ")
        sb.setSpan(android.text.style.ImageSpan(icon, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(level?.let { "$it %" } ?: "—"); temp(batTemp, 40, 45)

        // Para medir consumo (por ejemplo 60 contra 120 Hz) desde el PC: una línea por segundo en el registro.
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val mv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (ua != Int.MIN_VALUE && mv > 0) Log.d("G9Power", "w=%.3f hz=%s plugged=%b".format(kotlin.math.abs(ua.toDouble()) / 1e6 * mv / 1e3, hz(), plugged))

        // Completa: segunda línea con la energía, el tiempo, los Hz y el calor de la tablet.
        if (AutoRules.perfFull(svc)) {
            sb.append("\n")
            val watts = watts(bat)
            // Enchufada sin cargar (bypass o límite): la batería casi no da ni recibe nada, los watts y el tiempo
            // saldrían absurdos ("48 h"); el enchufe del ícono ya lo dice.
            if (!(plugged && !charging)) {
                if (watts != null) { sb.append(if (charging) "+" else ""); sb.append("%.1f ".format(watts)); label("W"); sep() }
                remaining(bat, charging)?.let { sb.append(it); sep() }
            }
            hz()?.let { sb.append("$it "); label("Hz") }
            // Android ya baja (o va a bajar) el rendimiento por calor, mida lo que mida la ROM.
            thermal()?.let { sep(); colored(it, HOT) }
        }
        main.post { view?.text = sb }
    }

    private val bm = svc.getSystemService(BatteryManager::class.java)
    /** Corriente suavizada (µA, positiva), para que los watts y el tiempo no salten de un segundo al otro. */
    private var currentAvg = 0.0

    /** Potencia de la batería en W: lo que sale (o entra, cargando). Null si Android no da la corriente. */
    private fun watts(bat: Intent?): Double? {
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE && it != 0 } ?: return null
        val mv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 } ?: return null
        val a = kotlin.math.abs(ua.toDouble())
        currentAvg = if (currentAvg == 0.0) a else currentAvg * 0.8 + a * 0.2
        return currentAvg / 1e6 * mv / 1e3
    }

    /** Tiempo que queda de batería, "~2 h 10 min", o para cargar hasta el límite (o 100), "⚡ 40 min → 80 %". */
    private fun remaining(bat: Intent?, charging: Boolean): String? {
        if (charging) {
            val ms = ZuiBattery.chargeTimeLeft(svc) ?: return null
            val target = ZuiBattery.chargeTarget(svc)
            return "⚡ " + ZuiBattery.formatTime(ms) + if (target < 100) " → $target %" else ""
        }
        val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 } ?: return null
        if (currentAvg < 50_000) return null   // casi sin consumo (enchufada en bypass): no tiene sentido
        return "~" + ZuiBattery.formatTime((uah / currentAvg * 3_600_000).toLong())
    }

    /** Hz de la pantalla ahora. */
    private fun hz(): Int? = runCatching {
        svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).refreshRate
    }.getOrNull()?.let { Math.round(it) }

    /**
     * El estado térmico de Android (no la batería: lo deciden la CPU y el cuerpo de la tablet; en Cemu o Eden la CPU
     * llega a ~95 °C con la batería a 39 °C). Moderado: se está calentando; desde severo, Android baja la CPU.
     */
    private fun thermal(): String? = when (runCatching { svc.getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrDefault(0)) {
        android.os.PowerManager.THERMAL_STATUS_MODERATE -> tr("Calentándose", "Warming up")
        android.os.PowerManager.THERMAL_STATUS_SEVERE, android.os.PowerManager.THERMAL_STATUS_CRITICAL,
        android.os.PowerManager.THERMAL_STATUS_EMERGENCY, android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> tr("CPU limitada por calor", "CPU throttled by heat")
        else -> null
    }

    // ——— FPS (con Shizuku) ———

    /** Tiempos entre los últimos cuadros (ms) y su promedio. */
    @Volatile private var frameTimes: List<Float> = emptyList()
    @Volatile private var frameMs: Double? = null

    /** RAM libre, "5.1 GB". */
    private fun ramFree(): String {
        val mi = android.app.ActivityManager.MemoryInfo()
        svc.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return "%.1f GB".format(mi.availMem / 1e9)
    }

    /**
     * Gráfico de tiempos de cuadro, como el de MangoHUD: una línea por cuadro; plana es fluido, cada pico es un tirón.
     * La escala va de 0 a 33 ms (30 FPS) o al pico más alto.
     */
    private class FrameGraph(ctx: android.content.Context, private val dp: Float) : android.view.View(ctx) {
        private var times: List<Float> = emptyList()
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT; strokeWidth = 1.6f * dp; style = android.graphics.Paint.Style.STROKE
        }
        private val guide = android.graphics.Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f * dp }
        fun set(t: List<Float>) { times = t.takeLast(120); invalidate() }
        override fun onDraw(c: android.graphics.Canvas) {
            if (times.size < 2) return
            val w = width.toFloat(); val h = height.toFloat()
            val top = maxOf(33.3f, times.max())
            // Línea guía en 16,7 ms (60 FPS).
            val g = h - 16.7f / top * h
            c.drawLine(0f, g, w, g, guide)
            val path = android.graphics.Path()
            times.forEachIndexed { i, ms ->
                val x = i * w / (times.size - 1); val y = h - (ms / top).coerceIn(0f, 1f) * (h - 2 * dp) - dp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, line)
        }
    }

    /** La capa del juego (se busca de nuevo al cambiar de app o si deja de responder). */
    private var layerApp: String? = null
    private var layer: String? = null
    private var layerAt = 0L

    /**
     * Cuadros presentados en el último segundo por la capa de la app al frente; null sin Shizuku. (Los FPS de la
     * pantalla del driver, `/sys/class/drm/sde-crtc-0/measured_fps`, SELinux no se los deja leer a las apps.)
     */
    private fun fps(): Int? {
        if (Shell.state != Shell.State.READY) return null
        val app = svc.foregroundApp ?: return null
        val now = System.nanoTime()
        if (app != layerApp || layer == null || now - layerAt > 10_000_000_000L) {
            layerApp = app
            layerAt = now
            layer = findLayer(app)
        }
        val name = layer ?: return null
        val presented = latency(name) ?: return null
        // La capa ya no existe o no dibuja: se busca otra en la próxima.
        if (presented.isEmpty()) { layer = null; return null }
        // Tiempos entre cuadros (ms) de los últimos ~2 s, para el gráfico y el promedio.
        val sorted = presented.filter { it > now - 2_000_000_000L }.sorted()
        frameTimes = sorted.zipWithNext { a, b -> (b - a) / 1e6f }.filter { it in 0.5f..500f }
        frameMs = frameTimes.takeIf { it.isNotEmpty() }?.average()
        return presented.count { it > now - 1_000_000_000L }
    }

    /**
     * La capa que dibuja el juego. En Android 15 `--list` da "RequestedLayerState{[id] nombre parentId=…}": la app
     * tiene varias (la de su actividad, la de los toques, la ventana y, debajo, la que recibe los cuadros; o una
     * SurfaceView). Se prueban las suyas y gana la que tenga el cuadro más reciente.
     */
    private fun findLayer(app: String): String? {
        val out = runBlocking { Shell.run(Shell.SF_LIST) } ?: return null
        val names = out.lines().map { it.trim() }
            .filter { app in it && "ActivityRecord" !in it && "InputSink" !in it && "Surface(name=" !in it && "Background" !in it }
            .map { it.removePrefix("RequestedLayerState{").removeSuffix("}").split(" parentId=", " z=").first().split(' ').last() }
            .distinct().takeLast(8)
        val pick = names.maxByOrNull { lastFrame(it) ?: 0L }?.takeIf { (lastFrame(it) ?: 0L) > 0 }
        Log.d(AutoModeService.TAG, "monitor: capa de $app = $pick (de ${names.size})")
        return pick
    }

    /** Cuándo presentó su último cuadro una capa (ns), o null si no tiene. */
    private fun lastFrame(layer: String): Long? = latency(layer)?.maxOrNull()

    /** Tiempos de presentación de los últimos cuadros de una capa (sin los pendientes). */
    private fun latency(layer: String): List<Long>? {
        val out = runBlocking { Shell.run(Shell.sfLatency(layer)) } ?: return null
        // Primera línea: período de refresco; después "pedido presentado listo" en ns (CLOCK_MONOTONIC, como nanoTime).
        return out.lines().drop(1).mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() }
            .filter { it in 1 until Long.MAX_VALUE }
    }

    /**
     * Ícono de batería (el mismo dibujo que el encabezado del panel): contorno y relleno según el nivel; con un rayo
     * cargando, y un enchufe al lado si está enchufada sin cargar (bypass o límite de carga).
     */
    private class BatteryIcon(val pct: Int, val charging: Boolean, val plugged: Boolean, dp: Float) : android.graphics.drawable.Drawable() {
        private val d = dp
        private val bodyW = 22 * dp
        private val h = 11 * dp
        private val plugW = if (plugged) 14 * dp else 0f
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        init { setBounds(0, 0, (plugW + bodyW + 3 * dp).toInt(), h.toInt()) }

        override fun draw(c: android.graphics.Canvas) {
            val x0 = plugW
            if (plugged) {
                // Enchufe: dos patas y el cuerpo, en celeste.
                paint.color = ACCENT; paint.style = android.graphics.Paint.Style.FILL
                c.drawRect(2 * d, 2 * d, 3.5f * d, 4.5f * d, paint)
                c.drawRect(6 * d, 2 * d, 7.5f * d, 4.5f * d, paint)
                c.drawRoundRect(1 * d, 4.5f * d, 8.5f * d, 8 * d, 1.5f * d, 1.5f * d, paint)
                c.drawRect(4 * d, 8 * d, 5.5f * d, 11 * d, paint)
            }
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.4f * d; paint.color = 0xFFB4BCC8.toInt()
            c.drawRoundRect(x0 + 0.7f * d, 0.7f * d, x0 + bodyW - 0.7f * d, h - 0.7f * d, 2.5f * d, 2.5f * d, paint)
            paint.style = android.graphics.Paint.Style.FILL
            c.drawRoundRect(x0 + bodyW, h * 0.3f, x0 + bodyW + 2.2f * d, h * 0.7f, 1f * d, 1f * d, paint)
            val pad = 2.4f * d
            val w = (bodyW - 2 * pad) * pct.coerceIn(0, 100) / 100f
            paint.color = if (pct <= 15 && !charging && !plugged) HOT else if (charging || plugged) ACCENT else WHITE
            c.drawRoundRect(x0 + pad, pad, x0 + pad + w, h - pad, 1.2f * d, 1.2f * d, paint)
            if (charging) {
                val cx = x0 + bodyW / 2
                val bolt = android.graphics.Path().apply {
                    moveTo(cx + h * 0.10f, h * 0.12f); lineTo(cx - h * 0.22f, h * 0.56f); lineTo(cx - h * 0.02f, h * 0.56f)
                    lineTo(cx - h * 0.10f, h * 0.88f); lineTo(cx + h * 0.22f, h * 0.44f); lineTo(cx + h * 0.02f, h * 0.44f); close()
                }
                paint.color = WHITE; c.drawPath(bolt, paint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** El acento del panel (en celeste, el de siempre). */
        val ACCENT get() = ThemeState.panelAccent.let { a -> if (a.base == null) 0xFF7DD8F5.toInt() else a.color(false).let { android.graphics.Color.argb(it.alpha, it.red, it.green, it.blue) } }
        val MUTED = 0xFF6B7480.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val WARM = 0xFFFFC857.toInt()
        val HOT = 0xFFFF6B6B.toInt()
    }
}
