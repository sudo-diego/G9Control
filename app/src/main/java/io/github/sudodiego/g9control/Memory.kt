package io.github.sudodiego.g9control

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

/**
 * Memoria de la tablet: cuánta RAM queda y liberarla. Liberar cierra los procesos de las apps que quedaron en segundo
 * plano (`killBackgroundProcesses`, permiso normal): la que está en pantalla y las que tienen un servicio corriendo (un
 * video, una descarga) siguen, y su tarjeta en Recientes puede quedar (al abrirla vuelve a empezar). Android no deja a
 * una app ver qué procesos de otras están vivos, así que no se muestra una lista: sería solo "usadas hace poco".
 */
object Memory {
    data class Info(val freeBytes: Long, val totalBytes: Long)

    private fun am(ctx: Context) = ctx.getSystemService(ActivityManager::class.java)

    fun info(ctx: Context): Info {
        val m = ActivityManager.MemoryInfo().also { am(ctx).getMemoryInfo(it) }
        return Info(m.availMem, m.totalMem)
    }

    /** Apps que se pueden abrir desde el cajón, sin esta ni las pantallas de inicio (se volverían a abrir solas). */
    private fun closable(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.filter { it !in home && it != ctx.packageName }.toSet()
    }

    /** Cierra lo que quedó en segundo plano; devuelve cuánta memoria quedó libre de más (bytes, puede ser 0). */
    fun freeAll(ctx: Context): Long {
        val before = info(ctx).freeBytes
        closable(ctx).forEach { runCatching { am(ctx).killBackgroundProcesses(it) } }
        Thread.sleep(800)
        return (info(ctx).freeBytes - before).coerceAtLeast(0)
    }

    fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
}
