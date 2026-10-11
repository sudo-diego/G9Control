package io.github.sudodiego.g9control

import android.content.Context
import android.view.InputDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modos a los que la app puede cambiar el control desde Android. Desde X-Input o DS5 no se puede
 * volver por software (el control ya no expone el canal de configuración): solo con Start + Select.
 */
enum class Target(val label: String) { XINPUT("X-Input"), DS5("DS5") }

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE)

    /** Ya se vio la configuración inicial. */
    fun setupDone(ctx: Context) = sp(ctx).getBoolean("setup_done", false)
    fun setSetupDone(ctx: Context) = sp(ctx).edit().putBoolean("setup_done", true).apply()

    /** Abrir la app al conectar el control en modo Android (por defecto no). */
    /** Dibujo del control al centro (arriba, con lo demás debajo) en vez de en una columna. */
    fun theme(ctx: Context) = runCatching { AppTheme.valueOf(sp(ctx).getString("theme", null)!!) }.getOrDefault(AppTheme.SYSTEM)
    fun setTheme(ctx: Context, v: AppTheme) = sp(ctx).edit().putString("theme", v.name).apply()

    /** Fondo de la app: degradado (por defecto), una imagen o GIF elegido, o liso; y cuánto se desenfoca la imagen. */
    fun background(ctx: Context) = runCatching { AppBackground.valueOf(sp(ctx).getString("background", null)!!) }.getOrDefault(AppBackground.GRADIENT)
    fun setBackground(ctx: Context, v: AppBackground) = sp(ctx).edit().putString("background", v.name).apply()
    fun backgroundUri(ctx: Context) = sp(ctx).getString("background_uri", null)
    fun setBackgroundUri(ctx: Context, v: String?) = sp(ctx).edit().putString("background_uri", v).apply()
    fun backgroundLoop(ctx: Context) = sp(ctx).getBoolean("background_loop", true)
    fun setBackgroundLoop(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("background_loop", v).apply()
    fun backgroundBlur(ctx: Context) = sp(ctx).getInt("background_blur", 24)
    fun setBackgroundBlur(ctx: Context, v: Int) = sp(ctx).edit().putInt("background_blur", v).apply()
    fun backgroundDim(ctx: Context) = sp(ctx).getInt("background_dim", 45)
    fun setBackgroundDim(ctx: Context, v: Int) = sp(ctx).edit().putInt("background_dim", v).apply()

    /** Fondo del panel de juego: degradado (por defecto), una imagen o GIF propia, o liso. */
    fun panelBackground(ctx: Context) = runCatching { PanelBackground.valueOf(sp(ctx).getString("panel_bg", null)!!) }.getOrDefault(PanelBackground.GRADIENT)
    fun setPanelBackground(ctx: Context, v: PanelBackground) = sp(ctx).edit().putString("panel_bg", v.name).apply()
    fun panelUri(ctx: Context) = sp(ctx).getString("panel_uri", null)
    fun setPanelUri(ctx: Context, v: String?) = sp(ctx).edit().putString("panel_uri", v).apply()
    fun panelBlur(ctx: Context) = sp(ctx).getInt("panel_blur", 24)
    fun setPanelBlur(ctx: Context, v: Int) = sp(ctx).edit().putInt("panel_blur", v).apply()
    fun panelLoop(ctx: Context) = sp(ctx).getBoolean("panel_loop", true)
    /** Mini monitor en el panel (RAM, W, CPU y temperatura), siempre que se abre. */
    fun panelStats(ctx: Context) = sp(ctx).getBoolean("panel_stats", true)
    fun setPanelStats(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("panel_stats", v).apply()
    fun setPanelLoop(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("panel_loop", v).apply()
    fun panelFill(ctx: Context) = sp(ctx).getInt("panel_fill", 25)
    fun setPanelFill(ctx: Context, v: Int) = sp(ctx).edit().putInt("panel_fill", v).apply()
    fun panelSee(ctx: Context) = sp(ctx).getInt("panel_see", 0)
    fun panelShape(ctx: Context) = runCatching { PanelShape.valueOf(sp(ctx).getString("panel_shape", null)!!) }.getOrDefault(PanelShape.AUTO)
    fun setPanelShape(ctx: Context, v: PanelShape) = sp(ctx).edit().putString("panel_shape", v.name).apply()
    fun panelScale(ctx: Context) = sp(ctx).getInt("panel_scale", 85)
    fun setPanelScale(ctx: Context, v: Int) = sp(ctx).edit().putInt("panel_scale", v).apply()
    fun setPanelSee(ctx: Context, v: Int) = sp(ctx).edit().putInt("panel_see", v).apply()

    fun centered(ctx: Context) = sp(ctx).getBoolean("centered", false)
    fun setCentered(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("centered", v).apply()

    fun openOnConnect(ctx: Context) = sp(ctx).getBoolean("open_on_connect", false)
    fun setOpenOnConnect(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("open_on_connect", v).apply()

    /** Herramientas de desarrollo visibles (se activan tocando la versión 5 veces). */
    fun devTools(ctx: Context) = sp(ctx).getBoolean("dev_tools", false)
    fun setDevTools(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dev_tools", v).apply()

    /** Estilo del dibujo del control. */
    fun skin(ctx: Context) = runCatching { ControllerSkin.valueOf(sp(ctx).getString("skin", null)!!) }
        .getOrDefault(ControllerSkin.CARBON)
    fun setSkin(ctx: Context, v: ControllerSkin) = sp(ctx).edit().putString("skin", v.name).apply()
}

/** Cómo se presenta el control a Android en cada modo (nombre del dispositivo de entrada). */
enum class PadMode(val label: String) {
    ANDROID("Android"), XINPUT("X-Input"), DS5("DS5");

    /** Cambio por USB (solo desde Android); null para Android, al que solo se vuelve con Vista + Menú. */
    val target: Target? get() = when (this) { XINPUT -> Target.XINPUT; DS5 -> Target.DS5; ANDROID -> null }
}

object ModeSwitcher {

    data class ModeInfo(val main: Int, val sub: Int)

    /** Modo deducido del nombre con que Android ve el control, o null si no está conectado. */
    fun detect(): PadMode? {
        val names = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it)?.name }
        return when {
            names.any { it.contains("x-box 360", true) || it.contains("xbox 360", true) } -> PadMode.XINPUT
            names.any { it.contains("sony interactive", true) && it.contains("g9", true) } -> PadMode.DS5
            names.any { it.contains("gamesir", true) && it.contains("g9", true) } -> PadMode.ANDROID
            else -> null
        }
    }

    suspend fun readMode(): ModeInfo? = withContext(Dispatchers.IO) {
        val r = G9Usb.transact(Gcm.readMode()) ?: return@withContext null
        if (r.size < 6) return@withContext null
        ModeInfo(r[4].u(), r[5].u())
    }

    /**
     * Cambia el modo del control. Requiere que ya haya permiso USB.
     * Devuelve un texto corto con el resultado.
     */
    suspend fun switchTo(ctx: Context, target: Target): String = withContext(Dispatchers.IO) {
        G9Usb.findDevice(ctx) ?: return@withContext tr("G9 no encontrado (¿está en modo Android?)", "G9 not found (is it in Android mode?)")
        G9Usb.open(ctx)?.let { return@withContext it }
        val id = when (target) {
            Target.XINPUT -> Gcm.MODE_XINPUT
            Target.DS5 -> Gcm.MODE_DS5
        }
        G9Usb.transact(Gcm.setMode(id, 1), expectReply = false)
        G9Usb.close()
        tr("Cambiando a ${target.label}…", "Switching to ${target.label}…")
    }
}
