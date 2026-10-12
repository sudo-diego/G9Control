package io.github.sudodiego.g9control

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Frecuencia de la pantalla por juego: un juego a 60 FPS se ve igual a 60 Hz (y gasta menos), y RetroArch con
 * inserción de cuadros negros quiere 120. Sin Shizuku ni ajustes: una ventana invisible del servicio pide ese modo de
 * pantalla con mínimo y máximo iguales, como un reproductor de video (votos APP_REQUEST del sistema). Probado: 60 Hz y
 * 165 Hz con el máximo del sistema en 120, y se mantiene con el juego quieto. Al quitar la ventana todo vuelve solo.
 * La elección se guarda por app.
 *
 * Antes se cambiaban `peak/min_refresh_rate` con Shizuku: si quedaron cambiados (hz_saved_*), se devuelven una vez.
 */
object RefreshRate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE)

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
     * panel); sin elección quita la ventana. [done] se llama al terminar.
     */
    fun applyFor(ctx: Context, pkg: String?, done: () -> Unit = {}) {
        val want = wanted(ctx, pkg)
        main.post { window(ctx, want); done() }
        if (want != null) Log.d(AutoModeService.TAG, "pantalla a $want Hz ($pkg)")
        main.removeCallbacks(verify)
        // Si se había forzado con los ajustes del sistema (por Ahorro de energía), se devuelven al cambiar de app o de Hz.
        if (forced != pkg to want) restoreSettings(ctx)
        verifyFor = want?.let { w -> { check(ctx, pkg, w) } }
        if (want != null) main.postDelayed(verify, 2000)
    }

    /**
     * La pantalla cambió de frecuencia (lo avisa el servicio): si hay una elegida y no es esa, se revisa en 1,5 s. Pasa
     * al cambiar a Ahorro dentro del juego: ZUI baja a 30 Hz después de que la app ya lo había revisado.
     */
    fun onDisplayChanged() {
        if (verifyFor == null) return
        main.removeCallbacks(verify)
        main.postDelayed(verify, 1500)
    }

    /**
     * En Ahorro de energía ZUI le impone 30 Hz al juego con la misma prioridad que la ventana, y gana. Solo ahí (la
     * pantalla no quedó como se pidió) y con Shizuku: mínimo y máximo del sistema iguales, que tienen más prioridad.
     */
    private var verifyFor: (() -> Unit)? = null
    /** App y Hz forzados con los ajustes del sistema (para no devolverlos mientras sigan siendo los mismos). */
    private var forced: Pair<String?, Int?>? = null
    private val verify = Runnable { verifyFor?.invoke() }
    private fun check(ctx: Context, pkg: String?, want: Int) {
        val now = current(ctx) ?: return
        if (kotlin.math.abs(now - want) <= 1 || Shell.state != Shell.State.READY) return
        val p = sp(ctx)
        val cr = ctx.contentResolver
        scope.launch {
            if (!p.contains("hz_saved_peak")) p.edit()
                .putFloat("hz_saved_peak", android.provider.Settings.System.getFloat(cr, "peak_refresh_rate", 120f))
                .putFloat("hz_saved_min", android.provider.Settings.System.getFloat(cr, "min_refresh_rate", 30f)).commit()
            Shell.run(Shell.refreshRate("peak_refresh_rate", want.toFloat()))
            Shell.run(Shell.refreshRate("min_refresh_rate", want.toFloat()))
            forced = pkg to want
            Log.d(AutoModeService.TAG, "pantalla en $now Hz en vez de $want ($pkg): forzada con los ajustes del sistema")
        }
    }

    /** Devuelve los ajustes del sistema si quedaron cambiados (cuando se pueda). */
    private fun restoreSettings(ctx: Context) {
        val p = sp(ctx)
        if (!p.contains("hz_saved_peak") || Shell.state != Shell.State.READY) return
        scope.launch {
            Shell.run(Shell.refreshRate("peak_refresh_rate", p.getFloat("hz_saved_peak", 120f)))
            Shell.run(Shell.refreshRate("min_refresh_rate", p.getFloat("hz_saved_min", 30f)))
            p.edit().remove("hz_saved_peak").remove("hz_saved_min").commit()
            forced = null
            Log.d(AutoModeService.TAG, "frecuencia del sistema devuelta a la de siempre")
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
        // El modo solo es la base: dentro del rango 30–120 el sistema igual baja si el juego dibuja poco. Mínimo y
        // máximo iguales lo fijan (campos ocultos del SDK; si Android no los deja, queda solo la base).
        runCatching {
            val c = android.view.WindowManager.LayoutParams::class.java
            c.getField("preferredMinDisplayRefreshRate").setFloat(lp, mode.refreshRate)
            c.getField("preferredMaxDisplayRefreshRate").setFloat(lp, mode.refreshRate)
        }.onFailure { Log.w(AutoModeService.TAG, "frecuencia: sin rango mínimo/máximo ($it)") }
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
        else runCatching { android.provider.Settings.System.getFloat(ctx.contentResolver, "peak_refresh_rate") }.getOrDefault(120f)).roundToInt()
    }
}
