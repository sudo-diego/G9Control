package io.github.sudodiego.g9control

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

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

    /** Las pantallas de inicio (Cocoon, la de ZUI): no se cierran ni se detienen. */
    private fun home(ctx: Context) = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }.toSet()

    /** Apps que se pueden abrir desde el cajón, sin esta ni las pantallas de inicio (se volverían a abrir solas). */
    private fun closable(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = home(ctx)
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

    data class App(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    /**
     * Apps activas (con Shizuku): las de usuario con un servicio en primer plano, como "Apps activas" de los ajustes
     * rápidos. Sale de `dumpsys activity processes` (las líneas "Proc #… FGS … pid:paquete/u0a…"). Sin esta app.
     */
    suspend fun activeApps(ctx: Context): List<App> {
        val out = Shell.run(Shell.PROCESSES) ?: return emptyList()
        val re = Regex("""Proc #\s*\d+:.*/(?:FGS|BFGS)\s.*\s\d+:([A-Za-z0-9_.]+)(?::[\w.]+)?/u\d+a\d+""")
        val pm = ctx.packageManager
        val launchers = home(ctx)
        return out.lineSequence().mapNotNull { re.find(it)?.groupValues?.get(1) }
            .filter { it != ctx.packageName }.distinct()
            .filter { it !in launchers }
            .mapNotNull { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                // Solo apps que se abren desde el cajón (no servicios del sistema ni de Google, que no conviene detener),
                // y Game Assistant, que no tiene ícono pero Android también lo muestra en sus apps activas.
                if (pm.getLaunchIntentForPackage(pkg) == null && pkg != ZuiGame.PACKAGE) return@mapNotNull null
                App(pkg, pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai) }.getOrNull())
            }.toList()
    }

    /** Detener una app, como el botón Detener de Android (la app y sus servicios). Con Shizuku. */
    suspend fun stop(ctx: Context, pkg: String): Boolean {
        val ok = Shell.run(Shell.stopApp(pkg)) != null
        if (ok) hist(ctx).edit().putLong("stop_$pkg", System.currentTimeMillis()).apply()
        return ok
    }

    /**
     * Apps que vuelven solas: al detenerla se anota cuándo; si después se la ve corriendo (en la lista o en la
     * revisión del servicio, cada 30 s con `pidof`), se guarda que volvió y cuánto tardó. Queda guardado: la marca
     * sigue aunque se cierre la app o pasen días. Se revisa hasta 6 h después de detenerla.
     */
    data class Return(val count: Int, val lastDelayMs: Long)

    private fun hist(ctx: Context) = ctx.getSharedPreferences("memory_history", Context.MODE_PRIVATE).also { h ->
        // La primera versión contaba también las que abrías tú: ese historial no sirve.
        if (h.getInt("v", 0) < 2) h.edit().clear().putInt("v", 2).apply()
    }

    /** La abriste tú (estuvo al frente) después de detenerla: si después está corriendo, no volvió sola. */
    fun openedByUser(ctx: Context, pkg: String) {
        val h = hist(ctx)
        if (h.contains("stop_$pkg")) h.edit().remove("stop_$pkg").apply()
    }

    fun returnOf(ctx: Context, pkg: String): Return? = hist(ctx).getString("ret_$pkg", null)?.split('|')?.let {
        Return(it[0].toInt(), it[1].toLong())
    }

    /** Todas las que volvieron alguna vez (paquete → cuántas veces y cuánto tardó la última). */
    fun returners(ctx: Context): Map<String, Return> = hist(ctx).all.keys.filter { it.startsWith("ret_") }
        .mapNotNull { k -> k.removePrefix("ret_").let { p -> returnOf(ctx, p)?.let { p to it } } }.toMap()

    fun clearHistory(ctx: Context) = hist(ctx).edit().clear().apply()

    /** Una app detenida desde aquí está corriendo otra vez: anotarlo. */
    private fun sawRunning(ctx: Context, pkg: String) {
        val h = hist(ctx)
        val at = h.getLong("stop_$pkg", 0).takeIf { it > 0 } ?: return
        val delay = System.currentTimeMillis() - at
        val prev = returnOf(ctx, pkg)
        h.edit().remove("stop_$pkg").putString("ret_$pkg", "${(prev?.count ?: 0) + 1}|$delay").apply()
    }

    private var checking = false

    /** Desde el servicio (cada 30 s): las detenidas hace menos de 6 h, ¿volvieron? Solo con Shizuku y si hay alguna. */
    fun checkReturns(ctx: Context) {
        if (checking || Shell.state != Shell.State.READY) return
        val h = hist(ctx)
        val now = System.currentTimeMillis()
        val pending = h.all.filterKeys { it.startsWith("stop_") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("stop_") to it } }
        if (pending.isEmpty()) return
        pending.filter { now - it.second > 6 * 3_600_000L }.forEach { h.edit().remove("stop_${it.first}").apply() }
        checking = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                pending.filter { now - it.second <= 6 * 3_600_000L }.forEach { (pkg, _) ->
                    if (!Shell.run(Shell.pidof(pkg)).isNullOrBlank()) sawRunning(ctx, pkg)
                }
            } finally { checking = false }
        }
    }

    /** Una app corriendo: cuánta RAM usa (MB, todos sus procesos), cuánta CPU (% de un núcleo) y si está activa. */
    data class Task(val app: App, val ramMb: Int, val cpu: Float, val active: Boolean)

    /**
     * Administrador de tareas (con Shizuku): las apps de usuario que están corriendo, la que más RAM usa primero. Sale
     * de `top` (la segunda pasada, que trae la CPU) sumando los procesos de cada app (paquete y paquete:algo).
     */
    suspend fun tasks(ctx: Context): List<Task> {
        val out = Shell.run(Shell.TOP) ?: return emptyList()
        val blocks = out.split(Regex("""(?m)^\s*Tasks:"""))
        val last = blocks.lastOrNull() ?: return emptyList()
        val row = Regex("""^\s*(u\d+_a\d+)\s+([\d.]+)\s+([\d.]+)([KMG])\s+(\S+)""")
        val ram = mutableMapOf<String, Double>()
        val cpu = mutableMapOf<String, Double>()
        last.lineSequence().forEach { line ->
            val m = row.find(line) ?: return@forEach
            val (_, c, size, unit, name) = m.destructured
            val pkg = name.substringBefore(':')
            val mb = size.toDouble() * when (unit) { "G" -> 1024.0; "K" -> 1 / 1024.0; else -> 1.0 }
            ram[pkg] = (ram[pkg] ?: 0.0) + mb
            cpu[pkg] = (cpu[pkg] ?: 0.0) + c.toDouble()
        }
        ram.keys.forEach { sawRunning(ctx, it) }
        val active = activeApps(ctx).map { it.pkg }.toSet()
        val pm = ctx.packageManager
        val launchers = home(ctx)
        return ram.keys.filter { it != ctx.packageName && it !in launchers }.mapNotNull { pkg ->
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            if (pm.getLaunchIntentForPackage(pkg) == null && pkg != ZuiGame.PACKAGE) return@mapNotNull null
            Task(App(pkg, pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai) }.getOrNull()),
                ram.getValue(pkg).toInt(), cpu.getValue(pkg).toFloat(), pkg in active)
        }.sortedByDescending { it.ramMb }
    }
}
