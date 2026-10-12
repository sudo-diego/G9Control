package io.github.sudodiego.g9control

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Apps usadas hace poco, para volver a ellas desde el panel como a un juego en suspensión. Las anota el servicio al
 * cambiar de app (sabe cuál está al frente, sin permisos de uso): solo las que se abren desde el cajón, sin esta, las
 * pantallas de inicio ni Game Assistant. Volver abre su tarea donde quedó (no la reinicia).
 */
object RecentApps {
    private const val MAX = 8
    private fun sp(ctx: Context) = ctx.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
    private var homes: Set<String>? = null
    private val icons = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    /** Tarea de cada app reciente (la que tenía al frente): volver a ella es como en Recientes de Android. */
    private val tasks = mutableMapOf<String, Int>()
    private var tasksLoaded = false

    /** Se guardan (al reinstalar o reiniciar el servicio se perdían); una tarea vieja se descubre al usarla. */
    fun rememberTask(ctx: Context, pkg: String, taskId: Int?) {
        if (taskId == null || taskId <= 0 || tasks[pkg] == taskId) return
        tasks[pkg] = taskId
        sp(ctx).edit().putInt("task_$pkg", taskId).apply()
    }

    private fun taskFor(ctx: Context, pkg: String): Int? {
        if (!tasksLoaded) { tasksLoaded = true; sp(ctx).all.forEach { (k, v) -> if (k.startsWith("task_") && v is Int) tasks.putIfAbsent(k.removePrefix("task_"), v) } }
        return tasks[pkg]
    }

    fun push(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName || pkg == ZuiGame.PACKAGE || pkg.startsWith("com.android.systemui")) return
        val home = homes ?: ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet().also { homes = it }
        if (pkg in home || ctx.packageManager.getLaunchIntentForPackage(pkg) == null) return
        val list = (listOf(pkg) + list(ctx).filter { it != pkg }).take(MAX)
        sp(ctx).edit().putString("list", list.joinToString(",")).apply()
    }

    /** Quitarla de la lista (se cerró desde el panel). Vuelve a aparecer si se abre de nuevo. */
    fun forget(ctx: Context, pkg: String) {
        tasks.remove(pkg)
        sp(ctx).edit().putString("list", list(ctx).filter { it != pkg }.joinToString(",")).remove("task_$pkg").apply()
    }

    fun list(ctx: Context): List<String> = sp(ctx).getString("list", "")!!.split(',').filter { it.isNotBlank() }

    fun label(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Ícono de la app (se guarda: el panel lo pide cada vez que se abre). */
    fun icon(ctx: Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? = icons[pkg] ?: runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val s = (48 * ctx.resources.displayMetrics.density).toInt()
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(android.graphics.Canvas(b))
        b.asImageBitmap().also { icons[pkg] = it }
    }.getOrNull()

    /**
     * Volver a la app como en Recientes de Android: su tarea al frente tal como quedó (moveTaskToFront, permiso
     * REORDER_TASKS). Abrirla con la intención del cajón no sirve: si un launcher la abrió con otra (Cocoon abre Dolphin
     * con VIEW), Android vuelve a su pantalla principal o reinicia el juego. Solo si no se conoce la tarea, se abre.
     */
    fun open(ctx: Context, pkg: String) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val task = taskFor(ctx, pkg)
        if (task != null && runCatching { am.moveTaskToFront(task, 0) }.isSuccess) {
            android.util.Log.i(AutoModeService.TAG, "recientes: tarea $task de $pkg al frente")
            // Si la tarea ya no existía (la app se cerró), no pasa nada: se comprueba y se abre.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val front = (ctx as? AutoModeService)?.currentApp()
                if (front != null && front != pkg) { android.util.Log.i(AutoModeService.TAG, "recientes: la tarea $task ya no está, se abre $pkg"); launch(ctx, pkg) }
            }, 700)
            return
        }
        android.util.Log.i(AutoModeService.TAG, "recientes: $pkg sin tarea conocida, se abre")
        launch(ctx, pkg)
    }

    private fun launch(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
        }
    }

    /** El número de tarea de una ventana de app (campo oculto de AccessibilityWindowInfo). */
    fun taskOf(w: android.view.accessibility.AccessibilityWindowInfo): Int? =
        runCatching { w.javaClass.getMethod("getTaskId").invoke(w) as Int }.getOrNull()
}
