package io.github.sudodiego.g9control

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings

/**
 * Lo que la ROM de la tablet (ZUI) deja ver de su Game Assistant sin permisos especiales.
 *
 * Mientras ZUI está en modo juego, Game Assistant guarda en `Settings.System` dónde está su pestaña
 * lateral (`lado#arriba#abajo`, lado 1 izquierda y 2 derecha, en píxeles). Fuera de un juego vale
 * `0#0#0`. Sirve para saber si ZUI tomó la app como juego y para abrir su panel con un gesto.
 */
object ZuiGame {
    const val PACKAGE = "com.zui.game.service"
    private const val KEY = "game_helper_side_bar"
    private const val MODE_KEY = "zui_playing_mode"
    private const val LAST_KEY = "zui_last_side_bar"
    private const val SETTINGS_ACTIVITY = "com.zui.ugame.gamesetting.ui.options.OptionActivity"

    /** Pestaña lateral de Game Assistant (solo existe en modo juego). */
    data class SideBar(val right: Boolean, val top: Int, val bottom: Int) {
        val centerY get() = (top + bottom) / 2f
    }

    /**
     * "Disable navigation bar and gesture" de Game Assistant (Ajustes → Game Assistant): en sus juegos, la barra de
     * navegación y los gestos de deslizar no hacen nada. Es un ajuste global; se escribe con WRITE_SECURE_SETTINGS.
     */
    private const val GESTURE_LOCK = "key_game_assistant_prevent_misoperation"
    fun gesturesLocked(ctx: Context) = runCatching { Settings.Global.getInt(ctx.contentResolver, GESTURE_LOCK) == 1 }.getOrDefault(false)
    fun setGesturesLocked(ctx: Context, on: Boolean) =
        runCatching { Settings.Global.putInt(ctx.contentResolver, GESTURE_LOCK, if (on) 1 else 0) }.isSuccess

    fun installed(ctx: Context) =
        runCatching { ctx.packageManager.getApplicationInfo(PACKAGE, 0).enabled }.getOrDefault(false)

    /**
     * ZUI está en modo juego: `Settings.Global zui_playing_mode` = "GameMode" (vacío fuera de un juego). Es la
     * señal confiable: la pestaña ([sideBar]) a veces no se publica con Game Assistant oculto.
     */
    fun inGame(ctx: Context): Boolean =
        runCatching { Settings.Global.getString(ctx.contentResolver, MODE_KEY) }.getOrNull() == "GameMode" || sideBar(ctx) != null

    /** Pestaña actual, o null si ZUI no está en modo juego o no la publicó (puede pasar con Game Assistant oculto). */
    fun sideBar(ctx: Context): SideBar? {
        val raw = runCatching { Settings.System.getString(ctx.contentResolver, KEY) }.getOrNull()
        val bar = parse(raw) ?: return null
        // Se recuerda la última (solo si cambió: se consulta seguido).
        val sp = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE)
        if (sp.getString(LAST_KEY, null) != raw) sp.edit().putString(LAST_KEY, raw).apply()
        return bar
    }

    /** La pestaña actual o, si no está publicada, la última que se vio (Game Assistant la recuerda por app). */
    fun sideBarOrLast(ctx: Context): SideBar? =
        sideBar(ctx) ?: parse(ctx.getSharedPreferences("g9", Context.MODE_PRIVATE).getString(LAST_KEY, null))

    /** Espera a que Game Assistant publique su pestaña (al mostrarlo); si no llega, la última que se vio. */
    suspend fun awaitSideBar(ctx: Context): SideBar? {
        repeat(15) { sideBar(ctx)?.let { return it }; kotlinx.coroutines.delay(100) }
        return sideBarOrLast(ctx)
    }

    fun parse(v: String?): SideBar? {
        val p = v?.split('#')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (p.size != 3 || p[0] !in 1..2 || p[2] <= p[1]) return null
        return SideBar(p[0] == 2, p[1], p[2])
    }

    /** Avisa cada vez que ZUI entra o sale del modo juego. Devuelve el observador para soltarlo después. */
    fun observe(ctx: Context, handler: Handler, onChange: (SideBar?) -> Unit): ContentObserver {
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onChange(sideBar(ctx))
        }
        ctx.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY), false, o)
        ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(MODE_KEY), false, o)
        return o
    }

    fun unobserve(ctx: Context, o: ContentObserver) = runCatching { ctx.contentResolver.unregisterContentObserver(o) }

    /**
     * Modos de rendimiento de Game Assistant, con el número que escribe en su registro
     * (`ZuiGameHelper writeSavageMode::open::1, status::N`). Ninguna app ni shell puede leerlos ni
     * cambiarlos directamente (el proveedor de com.zui.pp exige permiso de firma): solo su panel.
     */
    enum class PerfMode(val status: Int) {
        SAVER(1), BALANCED(0), BEAST(2);
        val label get() = when (this) { SAVER -> tr("Ahorro de energía", "Power saving"); BALANCED -> tr("Equilibrado", "Balanced"); BEAST -> tr("Alto rendimiento", "High performance") }
    }

    /** Última línea del registro de Game Assistant que dice el modo (o null si ya no está en el registro). */
    suspend fun lastPerfLine(): String? =
        Shell.run(Shell.GA_LOG)?.lineSequence()?.lastOrNull { it.contains("writeSavageMode") }

    fun perfOf(line: String?): PerfMode? {
        val n = line?.substringAfterLast("status::", "")?.trim()?.toIntOrNull() ?: return null
        return PerfMode.entries.firstOrNull { it.status == n }
    }

    /** Game Assistant oculto con appops (true), visible (false) o no se sabe (sin Shizuku). Actualiza [hiddenNow]. */
    suspend fun hidden(): Boolean? =
        Shell.run(Shell.GA_GET)?.let { it.contains("ignore") || it.contains("deny") }?.also { hiddenNow = it }

    /**
     * Si está oculto, según lo último que se supo (al leerlo o al mostrarlo u ocultarlo desde G9 Control). Para que
     * el panel de juego arme sus ítems al instante, sin esperar a Shizuku (si no, aparecían un momento después).
     */
    @Volatile var hiddenNow: Boolean? = null
        private set

    /** Muestra u oculta Game Assistant (appops, con Shizuku). False si no se pudo. */
    suspend fun setHidden(hide: Boolean): Boolean =
        (Shell.run(if (hide) Shell.GA_HIDE else Shell.GA_SHOW) != null).also { if (it) hiddenNow = hide }

    /**
     * Modo de rendimiento por app, según lo último que se supo: el submenú lo marca al instante. Se guarda (no solo
     * en memoria): el registro de Android se recicla y la línea de Game Assistant puede ya no estar.
     */
    private var perfPrefs: android.content.SharedPreferences? = null
    fun initPerf(ctx: Context) { if (perfPrefs == null) perfPrefs = ctx.getSharedPreferences("g9_perf", Context.MODE_PRIVATE) }
    fun knownPerf(app: String?): PerfMode? =
        app?.let { perfPrefs?.getString(it, null) }?.let { runCatching { PerfMode.valueOf(it) }.getOrNull() }
    fun rememberPerf(app: String?, mode: PerfMode?) {
        if (app != null && mode != null && knownPerf(app) != mode) perfPrefs?.edit()?.putString(app, mode.name)?.apply()
    }

    /**
     * Botón del modo de rendimiento en el panel de Game Assistant: un solo botón que pasa de Equilibrado a
     * Bestia, a Ahorro y vuelve. Medido en la Y700 (2560 × 1600 en horizontal): a 714 px del borde de la
     * pestaña y a media altura.
     */
    fun perfButton(bar: SideBar, width: Int, height: Int): Pair<Float, Float> =
        (if (bar.right) width - 714f else 714f) to height / 2f + 1

    /** Abre los ajustes de Game Assistant (la pantalla está abierta a otras apps). */
    fun openSettings(ctx: Context) = runCatching {
        ctx.startActivity(Intent().setClassName(PACKAGE, SETTINGS_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
