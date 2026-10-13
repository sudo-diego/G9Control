package io.github.sudodiego.g9control

import android.content.Context
import android.provider.Settings

/**
 * Proporción por juego con la función "Gaming view" (WideVision) de ZUI: el sistema dibuja esa app con otra
 * proporción, centrada y con franjas, sin cambiar la resolución de toda la tablet (a diferencia del 16:9 global con
 * `wm size`). Game Assistant solo la ofrece para tres juegos chinos, pero el sistema la aplica a cualquier app
 * (probado con RetroArch). Son dos ajustes:
 * - `Settings.Global zui_ultra_wide_<paquete con _>` = "<posición>:<proporción>" (1 = centrada; vacío = completa).
 *   TBF lo escribe con el permiso de pantalla (WRITE_SECURE_SETTINGS) o, si no, con Shizuku.
 * - `Settings.System enable_wide_field_packages` = los paquetes con proporción, separados por ":". Es una clave
 *   privada: una app no puede escribirla, va con Shizuku.
 * Se aplica al volver a abrir el juego (Android lo dibuja así desde que arranca).
 */
object GameAspect {
    enum class Ratio(val value: String?) {
        FULL(null), R16_9("1.78"), R4_3("1.33");
        val label get() = when (this) { FULL -> tr("Completa", "Full"); R16_9 -> "16:9"; R4_3 -> "4:3" }
        fun next() = entries[(ordinal + 1) % entries.size]
    }

    private const val LIST = "enable_wide_field_packages"
    private fun key(pkg: String) = "zui_ultra_wide_" + pkg.replace('.', '_')

    fun get(ctx: Context, pkg: String): Ratio {
        val v = runCatching { Settings.Global.getString(ctx.contentResolver, key(pkg)) }.getOrNull()?.substringAfter(':', "")
        return Ratio.entries.firstOrNull { it.value != null && it.value == v } ?: Ratio.FULL
    }

    /** Apps con proporción propia ahora (para la lista en Pantalla). */
    fun apps(ctx: Context): List<String> = runCatching { Settings.System.getString(ctx.contentResolver, LIST) }.getOrNull()
        ?.split(':')?.filter { it.isNotBlank() && get(ctx, it) != Ratio.FULL }.orEmpty()

    /** False si no se pudo (sin Shizuku para la lista). */
    suspend fun set(ctx: Context, pkg: String, r: Ratio): Boolean {
        if (!PKG.matches(pkg)) return false
        val value = r.value?.let { "1:$it" } ?: ""
        val cr = ctx.contentResolver
        val globalOk = runCatching { Settings.Global.putString(cr, key(pkg), value) }.isSuccess ||
            Shell.run(Shell.wideSet(key(pkg), value)) != null
        val list = runCatching { Settings.System.getString(cr, LIST) }.getOrNull()?.split(':')?.filter { it.isNotBlank() }.orEmpty().toMutableList()
        list.remove(pkg)
        if (r != Ratio.FULL) list.add(pkg)
        val joined = list.joinToString(":")
        val listOk = runCatching { Settings.System.putString(cr, LIST, joined) }.isSuccess ||
            Shell.run(Shell.wideList(joined)) != null
        return globalOk && listOk
    }

    private val PKG = Regex("[A-Za-z0-9_.]+")
}
