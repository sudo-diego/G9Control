package io.github.sudodiego.g9control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sesiones de juego, para comparar modos de rendimiento y Hz con datos de verdad: mientras ZUI está en modo juego, cada
 * 5 s (lo llama [PowerLog]) se suman los W (solo a batería: enchufada se mide la carga), la frecuencia de la CPU y las
 * temperaturas máximas de CPU, GPU y batería. Cada tramo es una app con un modo y unos Hz: si cambian a mitad del juego
 * empieza otro, así el mismo juego se compara en Ahorro, Equilibrado y Alto rendimiento. Se guardan los últimos 60
 * tramos de al menos un minuto.
 */
object Sessions {
    private const val STEP_S = 5
    private const val KEEP = 60

    data class Segment(
        val pkg: String, val mode: String?, val hz: Int?, val start: Long,
        val seconds: Int, val batterySeconds: Int, val wh: Double,
        val cpuMhzSum: Long, val samples: Int, val cpuMax: Int?, val gpuMax: Int?, val batMax: Int?,
    ) {
        val avgWatts get() = if (batterySeconds > 0) wh * 3600 / batterySeconds else null
        val avgCpuMhz get() = if (samples > 0) (cpuMhzSum / samples).toInt() else null
    }

    private var current: Segment? = null

    /** Una muestra (cada 5 s); [watts] null si está enchufada. Sin juego cierra el tramo que hubiera. */
    fun sample(ctx: Context, app: String?, watts: Double?, screenOn: Boolean) {
        val game = screenOn && app != null && ZuiGame.inGame(ctx)
        if (!game) { close(ctx); return }
        val mode = ZuiGame.knownPerf(app)?.name
        val hz = RefreshRate.current(ctx)
        var s = current
        if (s == null || s.pkg != app || s.mode != mode || s.hz != hz) {
            close(ctx)
            s = Segment(app!!, mode, hz, System.currentTimeMillis(), 0, 0, 0.0, 0, 0, null, null, null)
        }
        val cpu = Stats.cpuMhz()
        fun max(a: Int?, b: Int?) = listOfNotNull(a, b).maxOrNull()
        current = s.copy(
            seconds = s.seconds + STEP_S,
            batterySeconds = s.batterySeconds + if (watts != null) STEP_S else 0,
            wh = s.wh + (watts ?: 0.0) * STEP_S / 3600.0,
            cpuMhzSum = s.cpuMhzSum + (cpu ?: 0), samples = s.samples + if (cpu != null) 1 else 0,
            cpuMax = max(s.cpuMax, Stats.maxTemp("cpu")), gpuMax = max(s.gpuMax, Stats.maxTemp("gpu")),
            batMax = max(s.batMax, Stats.batteryTemp(ctx)?.toInt()),
        )
    }

    /** Cierra el tramo en curso (se guarda si duró al menos un minuto). */
    fun close(ctx: Context) {
        val s = current ?: return
        current = null
        if (s.seconds < 60) return
        val list = (listOf(s) + load(ctx)).take(KEEP)
        save(ctx, list)
    }

    fun list(ctx: Context): List<Segment> = listOfNotNull(current?.takeIf { it.seconds >= 60 }) + load(ctx)

    /** Por modo de rendimiento: tiempo total, W promedio (ponderado por tiempo a batería), CPU y temperatura máxima media. */
    data class ByMode(val mode: String?, val seconds: Int, val avgWatts: Double?, val avgCpuMhz: Int?, val avgCpuMax: Int?)

    fun byMode(ctx: Context): List<ByMode> = list(ctx).groupBy { it.mode }.map { (mode, segs) ->
        val bat = segs.sumOf { it.batterySeconds }
        val samples = segs.sumOf { it.samples }
        ByMode(mode, segs.sumOf { it.seconds }, if (bat > 0) segs.sumOf { it.wh } * 3600 / bat else null,
            if (samples > 0) (segs.sumOf { it.cpuMhzSum } / samples).toInt() else null,
            segs.mapNotNull { it.cpuMax }.takeIf { it.isNotEmpty() }?.average()?.toInt())
    }.sortedBy { it.mode }

    fun reset(ctx: Context) { current = null; save(ctx, emptyList()) }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    private fun load(ctx: Context): List<Segment> = runCatching {
        val a = JSONArray(sp(ctx).getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun intOrNull(k: String) = if (o.has(k)) o.getInt(k) else null
            Segment(o.getString("pkg"), o.optString("mode").takeIf { it.isNotEmpty() }, intOrNull("hz"), o.getLong("start"),
                o.getInt("s"), o.getInt("bs"), o.getDouble("wh"), o.getLong("cpuSum"), o.getInt("n"),
                intOrNull("cpuMax"), intOrNull("gpuMax"), intOrNull("batMax"))
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Segment>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("pkg", s.pkg).put("mode", s.mode ?: "").put("start", s.start).put("s", s.seconds)
                .put("bs", s.batterySeconds).put("wh", s.wh).put("cpuSum", s.cpuMhzSum).put("n", s.samples)
                .apply { s.hz?.let { put("hz", it) }; s.cpuMax?.let { put("cpuMax", it) }; s.gpuMax?.let { put("gpuMax", it) }; s.batMax?.let { put("batMax", it) } })
        }
        sp(ctx).edit().putString("list", a.toString()).apply()
    }
}
