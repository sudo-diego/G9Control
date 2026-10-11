package io.github.sudodiego.g9control

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Process

/**
 * Memoria de la tablet: cuánta RAM queda, qué apps se usaron hace poco y cerrarlas. Cerrar es terminar el proceso de
 * una app que quedó en segundo plano (`killBackgroundProcesses`, permiso normal): la que está en pantalla no se toca, y
 * su tarjeta en Recientes puede seguir ahí (al abrirla vuelve a empezar). Saber cuáles se usaron necesita el "Acceso a
 * datos de uso", que el usuario da una vez en Ajustes de Android; sin él, "Liberar" cierra todas las que estén en
 * segundo plano.
 */
object Memory {
    data class Info(val freeBytes: Long, val totalBytes: Long)
    data class App(val pkg: String, val label: String, val icon: Drawable?)

    private fun am(ctx: Context) = ctx.getSystemService(ActivityManager::class.java)

    fun info(ctx: Context): Info {
        val m = ActivityManager.MemoryInfo().also { am(ctx).getMemoryInfo(it) }
        return Info(m.availMem, m.totalMem)
    }

    /** El usuario dio "Acceso a datos de uso". */
    fun usageAllowed(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun usageSettings() = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Las pantallas de inicio: no se cierran (se volverían a abrir solas). */
    private fun launchers(ctx: Context): Set<String> =
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()

    /** Apps que se pueden abrir desde el cajón (las que tiene sentido cerrar), sin esta ni las de inicio. */
    private fun closable(ctx: Context): Set<String> {
        val skip = launchers(ctx) + ctx.packageName
        return ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.filter { it !in skip }.toSet()
    }

    /**
     * Las usadas en las últimas horas, la más reciente primero (con el acceso a datos de uso). Android no deja ver qué
     * procesos de otras apps siguen vivos: una app cerrada aquí no vuelve a la lista hasta que se use de nuevo.
     */
    fun recent(ctx: Context, hours: Int = 6): List<App> {
        if (!usageAllowed(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val ok = closable(ctx)
        val stats = ctx.getSystemService(UsageStatsManager::class.java)
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - hours * 3_600_000L, now)
        val pm = ctx.packageManager
        return stats.filter { it.packageName in ok && it.lastTimeUsed > now - hours * 3_600_000L && it.totalTimeInForeground > 0 }
            .groupBy { it.packageName }.mapValues { (_, v) -> v.maxOf { it.lastTimeUsed } }
            .filter { (pkg, last) -> last > (closedAt[pkg] ?: 0L) }
            .entries.sortedByDescending { it.value }
            .mapNotNull { (pkg, _) ->
                runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { App(pkg, pm.getApplicationLabel(it).toString(), runCatching { pm.getApplicationIcon(it) }.getOrNull()) }
            }
    }

    /** Cuándo se cerró cada app desde aquí (mientras la app esté viva). */
    private val closedAt = mutableMapOf<String, Long>()

    /**
     * Cierra una app: con Shizuku de verdad (`am force-stop`, también si tiene un servicio corriendo, como un video o
     * Discord); sin él, solo sus procesos en segundo plano (lo que Android permite a una app). En otro hilo.
     */
    fun close(ctx: Context, pkg: String) {
        closedAt[pkg] = System.currentTimeMillis()
        if (Shell.state == Shell.State.READY) kotlinx.coroutines.runBlocking { Shell.run(Shell.forceStop(pkg)) }
        else runCatching { am(ctx).killBackgroundProcesses(pkg) }
    }

    /**
     * Liberar: las usadas hace poco (si se sabe cuáles; con Shizuku se cierran de verdad) y, sin Shizuku, también los
     * procesos en segundo plano de todas las demás. Devuelve cuántas apps se cerraron y cuánta memoria se liberó.
     */
    fun freeAll(ctx: Context): Pair<Int, Long> {
        val before = info(ctx).freeBytes
        val list = recent(ctx).map { it.pkg }
        list.forEach { close(ctx, it) }
        if (Shell.state != Shell.State.READY) (closable(ctx) - list.toSet()).forEach { runCatching { am(ctx).killBackgroundProcesses(it) } }
        Thread.sleep(800)
        return list.size to (info(ctx).freeBytes - before).coerceAtLeast(0)
    }

    fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
}
