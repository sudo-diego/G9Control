package io.github.sudodiego.g9control

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Puente entre el servicio de accesibilidad (que ve teclas del sistema, como encendido o volumen) y
 * la prueba de botones. Solo tiene un oyente mientras la app está en primer plano.
 */
object KeyBus {
    @Volatile var listener: ((KeyEvent) -> Unit)? = null
}

/** Modo que quiere cada app. */
enum class AutoTarget { XINPUT, DS5 }

/** Dónde aparecen los avisos pequeños (el de volver a Android y la etiqueta del modo). */
enum class NoticePos(val h: Int, val v: Int) {
    TOP_START(-1, -1), TOP(0, -1), TOP_END(1, -1),
    CENTER_START(-1, 0), CENTER(0, 0), CENTER_END(1, 0),
    BOTTOM_START(-1, 1), BOTTOM(0, 1), BOTTOM_END(1, 1);

    /** Nombre para lectores de pantalla: "Arriba a la izquierda"… */
    val label: String get() {
        val row = when (v) { -1 -> tr("Arriba", "Top"); 1 -> tr("Abajo", "Bottom"); else -> tr("Al medio", "Middle") }
        val col = when (h) { -1 -> tr("a la izquierda", "left"); 1 -> tr("a la derecha", "right"); else -> tr("al centro", "center") }
        return "$row $col"
    }

}

/** Sonido al sacar una captura. */
enum class ShotSound { NONE, SHUTTER, JINGLE;
    val label get() = when (this) { NONE -> tr("Sin sonido", "None"); SHUTTER -> tr("Obturador", "Shutter"); JINGLE -> tr("Jingle", "Jingle") }
}

/** Cómo entran y salen los avisos. */
enum class NoticeAnim { SMOOTH, SLIDE, NONE;
    val label get() = when (this) { SMOOTH -> tr("Suave", "Smooth"); SLIDE -> tr("Deslizar", "Slide"); NONE -> tr("Sin animación", "None") }
}

/**
 * Reglas del cambio automático. La app cambia el control desde Android a X-Input o DS5; desde esos
 * modos (y para volver a Android) se usa Start + Select, con una guía en pantalla.
 */
object AutoRules {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("g9", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("auto_enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_enabled", v).apply()

    /** Recordar volver a Android al salir de una app con regla. */
    fun remindReturn(ctx: Context) = sp(ctx).getBoolean("auto_remind_return", true)
    fun setRemindReturn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_remind_return", v).apply()

    /** Mientras la guía grande pide Vista + Menú: bloquear botones del control y pantalla táctil. */
    fun guideBlock(ctx: Context) = sp(ctx).getBoolean("guide_block", false)
    fun setGuideBlock(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("guide_block", v).apply()

    /** Con bloqueo, también sticks y gatillos: la guía toma el foco (el juego puede pausarse). */
    fun blockSticks(ctx: Context) = sp(ctx).getBoolean("block_sticks", false)
    fun setBlockSticks(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("block_sticks", v).apply()
    /** Lo mismo, para el aviso pequeño. */
    fun miniBlockSticks(ctx: Context) = sp(ctx).getBoolean("mini_block_sticks", false)
    fun setMiniBlockSticks(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("mini_block_sticks", v).apply()

    /** Bloquear también con el aviso pequeño (por ejemplo, al salir de un juego). */
    fun miniBlock(ctx: Context) = sp(ctx).getBoolean("mini_block", false)
    fun setMiniBlock(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("mini_block", v).apply()

    /** Al abrir un juego: tarjeta en el centro (false) o directamente el aviso pequeño (true). */
    fun guideSmall(ctx: Context) = sp(ctx).getBoolean("guide_small", false)
    fun setGuideSmall(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("guide_small", v).apply()

    /** Mosaico resaltado "Activar Shizuku" en el panel cuando Shizuku está instalado pero no activo. */
    fun shizukuTile(ctx: Context) = sp(ctx).getBoolean("shizuku_tile", true)
    fun setShizukuTile(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("shizuku_tile", v).apply()

    /** Aviso de batería baja (tarjeta a 15, 10 y 5 % sin cargador). */
    fun lowBattery(ctx: Context) = sp(ctx).getBoolean("low_battery", true)
    fun setLowBattery(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("low_battery", v).apply()
    val LOW_LEVELS = listOf(15, 10, 5)
    /** El aviso de batería baja abajo al centro, encima del toast de ZUI (que no se puede quitar). */
    fun lowBatteryOverToast(ctx: Context) = sp(ctx).getBoolean("low_battery_over_toast", true)
    fun setLowBatteryOverToast(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("low_battery_over_toast", v).apply()
    fun lowBatterySound(ctx: Context) = sp(ctx).getBoolean("low_battery_sound", true)
    fun setLowBatterySound(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("low_battery_sound", v).apply()

    /** Ráfaga de capturas: mantener RB (con el botón guía) sigue sacando una por segundo. */
    fun burstShots(ctx: Context) = sp(ctx).getBoolean("burst_shots", true)
    fun setBurstShots(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("burst_shots", v).apply()

    /** Al salir de un juego: aviso pequeño (false, lo de siempre) o la tarjeta en el centro (true). */
    fun exitBig(ctx: Context) = sp(ctx).getBoolean("exit_big", false)
    fun setExitBig(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("exit_big", v).apply()

    /** Mostrar la etiqueta del modo cuando la app lo cambia sola. */
    fun showLabel(ctx: Context) = sp(ctx).getBoolean("show_label", true)
    fun setShowLabel(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("show_label", v).apply()

    /** L3 + R3 abre el panel de juego (captura, Game Assistant, modo del control). */
    fun gamePanel(ctx: Context) = sp(ctx).getBoolean("game_panel", true)
    fun setGamePanel(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("game_panel", v).apply()

    /** En DS5, M dos veces (llega como un toque del touchpad) saca la captura limpia del panel de juego. */
    fun touchpadShot(ctx: Context) = sp(ctx).getBoolean("touchpad_shot", true)
    fun setTouchpadShot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("touchpad_shot", v).apply()

    /**
     * Game Assistant oculto (appops, con Shizuku): G9 Control lo muestra solo mientras lo usa y después lo vuelve
     * a dejar así. Null mientras no se sabe (se toma de cómo esté la primera vez).
     */
    fun gaHidden(ctx: Context): Boolean? = sp(ctx).takeIf { it.contains("ga_hidden") }?.getBoolean("ga_hidden", false)
    fun setGaHidden(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("ga_hidden", v).apply()

    /**
     * Bypass automático propio (como el inteligente de Game Assistant, pero con los rangos que se elijan): enchufada,
     * con batería ≥ [smartBypassLevel] y temperatura ≥ [smartBypassTemp] (0 = sin mínimo), y si [smartBypassGames],
     * solo en juegos. Necesita Shizuku.
     */
    fun smartBypass(ctx: Context) = sp(ctx).getBoolean("smart_bypass", false)
    fun setSmartBypass(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("smart_bypass", v).apply()
    fun smartBypassLevel(ctx: Context) = sp(ctx).getInt("smart_bypass_level", 40)
    fun setSmartBypassLevel(ctx: Context, v: Int) = sp(ctx).edit().putInt("smart_bypass_level", v).apply()
    fun smartBypassTemp(ctx: Context) = sp(ctx).getInt("smart_bypass_temp", 0)
    fun setSmartBypassTemp(ctx: Context, v: Int) = sp(ctx).edit().putInt("smart_bypass_temp", v).apply()
    fun smartBypassGames(ctx: Context) = sp(ctx).getBoolean("smart_bypass_games", true)
    fun setSmartBypassGames(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("smart_bypass_games", v).apply()

    /** Avisos en Inicio: el cambio automático está apagado (hay reglas pero no el servicio) / Shizuku no está listo. */
    fun warnService(ctx: Context) = sp(ctx).getBoolean("warn_service", true)
    fun setWarnService(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("warn_service", v).apply()
    fun warnShizuku(ctx: Context) = sp(ctx).getBoolean("warn_shizuku", true)
    fun setWarnShizuku(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("warn_shizuku", v).apply()

    /** Botones de volumen de la tablet invertidos (el de abajo sube, el de arriba baja). Los del control no cambian. */
    fun swapVolume(ctx: Context) = sp(ctx).getBoolean("swap_volume", false)
    fun setSwapVolume(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("swap_volume", v).apply()

    /** Botón Legion (Home del control): tocar = Atrás, mantener = panel de juego. */
    fun legionKeys(ctx: Context) = sp(ctx).getBoolean("legion_keys", true)

    /** Cada atajo del botón guía, por separado (con el botón guía activado). */
    enum class Guide(val key: String) { TAP_BACK("guide_tap_back"), HOLD_PANEL("guide_hold_panel"), RB_SHOT("guide_rb_shot"), LB_BACK("guide_lb_back"), HOLD_LOCK("guide_hold_lock") }

    /**
     * Qué hace tocar la guía en una app: Atrás (lo normal), el botón guía tal cual (el emulador lo puede usar para lo
     * que quieras: en RetroArch, asignarlo a salir o al menú), Vista + Menú (salir de RetroArch con sus atajos) o
     * nada. Los dos del medio se aprietan como si fuera el control, con Shizuku ([KeyInject]); mantener sigue
     * abriendo el panel sin que el juego reciba nada.
     */
    // "Botón guía" (mandarlo tal cual) se quitó: Android 15 marca todo botón enviado por software como de un dispositivo
    // "Virtual" (aunque se le dé el id del G9; RetroArch decía "Virtual (0/0)"), así que un emulador no lo puede asignar
    // como la guía del control. Las combinaciones sí sirven: RetroArch le aplica a "Virtual" su configuración de respaldo.
    // PASS: la guía no la toma la app (ni tocar ni mantener): le llega al juego tal cual, desde el control real. Es lo
    // único que un emulador acepta como la guía del jugador 1 (lo enviado por software llega como "Virtual").
    // GUIDE: la app manda la guía apretada 3 s (para que el emulador alcance a asignarla); llega como "Virtual".
    enum class GuideTap { BACK, VIEW_MENU, GUIDE, PASS, NOTHING;
        val label get() = when (this) {
            BACK -> tr("Atrás", "Back"); VIEW_MENU -> tr("Vista + Menú", "View + Menu"); GUIDE -> tr("Botón guía", "Guide button")
            PASS -> tr("Pasar al juego", "Pass to the game"); NOTHING -> tr("Nada", "Nothing")
        }
    }
    /** RetroArch sale con Vista + Menú (sus atajos de fábrica en Android): esa es su opción de entrada. */
    fun guideTap(ctx: Context, pkg: String?): GuideTap = pkg?.let { p ->
        runCatching { GuideTap.valueOf(sp(ctx).getString("guide_tap_app_$p", null)!!) }.getOrNull()
            ?: if (p.startsWith("com.retroarch")) GuideTap.PASS else null
    } ?: GuideTap.BACK
    fun setGuideTap(ctx: Context, pkg: String, v: GuideTap) = sp(ctx).edit().putString("guide_tap_app_$pkg", v.name).apply()
    fun guide(ctx: Context, g: Guide) = sp(ctx).getBoolean(g.key, true)
    fun setGuide(ctx: Context, g: Guide, v: Boolean) = sp(ctx).edit().putBoolean(g.key, v).apply()

    /**
     * Apps de streaming de PC o consola: ahí el botón Legion pasa normal (es el botón guía del PC: modo Xbox, Game
     * Bar). En Moonlight y Artemis solo durante la transmisión ([STREAM_ACTIVITY]); en su lista de PCs sigue siendo
     * Atrás, para poder salir. Si la app toma el control por USB (Artemis en X-Input), Android ni lo ve y da igual.
     */
    val STREAMING = setOf(
        "com.limelight", "com.limelight.noir", "com.limelight.unofficial",   // Moonlight, Artemis
        "com.valvesoftware.steamlink", "com.microsoft.xboxone.smartglass", "com.gamepass",
        "com.metallic.chiaki", "com.playstation.remoteplay", "tv.parsec.client", "com.nvidia.geforcenow",
    )
    /** La pantalla de transmisión de Moonlight y sus derivados (Artemis). */
    const val STREAM_ACTIVITY = "com.limelight.Game"
    fun setLegionKeys(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("legion_keys", v).apply()

    /** Aviso de captura: dónde sale (por defecto arriba a la izquierda, como en la Switch) y con qué sonido. */
    fun shotPos(ctx: Context) = runCatching { NoticePos.valueOf(sp(ctx).getString("shot_pos", null)!!) }
        .getOrDefault(NoticePos.TOP_START)
    fun setShotPos(ctx: Context, v: NoticePos) = sp(ctx).edit().putString("shot_pos", v.name).apply()
    /** Monitor de rendimiento sobre el juego (se enciende desde el panel) y su posición. */
    fun perfHud(ctx: Context) = sp(ctx).getBoolean("perf_hud", false)
    fun setPerfHud(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("perf_hud", v).apply()
    /** Barra completa (dos líneas: además watts, tiempo restante, Hz y calor de la tablet) o compacta. */
    fun perfFull(ctx: Context) = sp(ctx).getBoolean("perf_full", true)
    fun setPerfFull(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("perf_full", v).apply()
    fun perfPos(ctx: Context) = runCatching { NoticePos.valueOf(sp(ctx).getString("perf_pos", null)!!) }
        .getOrDefault(NoticePos.TOP_START)
    fun setPerfPos(ctx: Context, v: NoticePos) = sp(ctx).edit().putString("perf_pos", v.name).apply()
    fun shotSound(ctx: Context) = runCatching { ShotSound.valueOf(sp(ctx).getString("shot_sound", null)!!) }
        .getOrDefault(ShotSound.JINGLE)
    fun setShotSound(ctx: Context, v: ShotSound) = sp(ctx).edit().putString("shot_sound", v.name).apply()
    fun shotAnim(ctx: Context) = runCatching { NoticeAnim.valueOf(sp(ctx).getString("shot_anim", null)!!) }
        .getOrDefault(NoticeAnim.SLIDE)
    fun setShotAnim(ctx: Context, v: NoticeAnim) = sp(ctx).edit().putString("shot_anim", v.name).apply()

    fun noticePos(ctx: Context) = runCatching { NoticePos.valueOf(sp(ctx).getString("notice_pos", null)!!) }
        .getOrDefault(NoticePos.TOP_END)
    fun setNoticePos(ctx: Context, v: NoticePos) = sp(ctx).edit().putString("notice_pos", v.name).apply()

    /** Posición de la guía pequeña (volver a Android); por defecto arriba al centro, como antes. */
    fun guidePos(ctx: Context) = runCatching { NoticePos.valueOf(sp(ctx).getString("guide_pos", null)!!) }
        .getOrDefault(NoticePos.TOP)
    fun setGuidePos(ctx: Context, v: NoticePos) = sp(ctx).edit().putString("guide_pos", v.name).apply()

    fun noticeAnim(ctx: Context) = runCatching { NoticeAnim.valueOf(sp(ctx).getString("notice_anim", null)!!) }
        .getOrDefault(NoticeAnim.SMOOTH)
    fun setNoticeAnim(ctx: Context, v: NoticeAnim) = sp(ctx).edit().putString("notice_anim", v.name).apply()

    /** Pide al servicio mostrar un ejemplo del aviso (para ver posición y animación). */
    fun preview(ctx: Context, kind: String) =
        ctx.sendBroadcast(Intent(ACTION_PREVIEW).setPackage(ctx.packageName).putExtra("kind", kind))
    const val ACTION_PREVIEW = "io.github.sudodiego.g9control.PREVIEW_NOTICE"

    /** Cambio pedido a mano (ajustes rápidos): el servicio cambia por USB o muestra el aviso de Vista + Menú. */
    fun requestSwitch(ctx: Context, to: PadMode) =
        ctx.sendBroadcast(Intent(ACTION_SWITCH).setPackage(ctx.packageName).putExtra("target", to.name))
    const val ACTION_SWITCH = "io.github.sudodiego.g9control.SWITCH_MODE"

    /** Desde la app: volver a oscurecer la tablet con el monitor (después de encenderla con el botón). */
    fun darkenTablet(ctx: Context) = ctx.sendBroadcast(Intent(ACTION_DARKEN).setPackage(ctx.packageName))
    const val ACTION_DARKEN = "io.github.sudodiego.g9control.DARKEN_TABLET"

    fun rules(ctx: Context): Map<String, AutoTarget> {
        val x = sp(ctx).getStringSet("auto_xinput", emptySet()).orEmpty().associateWith { AutoTarget.XINPUT }
        val d = sp(ctx).getStringSet("auto_ds5", emptySet()).orEmpty().associateWith { AutoTarget.DS5 }
        return x + d
    }

    fun setRules(ctx: Context, v: Map<String, AutoTarget>) = sp(ctx).edit()
        .putStringSet("auto_xinput", v.filterValues { it == AutoTarget.XINPUT }.keys)
        .putStringSet("auto_ds5", v.filterValues { it == AutoTarget.DS5 }.keys)
        .apply()

    /** true si el servicio de accesibilidad de la app está activado en los ajustes de Android. */
    fun serviceEnabled(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(ctx, AutoModeService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Permiso de las ROMs chinas para leer la lista de apps; en Android estándar no existe. */
    const val PERM_APP_LIST = "com.android.permission.GET_INSTALLED_APPS"

    fun openServiceSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Servicio de accesibilidad: escucha qué app pasa a primer plano (de las ventanas solo usa su número, para la
 * captura; no lee su contenido) y filtra teclas para el panel de juego y el botón Legion. Según las reglas, cambia el G9 a X-Input y muestra una guía flotante para llegar a
 * DS5 o volver a Android con Start + Select.
 */
class AutoModeService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var lastPackage: String? = null
    /** Transmitiendo desde un PC o consola (el botón Legion pasa normal). */
    private var streaming = false
    /** App al frente (la última que no es pasajera). */
    /** La app al frente: la de las ventanas ahora (si el aviso de cambio no llegó, [lastPackage] quedaba viejo). */
    val foregroundApp: String? get() = currentApp()
    /** App con regla que está (o estuvo) al frente; al salir de ella se recuerda volver a Android. */
    private var ruleApp: String? = null
    private lateinit var guide: ModeGuide
    private lateinit var panel: GamePanel
    private lateinit var perfHud: PerfHud
    /** Mouse virtual (desde el panel): mientras está encendido se queda con los sticks. */
    private val mouse by lazy { VirtualMouse(this) { mouseOn = it; applyMotionSources() } }
    private var mouseOn = false
    fun toggleMouse() = mouse.toggle()
    val isMouseOn get() = mouseOn
    /** Se salió de una app con regla y el control aún no vuelve a Android. */
    private var returnPending = false
    /** G9 Control está al frente: tiene su propia franja de modo, así que la guía se quita. */
    private var appInFront = false
    private var hiddenForApp = false
    /** Ventana de la app al frente (del último cambio de ventana), o -1. La usa la captura del panel de juego. */
    @Volatile var appWindowId = -1
        private set

    /**
     * Pantalla apagada: la guía se quita (no tiene sentido en la pantalla de bloqueo). Al desbloquear,
     * si sigue pendiente volver a Android, aparece el aviso pequeño de arriba.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { main.removeCallbacks(waitUnlock); guide.suspendForLock() }
                // Sin cargador el bypass no sirve, y si quedara activo la próxima vez que se enchufe no cargaría.
                Intent.ACTION_POWER_DISCONNECTED -> {
                    ZuiBattery.manualOverride = false
                    smartOn = false
                    scope.launch { if (ZuiBattery.readBypass() == true) ZuiBattery.setBypass(false, manual = false) }
                }
                Intent.ACTION_BATTERY_CHANGED -> { lastBattery = i; evaluateBypass(); checkLowBattery(i) }
                AutoRules.ACTION_PREVIEW -> when (val kind = i.getStringExtra("kind") ?: "banner") {
                    "shot" -> panel.previewShot()
                    "battery" -> panel.lowBattery(15, ZuiBattery.dischargeTimeLeft(this@AutoModeService, PowerLog.recentAvg)?.let { ZuiBattery.formatTime(it) })
                    else -> guide.preview(kind)
                }
                AutoRules.ACTION_DARKEN -> if (Monitor.woken) toggleTabletScreen()
                AutoRules.ACTION_SWITCH -> i.getStringExtra("target")
                    ?.let { runCatching { PadMode.valueOf(it) }.getOrNull() }?.let(::manualSwitch)
                // En esta tablet USER_PRESENT no siempre llega: al encender la pantalla se espera el desbloqueo.
                Intent.ACTION_SCREEN_ON -> {
                    unlockTries = 0; main.removeCallbacks(waitUnlock); main.post(waitUnlock)
                    // Encendida con el botón: el sistema vuelve a encender el panel y queda así (es la forma de ver la
                    // tablet si el control no sirve con el monitor). Se oscurece otra vez desde el panel de juego o al
                    // volver a conectar el monitor.
                    if (panelOff || lightOff || darkView != null) {
                        panelOff = false; lightOff = false; Monitor.woken = true
                        main.removeCallbacks(monitorCheck); main.postDelayed(monitorCheck, 500)
                    }
                }
                Intent.ACTION_USER_PRESENT -> { main.removeCallbacks(waitUnlock); onUnlocked() }
            }
        }
    }

    /** Último aviso de la batería (nivel, temperatura, cargador). */
    private var lastBattery: Intent? = null
    /** El bypass automático lo activó (solo apaga lo que él activó). */
    private var smartOn = false
    private var lastBypassRead = 0L

    /**
     * Bypass automático, por dos razones (basta una): el automático (enchufada, con batería ≥ mínimo y temperatura ≥
     * mínimo, y en un juego si se eligió) o el límite de carga propio (enchufada y con la batería en el límite). Una
     * vez activo se mantiene hasta que la batería baje 2 % (3 % el límite) o la temperatura 1 °C (si no, parpadearía).
     * Si el usuario lo cambió a mano, no lo toca hasta desconectar o cambiar de app.
     */
    private fun evaluateBypass() {
        val i = lastBattery ?: return
        val limit = ZuiBattery.ownLimit(this)
        if (!AutoRules.smartBypass(this) && limit == null) {
            if (smartOn) { smartOn = false; scope.launch { ZuiBattery.setBypass(false, manual = false) } }
            return
        }
        if (ZuiBattery.manualOverride || Shell.state != Shell.State.READY) return
        // Lo encendió el automático pero alguien lo apagó (a mano, en otra app, ZUI o Game Assistant): ya no está
        // encendido por él. Si no, creería que sigue activo y no lo volvería a encender. Cada minuto se lee el estado
        // real (los avisos de la batería llegan seguido; leerlo es un comando de Shizuku).
        if (smartOn && ZuiBattery.bypassNow == false) smartOn = false
        val now = android.os.SystemClock.uptimeMillis()
        if (smartOn && now - lastBypassRead > 60_000) {
            lastBypassRead = now
            scope.launch { if (ZuiBattery.readBypass() == false) { Log.d(TAG, "bypass apagado desde fuera: se revisa"); evaluateBypass() } }
        }
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) * 100 /
            i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val temp = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val game = !AutoRules.smartBypassGames(this) || ZuiGame.inGame(this)
        val minLevel = AutoRules.smartBypassLevel(this)
        val minTemp = AutoRules.smartBypassTemp(this)
        val margin = if (smartOn) 2 else 0
        val smartWant = AutoRules.smartBypass(this) && game && level >= minLevel - margin && (minTemp == 0 || temp >= minTemp - margin / 2f)
        val limitWant = limit != null && level >= limit - (if (smartOn) 3 else 0)
        val want = plugged && (smartWant || limitWant)
        if (want == smartOn) return
        Log.d(TAG, "bypass automático: ${if (want) "activar" else "desactivar"} (batería $level %, $temp °C, juego $game, límite $limit)")
        smartOn = want
        scope.launch { if (!ZuiBattery.setBypass(want, manual = false) && want) smartOn = false }
    }

    /** Aviso de volver a Android al salir de un juego: el pequeño o la tarjeta, según lo elegido. */
    private fun showReturn() {
        val big = AutoRules.exitBig(this)
        guide.show(PadMode.ANDROID, mini = !big, big = big)
    }

    /** El bypass vuelve al automático (desde el panel): decide ya con lo que hay ahora. */
    fun bypassToAuto() {
        ZuiBattery.returnToAuto()
        smartOn = ZuiBattery.bypassNow == true
        evaluateBypass()
    }

    /** Hay algo automático para el bypass: el bypass automático o el límite de carga propio. */
    fun bypassHasAuto() = AutoRules.smartBypass(this) || ZuiBattery.ownLimit(this) != null

    // También la frecuencia de los juegos: ZUI tarda en decir que es un juego.
    private val bypassCheck = Runnable { evaluateBypass(); if (RefreshRate.forGames(this) != null) RefreshRate.applyFor(this, lastPackage) }

    /**
     * Monitor externo: en modo espejo, si para ese monitor (o para todos) se eligió 16:9, se pone solo; al
     * desconectarlo (o pasar al modo extendido) se vuelve a 16:10, solo si lo había puesto G9 Control. La pantalla
     * de la tablet queda como se eligió (oscura o apagada) mientras tanto. Se revisa un momento después de cada
     * cambio de pantalla o de modo (el monitor tarda en asentarse).
     */
    private val monitorCheck = Runnable {
        val m = Monitor.current(this)
        val mirror = Monitor.mirror(this)
        // Al arrancar el servicio (por ejemplo, tras actualizar la app con el panel apagado y el monitor ya quitado):
        // si no toca tenerlo apagado, se enciende por si acaso. Encenderlo estando encendido no hace nada.
        if (!panelChecked && Shell.state == Shell.State.READY && Monitor.screen(this) == Monitor.Screen.OFF) {
            panelChecked = true
            if (m == null || !mirror) scope.launch { Shell.run(Shell.screenPower(true)) }
        }
        if (m != null && m.key != lastMonitor) { Monitor.remember(this, m); Log.d(TAG, "monitor conectado: ${m.name} (${m.key})") }
        lastMonitor = m?.key
        val want = m != null && mirror && Monitor.wants169(this, m.key)
        val wide = Aspect.is169(this)
        when {
            want && !wide -> scope.launch { if (Aspect.toggle(this@AutoModeService)) { Monitor.setApplied(this@AutoModeService, true); Log.d(TAG, "monitor ${m?.name}: 16:9") } }
            !want && wide && Monitor.applied(this) -> scope.launch {
                Monitor.setApplied(this@AutoModeService, false)
                if (Aspect.toggle(this@AutoModeService)) Log.d(TAG, "sin monitor en espejo: 16:10")
            }
        }
        applyTabletScreen(if (m != null && mirror && !Monitor.woken) Monitor.screen(this) else Monitor.Screen.NORMAL)
        if (m == null) Monitor.woken = false
    }
    private var lastMonitor: String? = null
    private var panelChecked = false

    /** Lo que G9 Control hizo con la pantalla de la tablet, para deshacerlo. */
    private var darkView: View? = null
    private var panelOff = false

    /**
     * Pantalla de la tablet con un monitor en espejo. Oscura: una ventana invisible del servicio pide el brillo
     * "apagado", que Android deja en el mínimo (sin Shizuku; una app no puede apagar la luz del todo). Apagada: el
     * panel entero, con Shizuku, como Extinguish (si no está, queda oscura). Sin luz: la ventana de Oscura (así el
     * brillo automático no lo cambia) y, encima, el brillo en -1 con Shizuku, lo que hace Extinguish; al quitar la
     * ventana Android vuelve a poner el brillo de siempre.
     */
    private fun applyTabletScreen(target: Monitor.Screen) {
        val ready = Shell.state == Shell.State.READY
        val off = target == Monitor.Screen.OFF && ready
        val black = target == Monitor.Screen.NO_LIGHT && ready
        val dark = !off && target != Monitor.Screen.NORMAL
        // Saliendo de Sin luz: se quita la ventana para que Android vuelva a aplicar el brillo (después se pone si toca).
        if (lightOff && !black) {
            lightOff = false
            darkView?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) }; darkView = null }
        }
        if (dark && darkView == null) {
            val v = View(this)
            val p = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            }
            runCatching { getSystemService(WindowManager::class.java).addView(v, p); darkView = v; Log.d(TAG, "tablet oscura") }
                .onFailure { Log.w(TAG, "no se pudo quitar la luz", it) }
        } else if (!dark && darkView != null) {
            runCatching { getSystemService(WindowManager::class.java).removeView(darkView) }
            darkView = null
            Log.d(TAG, "tablet con luz")
        }
        if (off != panelOff) {
            panelOff = off
            scope.launch { Shell.run(Shell.screenPower(!off))?.let { Log.d(TAG, if (off) "panel de la tablet apagado" else "panel de la tablet encendido") } }
        }
        if (black && !lightOff) {
            lightOff = true
            // Un momento después de la ventana: si no, el brillo mínimo que pide ella llega después y pisa el -1.
            scope.launch { kotlinx.coroutines.delay(800); if (lightOff) Shell.run(Shell.SCREEN_LIGHT_OFF)?.let { Log.d(TAG, "tablet sin luz") } }
        }
    }
    private var lightOff = false

    /** Desde el panel de juego: ver la tablet un rato con el monitor conectado (o volver a oscurecerla). */
    fun toggleTabletScreen() {
        Monitor.woken = !Monitor.woken
        main.removeCallbacks(monitorCheck)
        monitorCheck.run()
    }
    /** La pantalla de la tablet está oscurecida por el monitor (para el texto del panel de juego). */
    val tabletDark get() = darkView != null || panelOff
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = later()
        override fun onDisplayRemoved(id: Int) = later()
        override fun onDisplayChanged(id: Int) {
            if (id != android.view.Display.DEFAULT_DISPLAY) later()
            // La pantalla de la tablet cambió de frecuencia: que se mantenga la elegida (Ahorro la baja a 30).
            else RefreshRate.onDisplayChanged()
        }
        fun later() { main.removeCallbacks(monitorCheck); main.postDelayed(monitorCheck, 1500) }
    }
    private val monitorModeObserver = object : android.database.ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(monitorCheck); main.postDelayed(monitorCheck, 1500) }
    }

    /**
     * Moonlight/Artemis está transmitiendo. La vista del video se reporta como un `View` genérico, así que se mira
     * la forma: transmitiendo, su ventana activa son apenas un marco y unas pocas vistas (probado: FrameLayout con
     * View, View y el TextView de las estadísticas); en sus listas de PCs y apps hay grillas, imágenes y botones.
     * Solo nombres de clase, no el contenido.
     */
    private fun limelightStreaming(): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return streaming
        // Transmitiendo hay un TextView (las estadísticas de Artemis): ese no cuenta.
        val ui = listOf("Button", "ListView", "GridView", "RecyclerView", "ImageView", "EditText", "ScrollView")
        val queue = ArrayDeque(listOf(root))
        var count = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (++count > 8) return false
            val cls = n.className?.toString().orEmpty()
            if (ui.any { cls.endsWith(it) }) return false
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
        return true
    }

    /**
     * Al arrancar el servicio (al actualizar la app, por ejemplo) no llega ningún "cambio de app" hasta cambiar de
     * verdad: la app al frente se lee de la lista de ventanas (para el panel, las capturas en un launcher, etc.).
     */
    /**
     * La app al frente leída ahora de la lista de ventanas (la que tiene el foco). El aviso de cambio de ventana a veces
     * no llega (probado: RetroArch abierto por un launcher seguía como Dolphin), y lo que depende de la app (tocar la guía
     * en un emulador) no puede fiarse solo de [lastPackage].
     */
    private var staleSeen: String? = null
    fun currentApp(): String? {
        val apps = runCatching { windows }.getOrDefault(emptyList()).filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive }
        val pkg = w?.let { runCatching { it.root?.packageName?.toString() }.getOrNull() }?.takeIf { !isTransient(it) }
        // Su tarea, para volver a ella desde Recientes del panel como en Recientes de Android.
        if (pkg != null && w != null) RecentApps.rememberTask(this, pkg, RecentApps.taskOf(w))
        if (pkg != null && pkg != lastPackage && pkg != staleSeen) { staleSeen = pkg; Log.i(TAG, "app al frente: $pkg (el servicio creía $lastPackage)") }
        return pkg ?: lastPackage
    }

    private fun initForeground() {
        if (lastPackage != null) return
        val apps = runCatching { windows }.getOrDefault(emptyList()).filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: return
        val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: return
        if (isTransient(pkg)) return
        lastPackage = pkg
        Memory.openedByUser(this, pkg)
        appWindowId = w.id
        if (pkg in AutoRules.STREAMING) { streaming = !pkg.startsWith("com.limelight") || limelightStreaming(); updatePadWatch() }
        Log.d(TAG, "app al frente al arrancar: $pkg${if (streaming) " (transmitiendo)" else ""}")
    }

    private var unlockTries = 0
    /** Revisa cada 300 ms (hasta 2 min) si ya se quitó la pantalla de bloqueo. */
    private val waitUnlock: Runnable = object : Runnable {
        override fun run() {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (!km.isKeyguardLocked) { onUnlocked(); return }
            if (++unlockTries < 400) main.postDelayed(this, 300)
        }
    }

    private var lastUnlock = 0L

    /** Recién desbloqueada: vuelve la guía que había, o el aviso de volver a Android. */
    private fun onUnlocked() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUnlock < 1500) return   // USER_PRESENT y la espera pueden llegar juntos
        lastUnlock = now
        Log.d(TAG, "desbloqueada")
        val mode = ModeSwitcher.detect()
        if (mode == PadMode.ANDROID) returnPending = false
        if (lowPending) main.postDelayed({ lastBattery?.let { checkLowBattery(it) } }, 800)
        if (guide.resumeAfterLock()) return
        if (returnPending && ruleApp == null && mode != null) showReturn()
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = changed()
        override fun onInputDeviceRemoved(id: Int) { lockIfGuidePoweredOff(id); changed() }
        override fun onInputDeviceChanged(id: Int) = changed()
        private fun changed() {
            ModeTileService.refreshAll(this@AutoModeService)
            updateTouchpad()
            updatePadWatch()
            if (ModeSwitcher.detect() == PadMode.ANDROID) returnPending = false
            guide.update()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Lang.load(this)
        // El fondo del panel (y el tema) aunque no se haya abierto la app.
        ThemeState.load(this)
        guide = ModeGuide(this)
        panel = GamePanel(this)
        // Sin control: el botón de accesibilidad de la barra (o mantener los dos botones de volumen, si se eligió
        // este servicio para ese atajo) abre y cierra el panel; se usa con el dedo.
        accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                if (panel.isOpen) panel.hide() else panel.show()
            }
        })
        perfHud = PerfHud(this).also { it.update() }
        Shell.init(this)
        ZuiBattery.init(this)
        // El límite de carga de ZUI, para el tiempo de carga (queda guardado para cuando Shizuku no esté).
        scope.launch { ZuiBattery.readChargeLimit() }
        // Consumo por app y apps que vuelven solas (cada 5 s).
        PowerLog.start(this)
        ZuiGame.initPerf(this)
        updateTouchpad()
        getSharedPreferences("g9", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputListener, main)
        getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayListener, main)
        contentResolver.registerContentObserver(Settings.System.getUriFor(Monitor.MODE_KEY), false, monitorModeObserver)
        main.postDelayed(monitorCheck, 2000)   // por si el monitor ya estaba conectado
        main.postDelayed(::initForeground, 1000)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AutoRules.ACTION_PREVIEW)
            addAction(AutoRules.ACTION_SWITCH)
            addAction(AutoRules.ACTION_DARKEN)
        }, if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
        // Prueba desde el PC: hace lo mismo que tocar la guía en la app al frente (solo adb: pide DUMP, que el shell
        // tiene y una app normal no). `adb shell am broadcast -a io.github.sudodiego.g9control.TEST_GUIDE_TAP`.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                main.post {
                    when (i.getStringExtra("what")) {
                        "battery" -> panel.lowBattery(15, "1 h 20 min")
                        "panel" -> panel.toggle()
                        "recent" -> i.getStringExtra("pkg")?.let { RecentApps.open(this@AutoModeService, it) }
                        "front" -> Log.i(TAG, "al frente: ${currentApp()}")
                        else -> guideTap()
                    }
                }
            }
        }, IntentFilter(ACTION_TEST_GUIDE_TAP), android.Manifest.permission.DUMP, main,
            if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // La propia app (no las ventanas de la guía, que también son de este paquete): la guía se quita para
        // que Vista y Menú le lleguen a la app y su franja muestre el avance.
        if (pkg == packageName && event.className?.toString() == MainActivity::class.java.name) {
            appInFront = true
            lastPackage = pkg
            if (guide.isGuiding) { guide.hide(); hiddenForApp = true }
            return
        }
        if (isTransient(pkg)) return
        // Ventana de la app al frente, para capturarla sola (sin puntero ni superposiciones). -1 si Android no la entrega.
        appWindowId = event.windowId
        // Transmitiendo desde el PC: el botón Legion es el guía de allá. En Moonlight/Artemis solo en su pantalla de
        // transmisión (las clases de diálogos o vistas sueltas no cuentan: solo las de la propia app).
        val cls = event.className?.toString().orEmpty()
        val wasStreaming = streaming
        if (pkg !in AutoRules.STREAMING) streaming = false
        else if (!pkg.startsWith("com.limelight")) streaming = true
        else if (cls.startsWith("com.limelight.")) streaming = cls == AutoRules.STREAM_ACTIVITY
        if (streaming != wasStreaming) { Log.d(TAG, "transmitiendo: $streaming ($pkg, $cls)"); updatePadWatch() }
        if (pkg == lastPackage) return
        appInFront = false
        val wasHidden = hiddenForApp
        hiddenForApp = false
        lastPackage = pkg
        Memory.openedByUser(this, pkg)
        RecentApps.push(this, pkg)
        panel.hide()
        panel.onAppChanged()
        // Bypass automático: otra app, otra decisión ("solo en juegos"). Lo manual sigue valiendo hasta desconectar el
        // cargador. ZUI tarda un momento en decidir si es juego.
        main.removeCallbacks(bypassCheck)
        main.postDelayed(bypassCheck, 3000)
        Log.d(TAG, "app al frente: $pkg (${event.className}), ventana $appWindowId, modo ${ModeSwitcher.detect()}, ZUI ${ZuiGame.sideBar(this)}")
        // Frecuencia de la pantalla elegida para esta app (o la de siempre).
        RefreshRate.applyFor(this, pkg)
        if (!AutoRules.enabled(this)) return
        // Shizuku (para iniciarlo desde el panel): ir y volver no es salir del juego; el aviso de volver a Android
        // queda para cuando se vaya de verdad a otra app.
        if (pkg == Shell.PACKAGE) return

        val target = AutoRules.rules(this)[pkg]
        if (target == null) {
            // Se salió de una app con regla: recordar volver a Android.
            if (ruleApp != null) {
                ruleApp = null
                val mode = ModeSwitcher.detect()
                // Solo si el G9 se usó en esa app (si se jugó con otro control, no tiene sentido).
                if (AutoRules.remindReturn(this) && mode != null && mode != PadMode.ANDROID && lastG9Key >= ruleSince) {
                    returnPending = true
                    // Solo el aviso pequeño de arriba: recuerda volver sin tapar la pantalla.
                    showReturn()
                }
            } else if (wasHidden && returnPending && ModeSwitcher.detect().let { it != null && it != PadMode.ANDROID }) {
                // Se había quitado al abrir G9 Control: vuelve el aviso de volver a Android.
                showReturn()
            }
            return
        }
        ruleApp = pkg
        ruleSince = android.os.SystemClock.uptimeMillis()
        returnPending = false
        if (otherPadInUse) { Log.d(TAG, "regla $target: se está usando otro control, el G9 no se toca"); return }
        applyRule(target, retry = true)
    }

    private fun applyRule(target: AutoTarget, retry: Boolean) {
        val mode = ModeSwitcher.detect()
        Log.d(TAG, "regla $target, modo actual $mode")
        when {
            // Desde Android: cambio directo con aviso "Cambiando a …" → "Listo ✓".
            mode == PadMode.ANDROID -> switchTo(wanted(target))
            // Ya está en el modo pedido: si quedó la guía de volver a Android, ya no sirve.
            mode == wanted(target) -> if (guide.isGuiding) guide.hide()
            // Desde otro modo que no es el pedido: guía de Start + Select.
            mode != null && mode != wanted(target) -> guide.show(wanted(target))
            // El control se está reconectando: se reintenta una vez.
            mode == null && retry -> main.postDelayed({ applyRule(target, retry = false) }, 2500)
        }
    }

    /** Última vez que se apretó un botón del G9 / de otro control (uptime en ms; 0 = nunca). */
    private var lastG9Key = 0L
    private var lastOtherPad = 0L
    /** Cuándo se abrió la app con regla actual. */
    private var ruleSince = 0L
    /** Lo último que se usó fue otro control: el G9 no se toca (ni cambio, ni guía, ni aviso de volver). */
    private val otherPadInUse get() = lastOtherPad > lastG9Key

    /** Start/Select que se retuvieron mientras estaba la guía; su "soltar" también se retiene. */
    private val held = mutableSetOf<Int>()

    /** Botones del control cuya presión llegó a la app: su soltar tiene que llegar también. */
    private val delivered = mutableSetOf<Int>()

    /**
     * Si la presión de un botón llegó a la app, su soltar llega siempre, aunque mientras tanto se haya abierto el
     * panel o la guía (si no, la app lo cree apretado para siempre: pasaba con el primer stick de L3 + R3).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        // Soltar la guía cancela siempre el bloqueo de 5 s, antes que nada: con el panel abierto el soltar va al panel
        // y no llegaba a legionKey (la tablet se bloqueaba sola 5 s después de abrir el panel manteniendo la guía).
        if (k == KeyEvent.KEYCODE_BUTTON_MODE) trackGuide(event)
        // Otro botón del mismo control con la guía apretada (Vista + Menú, navegar el panel): no es "mantener para apagar".
        else if (event.action == KeyEvent.ACTION_DOWN && event.deviceId == guideDevice && guideDownAt > 0) guideDownAt = 0
        // Los botones que aprieta la app como si fuera el control (la guía en RetroArch) pasan sin tocar.
        if (android.os.SystemClock.uptimeMillis() < injectingUntil) return false
        // Qué control se usó por última vez: con otro control (8BitDo, por ejemplo) el G9 se queda como está.
        if (event.action == KeyEvent.ACTION_DOWN && KeyEvent.isGamepadButton(k)) {
            val now = android.os.SystemClock.uptimeMillis()
            // En X-Input el G9 se llama "Microsoft X-Box 360 pad" (el nombre del driver de Xbox por USB).
            val name = event.device?.name.orEmpty()
            if (name.contains("G9", true) || name.contains("x-box 360", true)) lastG9Key = now else lastOtherPad = now
            event.device?.takeIf { !it.isVirtual }?.let { lastPadDevice = it.id }
        }
        if (event.action == KeyEvent.ACTION_UP && delivered.remove(k)) {
            handleKey(event)   // para que el resto (L3 + R3, la guía) se entere de que se soltó
            return false
        }
        val consumed = handleKey(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !consumed && KeyEvent.isGamepadButton(k)) delivered += k
        return consumed
    }

    /**
     * Mientras la guía está en pantalla, Start y Select no llegan al juego (para cambiar de modo sin
     * pausar ni abrir menús). El resto de los botones pasa normal. Devuelve si el evento es nuestro.
     */
    private fun handleKey(event: KeyEvent): Boolean {
        val k = event.keyCode
        // Teclas que no llegan a las apps (encendido, volumen…): se pasan a la prueba de botones.
        KeyBus.listener?.let { l ->
            if (!KeyEvent.isGamepadButton(k) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val copy = KeyEvent(event)
                main.post { l(copy) }
            }
        }
        if (volumeSwap(event)) return true
        // Imagen congelada (cambiando el rendimiento): moverse sin ver a dónde, no. Los sticks los toma su ventana.
        if (panel.busy && (KeyEvent.isGamepadButton(k) || event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_DPAD))) return true
        if (panelChord(event)) return true
        if (legionChord(event)) return true
        if (panel.isOpen && panel.onKey(event)) return true
        if (legionKey(event)) return true
        if (mouseOn && mouse.onKey(event)) return true
        if (k != KeyEvent.KEYCODE_BUTTON_START && k != KeyEvent.KEYCODE_BUTTON_SELECT) {
            // Guía grande con bloqueo: el resto de los botones del control no llega a las apps.
            val fromPad = KeyEvent.isGamepadButton(k) || event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            return fromPad && guide.blocksInput
        }
        if (event.repeatCount == 0 && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            guide.onKey(k, event.action == KeyEvent.ACTION_DOWN)
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> if (guide.isRetaining) { held += k; true } else false
            KeyEvent.ACTION_UP -> held.remove(k) || guide.isRetaining
            else -> false
        }
    }

    /**
     * Volumen invertido en los botones de la tablet: el servicio se queda con la tecla y sube o baja al revés,
     * como lo haría Android (el volumen que corresponda y su barra). Solo los botones físicos de la tablet (no
     * externos ni virtuales): los del control y las teclas inyectadas quedan igual. La captura con encendido +
     * bajar volumen la procesa Android antes y sigue con el botón físico de siempre.
     */
    private fun volumeSwap(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!AutoRules.swapVolume(this)) return false
        val dev = e.device ?: return false
        if (dev.isExternal || dev.isVirtual) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                volKey = k
                volDir = if (k == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                adjustVolume(volDir)
                volSteps = 0
                main.removeCallbacks(volRepeat)
                main.postDelayed(volRepeat, 400)
            }
            // Solo el soltar del botón que se está repitiendo: al pasar rápido de uno al otro, el soltar del primero
            // puede llegar después de apretar el segundo, y cortaba su repetición.
            KeyEvent.ACTION_UP -> if (k == volKey) { main.removeCallbacks(volRepeat); volKey = 0 }
        }
        return true
    }

    /** El botón de volumen que se está manteniendo (0 = ninguno). */
    private var volKey = 0
    private var volDir = 0
    private var volSteps = 0
    /**
     * Mantener el botón: el servicio recibe la tecla antes de que Android genere las repeticiones (solo ve apretar y
     * soltar), así que las hace él, con los tiempos de Android: 400 ms y después un paso cada 80 ms. Como mucho
     * 40 pasos (más de lo que hay de 0 a 100 %): si el soltar no llega (pantalla apagada, la captura se lo quedó),
     * no sigue para siempre.
     */
    private val volRepeat: Runnable = object : Runnable {
        override fun run() {
            if (++volSteps > 40) return
            adjustVolume(volDir)
            main.postDelayed(this, 80)
        }
    }

    private fun adjustVolume(dir: Int) = getSystemService(AudioManager::class.java)
        .adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)

    /** Esta pulsación del botón Legion es nuestra (no llega a la app). */
    private var legionOwned = false
    /** Botón Legion apretado y todavía sin decidir si es toque (Atrás) o mantener (panel de juego). */
    private var legionPending = false
    private val legionHold = Runnable {
        if (!legionPending) return@Runnable
        legionPending = false
        panel.show()
    }

    /**
     * Mantener la guía 5 s: el G9 se apaga (firmware) y la tablet se bloquea con él, como "poner a dormir". Sin
     * Shizuku (acción global del servicio). Si el control se apaga, el soltar puede no llegar: por eso se cuenta
     * desde que se apretó y se cancela solo si llega el soltar o una combinación.
     */
    /** Cuándo se apretó la guía, cuánto se mantuvo la última vez y cuándo se soltó (uptime), y de qué control. */
    private var guideDownAt = 0L
    private var guideHeld = 0L
    private var guideUpAt = 0L
    private var guideDevice = -1

    /**
     * Seguimiento de la guía para "mantener 5 s: bloquear". El G9 se apaga a los 5 s y su firmware manda el soltar
     * justo antes (o se desconecta con la guía apretada), así que el temporizador de 5 s no alcanza: se bloquea cuando
     * el control desaparece después de una guía mantenida 3,5 s o más (ver [lockIfGuidePoweredOff]).
     */
    private fun trackGuide(e: KeyEvent) {
        val now = android.os.SystemClock.uptimeMillis()
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { guideDownAt = now; guideDevice = e.deviceId }
        if (e.action == KeyEvent.ACTION_UP) {
            main.removeCallbacks(legionLock)
            if (guideDownAt > 0) { guideHeld = now - guideDownAt; guideUpAt = now; guideDownAt = 0 }
            if (guideHeld > 2000) Log.i(TAG, "guía soltada tras ${guideHeld} ms")
        }
    }

    /** Se desconectó un control: si fue el de la guía y la guía venía mantenida (apagado del G9), bloquear la tablet. */
    private fun lockIfGuidePoweredOff(id: Int) {
        if (id != guideDevice || !AutoRules.guide(this, AutoRules.Guide.HOLD_LOCK)) return
        val now = android.os.SystemClock.uptimeMillis()
        val heldNow = if (guideDownAt > 0) now - guideDownAt else 0
        val recent = guideUpAt > 0 && now - guideUpAt < 3000 && guideHeld in 3500..7000
        Log.i(TAG, "control de la guía desconectado: mantenida ${heldNow} ms, última ${guideHeld} ms hace ${now - guideUpAt} ms")
        // El G9 se apaga a los 5 s: solo una desconexión en ese momento cuenta (cambiar de modo también desconecta y,
        // si el soltar de la guía no había llegado, parecía mantenida mucho rato).
        if (heldNow in 4500..7000 || recent) {
            guideDownAt = 0; guideUpAt = 0
            main.removeCallbacks(legionLock)
            legionLock.run()
        }
    }

    private val legionLock = Runnable {
        Log.i(TAG, "guía mantenida 5 s: bloquear la tablet")
        panel.hide()
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    /**
     * Botón Legion (Home del control, llega como BUTTON_MODE en los tres modos): tocar = Atrás, mantener 0,5 s
     * = panel de juego. Dentro de G9 Control llega normal (lo usa la prueba de botones), y en las apps de streaming
     * también ([AutoRules.STREAMING]: ahí es el botón guía del PC o la consola). Llega con algo de retraso: el
     * firmware espera por si es un doble toque o Home + cruceta. Devuelve si el evento es nuestro.
     */
    /** RB o LB apretados junto con Legion: no llegan a la app (ni al bajar ni al soltar). */
    private val chordSwallow = mutableSetOf<Int>()
    /**
     * Ya hubo una combinación con este Legion: mientras siga apretado, RB y LB son combinaciones (no llegan al juego).
     * El firmware del G9 avisa que Legion se soltó en cuanto se aprieta otro botón (aunque siga apretado) y, al
     * soltarlo de verdad, manda un toque corto de Legion (bajar y subir en ~70 ms), que no es Atrás. No hace falta
     * mantener Legion (5 s apaga el control): la sesión sigue 2 s después de la última combinación, y en ese rato
     * RB o LB solos también lo son (varias capturas seguidas).
     */
    private var legionChording = false
    private val chordExpire = Runnable { legionChording = false }

    /**
     * Legion + RB = captura (como Steam + R1 en el Steam Deck); mantener RB 1 s sigue sacando (ráfaga, una por
     * segundo, lo que deja Android) hasta soltarlo, aunque ya se haya soltado Legion (mantenerlo 5 s apaga el G9).
     * Legion + LB = Atrás mantenido (salir del juego en Dolphin; necesita Shizuku). Con Legion apretado y antes de
     * que se abra el panel; así Legion no hace Atrás al soltarlo.
     */
    private fun legionChord(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k != KeyEvent.KEYCODE_BUTTON_R1 && k != KeyEvent.KEYCODE_BUTTON_L1) return false
        if (e.action == KeyEvent.ACTION_UP) {
            if (k == KeyEvent.KEYCODE_BUTTON_R1) main.removeCallbacks(burst)
            return chordSwallow.remove(k)
        }
        if (e.repeatCount > 0) return k in chordSwallow
        if (!legionChording && !(legionOwned && legionPending)) return false
        // Ese atajo apagado: RB o LB llegan normal.
        if (!AutoRules.guide(this, if (k == KeyEvent.KEYCODE_BUTTON_R1) AutoRules.Guide.RB_SHOT else AutoRules.Guide.LB_BACK)) return false
        legionPending = false
        legionChording = true
        main.removeCallbacks(legionHold)
        main.removeCallbacks(legionLock)
        main.removeCallbacks(chordExpire)
        main.postDelayed(chordExpire, 2000)
        chordSwallow += k
        if (k == KeyEvent.KEYCODE_BUTTON_R1) {
            Log.d(TAG, "Legion + RB: captura")
            panel.capture()
            main.removeCallbacks(burst)
            if (AutoRules.burstShots(this)) main.postDelayed(burst, 1000)
        } else {
            Log.d(TAG, "Legion + LB: Atrás mantenido")
            scope.launch {
                if (Shell.run(Shell.BACK_LONG) == null) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    panel.message(tr("Mantener Atrás necesita Shizuku: se hizo un Atrás normal.", "Holding Back needs Shizuku: did a normal Back."))
                }
            }
        }
        return true
    }

    /** Ráfaga: RB sigue apretado (desde la combinación con Legion). */
    private val burst: Runnable = object : Runnable {
        override fun run() {
            if (KeyEvent.KEYCODE_BUTTON_R1 !in chordSwallow) return
            panel.capture()
            main.postDelayed(this, 1100)
        }
    }

    /** El último umbral avisado (15, 10, 5); se olvida al enchufar o al pasar de 20 %. */
    private var lowWarned = 101

    /** Batería baja sin cargador: una tarjeta al cruzar cada umbral (no un toast que se olvida). */
    private fun checkLowBattery(i: Intent) {
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged || level > 20) { lowWarned = 101; return }
        if (level < 0 || !AutoRules.lowBattery(this)) return
        val step = AutoRules.LOW_LEVELS.firstOrNull { level <= it && it < lowWarned } ?: return
        // Como la Switch: con la pantalla apagada o bloqueada (en la mochila) no se avisa; se avisa al desbloquear.
        val screenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (!screenOn || locked) { lowPending = true; return }
        // Varios umbrales de golpe (se encendió con 4 %): solo el más bajo.
        lowWarned = AutoRules.LOW_LEVELS.filter { level <= it }.minOrNull() ?: step
        lowPending = false
        Log.i(TAG, "batería baja: $level %")
        panel.lowBattery(level, ZuiBattery.estimatedLeft(this)?.let { ZuiBattery.formatTime(it) })
    }

    /** Se cruzó un umbral con la pantalla apagada o bloqueada: se avisa al desbloquear (si sigue baja y sin cargador). */
    private var lowPending = false

    /** Mientras la app aprieta botones como el control (hasta este momento), el servicio no los toma. */
    @Volatile private var injectingUntil = 0L
    /** El control que apretó la guía: los botones que se envían salen de él. */
    private var legionDevice = 0
    /** El último control real (no virtual) que apretó un botón. */
    private var lastPadDevice = 0

    /**
     * De qué control salen los botones enviados: el que apretó la guía si Android lo conoce y es real; si no, el
     * último que se usó; si no, el primer gamepad conectado. Con un id que no existe, el emulador lo toma como un
     * teclado virtual (RetroArch decía "virtual 0/0") y no como el control.
     */
    private fun injectDevice(): Int {
        // Un control de verdad: gamepad, no virtual y con botón guía (descarta dispositivos raros que se declaran
        // gamepad, como el 11 que salió en la prueba).
        fun real(id: Int) = android.view.InputDevice.getDevice(id)?.takeIf {
            !it.isVirtual && (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD &&
                it.hasKeys(KeyEvent.KEYCODE_BUTTON_MODE)[0]
        }
        val pads = android.view.InputDevice.getDeviceIds().toList().mapNotNull { real(it) }
        return real(legionDevice)?.id ?: real(lastPadDevice)?.id
            ?: pads.firstOrNull { it.name.contains("G9", true) || it.name.contains("x-box 360", true) }?.id ?: pads.firstOrNull()?.id ?: legionDevice
    }

    /** Tocar la guía en esta app: Atrás, o apretar botones como el control (con Shizuku), o nada. */
    private fun guideTap() {
        val app = currentApp()
        val tap = AutoRules.guideTap(this, app)
        val keys = when (tap) {
            AutoRules.GuideTap.BACK -> {
                Log.i(TAG, "guía en $app: Atrás")
                if (AutoRules.guide(this, AutoRules.Guide.TAP_BACK)) performGlobalAction(GLOBAL_ACTION_BACK); return
            }
            AutoRules.GuideTap.NOTHING, AutoRules.GuideTap.PASS -> return
            AutoRules.GuideTap.VIEW_MENU -> intArrayOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START) to 200
            AutoRules.GuideTap.GUIDE -> intArrayOf(KeyEvent.KEYCODE_BUTTON_MODE) to 3000
        }
        if (Shell.state != Shell.State.READY) {
            panel.message(tr("Enviar ${tap.label} necesita Shizuku.", "Sending ${tap.label} needs Shizuku."))
            return
        }
        injectingUntil = android.os.SystemClock.uptimeMillis() + keys.second + 1500
        val dev = injectDevice()
        scope.launch {
            val ok = Shell.run(Shell.keyInject(dev, keys.second, *keys.first), timeoutSec = 8) != null
            injectingUntil = android.os.SystemClock.uptimeMillis() + 200
            Log.i(TAG, "guía en $app: ${tap.name} desde el dispositivo $dev (guía: $legionDevice, último: $lastPadDevice) ${if (ok) "enviado" else "falló"}")
        }
    }

    private fun legionKey(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_BUTTON_MODE) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return legionOwned || legionChording
                // El toque final del firmware al soltar Legion después de una combinación: no es Atrás.
                if (legionChording) return true
                // En Moonlight/Artemis se confirma mirando la pantalla ahora (los avisos de ventana a veces no llegan
                // o un menú de Artemis los cambia): así Legion no hace Atrás transmitiendo.
                if (lastPackage?.startsWith("com.limelight") == true) {
                    val now = limelightStreaming()
                    if (now != streaming) { streaming = now; Log.d(TAG, "transmitiendo (al apretar Legion): $now"); updatePadWatch() }
                }
                legionOwned = AutoRules.legionKeys(this) && !appInFront && !guide.blocksInput && !streaming &&
                    AutoRules.guideTap(this, currentApp()) != AutoRules.GuideTap.PASS
                legionDevice = e.device?.id ?: e.deviceId
                Log.i(TAG, "guía apretada: dispositivo ${e.deviceId} (${e.device?.name}), en $lastPackage")
                if (legionOwned) {
                    legionPending = true
                    if (AutoRules.guide(this, AutoRules.Guide.HOLD_PANEL)) main.postDelayed(legionHold, 500)
                    // Sin temporizador de 5 s: a veces el soltar no llega y bloqueaba sin que el G9 se apagara. Se bloquea
                    // solo cuando el G9 se apaga de verdad (se desconecta tras mantener la guía; ver lockIfGuidePoweredOff).
                }
                return legionOwned
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(legionLock)
                if (legionChording) { legionOwned = false; return true }
                if (!legionOwned) return false
                legionOwned = false
                if (legionPending) {
                    legionPending = false; main.removeCallbacks(legionHold)
                    guideTap()
                }
                return true
            }
        }
        return legionOwned
    }

    private var lastTouchpad = 0L
    private var touchpadOn = false
    /** Al cambiar la opción en Ajustes se aplica enseguida (no recién al reconectar el control). */
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "touchpad_shot") updateTouchpad()
        if (key?.startsWith("smart_bypass") == true) evaluateBypass()
        if (key == "charge_limit") evaluateBypass()
        if ((key == "perf_hud" || key == "perf_pos") && ::perfHud.isInitialized) perfHud.update()
        if (key == "monitor_screen") Monitor.woken = false   // la opción recién elegida se ve al tiro
        if (key == "monitor_screen" || key == "monitor_all_169" || key?.startsWith("monitor_fit_") == true) {
            main.removeCallbacks(monitorCheck); main.postDelayed(monitorCheck, 300)
        }
    }

    /**
     * Recibir los eventos de mouse (así llega el touchpad del G9) solo en DS5 y con la opción activa. Android 14
     * y 15 no permiten solo mirarlos: el servicio se los queda, así que un mouse real tampoco funciona en DS5.
     */
    private fun updateTouchpad() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val want = AutoRules.touchpadShot(this) && ModeSwitcher.detect() == PadMode.DS5
        if (want == touchpadOn) return
        touchpadOn = want
        applyMotionSources()
        Log.d(TAG, "touchpad del G9: ${if (want) "capturas" else "normal"}")
    }

    /**
     * M dos veces transmitiendo (Artemis/Moonlight en DS5): la app captura el puntero y el clic del touchpad no llega
     * al servicio. Con Shizuku se leen los eventos crudos del touchpad del G9 (`getevent`, solo lectura: la app
     * igual recibe el clic) y cada clic saca la captura. Solo mientras se transmite.
     */
    private var padWatch: Process? = null
    private fun updatePadWatch() {
        val want = streaming && AutoRules.touchpadShot(this) && ModeSwitcher.detect() == PadMode.DS5 && Shell.state == Shell.State.READY
        if (want == (padWatch != null)) return
        if (!want) { padWatch?.destroy(); padWatch = null; Log.d(TAG, "touchpad crudo: no"); return }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // "add device 13: /dev/input/event12" y después '  name:     "… G9 Touchpad"'.
            val list = Shell.run(Shell.INPUT_LIST) ?: return@launch
            var last: String? = null
            var dev: String? = null
            for (line in list.lines()) {
                if (line.startsWith("add device")) last = line.substringAfter(": ").trim()
                else if ("name:" in line && "Touchpad" in line && ("G9" in line || "Legion" in line)) { dev = last; break }
            }
            val d = dev ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (padWatch != null || !streaming) return@withContext
                padWatch = Shell.stream(Shell.inputEvents(d)) { l ->
                    if ("BTN_MOUSE" in l && "DOWN" in l) main.post { onPadClick() }
                }
                Log.d(TAG, "touchpad crudo: $d")
            }
        }
    }

    /** Clic del touchpad (M dos veces): captura, como en [onMotionEvent] (uno por toque, no cada evento). */
    private fun onPadClick() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTouchpad < 700) return
        lastTouchpad = now
        Log.d(TAG, "M dos veces transmitiendo (touchpad crudo): captura")
        panel.capture()
    }

    /** Sticks y cruceta (como ejes) mientras el panel de juego está abierto: lo navegan y no llegan al juego. */
    private var panelSticks = false
    fun setPanelSticks(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 34 || on == panelSticks) return
        panelSticks = on
        applyMotionSources()
    }

    private fun applyMotionSources() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val sources = (if (touchpadOn) InputDevice.SOURCE_MOUSE else 0) or (if (panelSticks || mouseOn) InputDevice.SOURCE_JOYSTICK else 0)
        runCatching { serviceInfo = serviceInfo.apply { motionEventSources = sources } }
            .onFailure { Log.w(TAG, "no se pudieron pedir los eventos de movimiento", it) }
    }

    /**
     * Touchpad del G9 en DS5. El control no tiene touchpad: solo llega M dos veces, como un toque corto
     * (que además muestra el puntero). El primer evento tras 1,5 s sin ninguno saca la captura limpia. Solo
     * llegan mientras [updateTouchpad] los pidió.
     */
    override fun onMotionEvent(event: MotionEvent) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            if (panel.isOpen) panel.onStick(event) else if (mouseOn) mouse.onMotion(event)
            return
        }
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return
        val now = android.os.SystemClock.uptimeMillis()
        val quiet = now - lastTouchpad > 1500
        lastTouchpad = now
        if (!quiet || !AutoRules.touchpadShot(this)) return
        if (event.device?.name?.contains("g9", true) != true || ModeSwitcher.detect() != PadMode.DS5) return
        Log.d(TAG, "M dos veces en DS5 (touchpad): captura")
        panel.capture()
    }

    /** L3 y R3 apretados ahora mismo. */
    private val sticksDown = mutableSetOf<Int>()
    /** El stick que completó L3 + R3: ni su presión ni su soltar llegan al juego. */
    private var chordKey = -1
    /** L3 + R3 apretados y todavía sin decidir si es toque (panel) o mantener (captura). */
    private var chordPending = false

    /** L3 + R3 mantenidos medio segundo: captura directa, sin abrir el panel. */
    private val chordHold = Runnable {
        if (!chordPending) return@Runnable
        chordPending = false
        Log.d(TAG, "L3 + R3 mantenidos: captura")
        panel.hide()
        panel.capture()
    }

    /**
     * L3 + R3: tocarlos abre o cierra el panel de juego; mantenerlos 0,5 s saca la captura directamente.
     * El primero de los dos llega al juego como siempre (no se sabe todavía si viene el otro); el segundo
     * se lo queda el panel. No funciona mientras está la guía.
     */
    private fun panelChord(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k != KeyEvent.KEYCODE_BUTTON_THUMBL && k != KeyEvent.KEYCODE_BUTTON_THUMBR) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return k == chordKey
                sticksDown += k
                if (sticksDown.size == 2 && AutoRules.gamePanel(this) && !guide.isGuiding) {
                    chordKey = k
                    chordPending = true
                    main.postDelayed(chordHold, 500)
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                sticksDown -= k
                // Se soltó antes del medio segundo: era un toque.
                if (chordPending) { chordPending = false; main.removeCallbacks(chordHold); panel.toggle() }
                if (k == chordKey) { chordKey = -1; return true }
            }
        }
        return false
    }

    private val transientCache = mutableMapOf<String, Boolean>()

    /**
     * Ventanas que no cuentan como "cambiar de app": esta app, la barra del sistema, el teclado y
     * superposiciones sin ícono en el menú de apps (por ejemplo la barra de juegos de ZUI, que aparece
     * justo al abrir un juego). Las pantallas de inicio (Cocoon Shell) sí cuentan.
     */
    private fun isTransient(pkg: String): Boolean = transientCache.getOrPut(pkg) {
        if (pkg == packageName || pkg == "com.android.systemui") return@getOrPut true
        val imm = getSystemService(InputMethodManager::class.java)
        if (imm.enabledInputMethodList.any { it.packageName == pkg }) return@getOrPut true
        val pm = packageManager
        val launchable = pm.getLaunchIntentForPackage(pkg) != null
        val home = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).any { it.activityInfo.packageName == pkg }
        !launchable && !home
    }

    private fun wanted(t: AutoTarget) = if (t == AutoTarget.DS5) PadMode.DS5 else PadMode.XINPUT

    /**
     * Cambio pedido a mano (ajustes rápidos o panel de juego). Desde Android va por USB; desde otro modo
     * muestra el aviso pequeño de Vista + Menú (reemplaza la guía que hubiera) y cierra el panel de
     * notificaciones para que se vea.
     */
    internal fun manualSwitch(to: PadMode) {
        val mode = ModeSwitcher.detect() ?: return
        if (mode == to) return
        if (mode == PadMode.ANDROID) { switchTo(to); return }
        if (android.os.Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // Con G9 Control al frente guía su propia franja (si no, la guía se quedaría con Vista y Menú).
        if (appInFront) startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SWITCH_TO, to.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        else guide.show(to, mini = true)
    }

    private fun switchTo(to: PadMode) {
        val target = to.target ?: return
        val d = G9Usb.findDevice(this) ?: return
        if (!G9Usb.hasPermission(this, d)) {
            Toast.makeText(
                this, tr("TBF Companion: falta el permiso USB. Abre la app una vez con el control conectado.",
                    "TBF Companion: USB permission missing. Open the app once with the controller connected."),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        scope.launch {
            ModeSwitcher.switchTo(this@AutoModeService, target)
            if (AutoRules.showLabel(this@AutoModeService)) guide.show(to, hold = false)
        }
    }

    override fun onInterrupt() {}

    companion object {
        internal const val TAG = "G9Auto"
        const val ACTION_TEST_GUIDE_TAP = "io.github.sudodiego.g9control.TEST_GUIDE_TAP"
    }

    override fun onDestroy() {
        if (mouseOn) mouse.stop()
        PowerLog.stop()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener)
        getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener)
        runCatching { contentResolver.unregisterContentObserver(monitorModeObserver) }
        getSharedPreferences("g9", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacksAndMessages(null)
        // Que la tablet no quede a oscuras si el servicio se apaga con el monitor conectado.
        darkView?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        if (panelOff) Thread { kotlinx.coroutines.runBlocking { Shell.run(Shell.screenPower(true)) } }.start()
        if (::guide.isInitialized) guide.hide()
        if (::panel.isInitialized) panel.release()
        if (::perfHud.isInitialized) perfHud.release()
        padWatch?.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
