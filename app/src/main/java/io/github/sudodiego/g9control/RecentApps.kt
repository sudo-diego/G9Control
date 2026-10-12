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

    fun push(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName || pkg == ZuiGame.PACKAGE || pkg.startsWith("com.android.systemui")) return
        val home = homes ?: ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet().also { homes = it }
        if (pkg in home || ctx.packageManager.getLaunchIntentForPackage(pkg) == null) return
        val list = (listOf(pkg) + list(ctx).filter { it != pkg }).take(MAX)
        sp(ctx).edit().putString("list", list.joinToString(",")).apply()
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

    /** Volver a la app: su tarea al frente, como estaba. */
    fun open(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
        }
    }
}
