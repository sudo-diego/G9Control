package io.github.sudodiego.g9control

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Frecuencia de la pantalla por juego: un juego a 60 FPS se ve igual a 60 Hz (y gasta menos), y RetroArch con
 * inserción de cuadros negros quiere 120. Se fija con los ajustes del sistema `peak_refresh_rate` y
 * `min_refresh_rate` iguales (probado: 60 → 60 Hz, 165 → 165 Hz). Son privados del sistema: una app no los puede
 * escribir (ni con WRITE_SECURE_SETTINGS), así que va con Shizuku. La elección se guarda por app; al pasar a una app
 * sin elección se vuelve a lo que había antes (guardado al aplicar la primera vez).
 */
object RefreshRate {
    private const val PEAK = "peak_refresh_rate"
    private const val MIN = "min_refresh_rate"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE)

    fun canWrite() = Shell.state == Shell.State.READY

    /** Hasta el máximo del sistema (120) va sin Shizuku (con la ventana); más (144, 165) hay que subir el máximo. */
    fun available(ctx: Context, hz: Int) = hz <= systemPeak(ctx) || canWrite()

    /** Frecuencias que admite la pantalla (30 sirve para juegos a 30 FPS). */
    fun supported(ctx: Context): List<Int> =
        display(ctx)?.supportedModes?.map { it.refreshRate.roundToInt() }?.filter { it >= 30 }?.distinct()?.sorted().orEmpty()

    private fun display(ctx: Context) = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** Hz de la pantalla ahora. */
    fun current(ctx: Context): Int? = display(ctx)?.refreshRate?.roundToInt()

    /** La elección para una app (null = la del sistema). */
    fun forApp(ctx: Context, pkg: String?): Int? = pkg?.let { sp(ctx).getInt("hz_$it", 0).takeIf { v -> v > 0 } }
    fun setForApp(ctx: Context, pkg: String, hz: Int?) =
        sp(ctx).edit().apply { if (hz == null) remove("hz_$pkg") else putInt("hz_$pkg", hz) }.apply()

    /**
     * Para todos los juegos sin elección propia (null = la del sistema). Sin esto, en un juego el sistema elige
     * dentro de 30–120 según lo que dibuja el juego: en Ahorro la CPU va lenta, el juego dibuja menos y baja a 30.
     */
    fun forGames(ctx: Context): Int? = sp(ctx).getInt("hz_games", 0).takeIf { it > 0 }
    fun setForGames(ctx: Context, hz: Int?) = sp(ctx).edit().putInt("hz_games", hz ?: 0).apply()

    /** Lo que toca para esa app: su elección, o la de los juegos si es un juego. */
    fun wanted(ctx: Context, pkg: String?): Int? = forApp(ctx, pkg) ?: forGames(ctx)?.takeIf { ZuiGame.inGame(ctx) }

    /**
     * Aplica la frecuencia de esa app (al cambiar de app, cuando ZUI dice que es un juego, o al elegirla en el
     * panel). Sin elección, vuelve a lo que había antes de la primera vez que G9 Control la cambió. [done] se llama
     * al terminar (en otro hilo).
     */
    fun applyFor(ctx: Context, pkg: String?, done: () -> Unit = {}) {
        val want = wanted(ctx, pkg)
        val p = sp(ctx)
        // Sin Shizuku (o sin pasar del máximo del sistema): una ventana invisible del servicio que pide ese modo de
        // pantalla, como hace un reproductor de video. No cambia ningún ajuste; se quita y vuelve todo solo.
        val peak = systemPeak(ctx)
        val byWindow = want != null && (want <= peak || !canWrite())
        main.post { if (byWindow) window(ctx, want!!) else window(ctx, null) }
        if (byWindow) Log.d(AutoModeService.TAG, "pantalla a $want Hz con la ventana ($pkg)")
        if ((want == null || byWindow) && !p.contains("hz_saved_peak")) return done()
        val cr = ctx.contentResolver
        scope.launch {
            if (!canWrite()) return@launch done()
            if (want != null && !byWindow) {
                // Lo del usuario se guarda una sola vez (mientras G9 Control tenga la frecuencia cambiada).
                if (!p.contains("hz_saved_peak")) p.edit()
                    .putFloat("hz_saved_peak", Settings.System.getFloat(cr, PEAK, 120f))
                    .putFloat("hz_saved_min", Settings.System.getFloat(cr, MIN, 30f)).commit()
                Shell.run(Shell.refreshRate(PEAK, want.toFloat()))
                Shell.run(Shell.refreshRate(MIN, want.toFloat()))
                Log.d(AutoModeService.TAG, "pantalla a $want Hz ($pkg)")
            } else {
                Shell.run(Shell.refreshRate(PEAK, p.getFloat("hz_saved_peak", 120f)))
                Shell.run(Shell.refreshRate(MIN, p.getFloat("hz_saved_min", 30f)))
                p.edit().remove("hz_saved_peak").remove("hz_saved_min").commit()
                Log.d(AutoModeService.TAG, "pantalla a su frecuencia de siempre")
            }
            done()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var hzView: android.view.View? = null

    /**
     * La ventana que pide la frecuencia: 1 px transparente que no se toca, de tipo superposición de accesibilidad
     * (solo desde el servicio). Pide el modo de pantalla con esa frecuencia y la resolución actual. null la quita.
     */
    private fun window(ctx: Context, hz: Int?) {
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        if (hz == null || ctx !is android.accessibilityservice.AccessibilityService) {
            hzView?.let { runCatching { wm.removeView(it) } }
            hzView = null
            return
        }
        val d = display(ctx) ?: return
        val cur = d.mode
        val mode = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .minByOrNull { kotlin.math.abs(it.refreshRate - hz) } ?: return
        val lp = (hzView?.layoutParams as? android.view.WindowManager.LayoutParams) ?: android.view.WindowManager.LayoutParams(
            1, 1, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        lp.preferredDisplayModeId = mode.modeId
        val v = hzView
        if (v == null) {
            val nv = android.view.View(ctx)
            if (runCatching { wm.addView(nv, lp) }.isSuccess) hzView = nv
        } else runCatching { wm.updateViewLayout(v, lp) }
    }

    /** Lo que tiene el sistema sin G9 Control (para mostrar "Sistema · 120 Hz"). */
    fun systemPeak(ctx: Context): Int {
        val p = sp(ctx)
        return (if (p.contains("hz_saved_peak")) p.getFloat("hz_saved_peak", 120f)
        else runCatching { Settings.System.getFloat(ctx.contentResolver, PEAK) }.getOrDefault(120f)).roundToInt()
    }
}
