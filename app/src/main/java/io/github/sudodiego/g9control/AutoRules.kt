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

    /**
     * La captura la saca el emulador (solo con "Pasar al juego"): Guía + RB le llega y él guarda su propia imagen, sin
     * shaders (con BFI o CRT beam simulator la captura de Android sale con un cuadro a medias). La app solo muestra el
     * aviso y el sonido. Por defecto en RetroArch.
     */
    fun shotByGame(ctx: Context, pkg: String?): Boolean = pkg != null && guideTap(ctx, pkg) == GuideTap.PASS &&
        sp(ctx).getBoolean("shot_by_game_$pkg", pkg.startsWith("com.retroarch"))
    fun setShotByGame(ctx: Context, pkg: String, v: Boolean) = sp(ctx).edit().putBoolean("shot_by_game_$pkg", v).apply()

    /** Aviso de RAOfflineProxy al abrir un emulador sin internet con el proxy apagado. */
    fun raProxyHint(ctx: Context) = sp(ctx).getBoolean("ra_proxy_hint", true)
    fun setRaProxyHint(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("ra_proxy_hint", v).apply()

    /** Al empezar a transmitir desde el PC, recordar que las capturas van con M dos veces o L3 + R3. */
    fun streamTip(ctx: Context) = sp(ctx).getBoolean("stream_tip", true)
    fun setStreamTip(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("stream_tip", v).apply()

    /** Aviso de batería baja (tarjeta a 15, 10 y 5 % sin cargador). */
    fun lowBattery(ctx: Context) = sp(ctx).getBoolean("low_battery", true)
    fun setLowBattery(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("low_battery", v).apply()
    val LOW_LEVELS = listOf(15, 10, 5)
    /** El aviso de batería baja abajo al centro, encima del toast de ZUI (que no se puede quitar). */
    fun lowBatteryOverToast(ctx: Context) = sp(ctx).getBoolean("low_battery_over_toast", true)
    fun setLowBatteryOverToast(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("low_battery_over_toast", v).apply()
    /** Al enchufar: un aviso con lo que falta para el límite (el propio o el de ZUI) o para 100 %. */
    fun chargeNotice(ctx: Context) = sp(ctx).getBoolean("charge_notice", true)
    fun setChargeNotice(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("charge_notice", v).apply()
    /** Jingle al enchufar (res/raw/charge.wav), apenas se conecta el cargador. */
    fun chargeSound(ctx: Context) = sp(ctx).getBoolean("charge_sound", true)
    fun setChargeSound(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("charge_sound", v).apply()
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
    /** L3 + R3 solo transmitiendo (Artemis, Moonlight: ahí la guía es del PC); en lo demás, la guía. */
    fun chordOnlyStreaming(ctx: Context) = sp(ctx).getBoolean("chord_streaming_only", false)
    fun setChordOnlyStreaming(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("chord_streaming_only", v).apply()
    /** Transmitiendo, Start + Select = Atrás de Android (abre el menú de Artemis o Moonlight sin tocar la pantalla). */
    fun streamBack(ctx: Context) = sp(ctx).getBoolean("stream_back", true)
    fun setStreamBack(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("stream_back", v).apply()

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
     * con batería ≥ [smartBypassLevel] y temperatura ≥ [smartBypassTemp] (0 = sin mínimo) — las dos, o con
     * [smartBypassAny] cualquiera de las dos (con calor aunque tenga poca batería) — y si [smartBypassGames], solo en
     * juegos. Necesita Shizuku.
     */
    fun smartBypass(ctx: Context) = sp(ctx).getBoolean("smart_bypass", false)
    fun setSmartBypass(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("smart_bypass", v).apply()
    fun smartBypassLevel(ctx: Context) = sp(ctx).getInt("smart_bypass_level", 40)
    fun setSmartBypassLevel(ctx: Context, v: Int) = sp(ctx).edit().putInt("smart_bypass_level", v).apply()
    fun smartBypassTemp(ctx: Context) = sp(ctx).getInt("smart_bypass_temp", 0)
    fun setSmartBypassTemp(ctx: Context, v: Int) = sp(ctx).edit().putInt("smart_bypass_temp", v).apply()
    fun smartBypassAny(ctx: Context) = sp(ctx).getBoolean("smart_bypass_any", false)
    fun setSmartBypassAny(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("smart_bypass_any", v).apply()
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
     * Qué hace tocar la guía en una app: Atrás (lo normal), nada, o pasarla al juego (la app no la toma: el emulador
     * recibe la guía real del G9 y la puede asignar; mantener tampoco abre el panel, se usa L3 + R3). Enviar botones
     * desde la app no sirve: Android 15 los marca como de un dispositivo "Virtual" y RetroArch no los toma como del
     * jugador 1 (probado el 12 oct 2026; ver CONTEXTO).
     */
    enum class GuideTap { BACK, PASS, NOTHING;
        val label get() = when (this) {
            BACK -> tr("Atrás", "Back"); PASS -> tr("Pasar al juego", "Pass to the game"); NOTHING -> tr("Nada", "Nothing")
        }
    }
    /** RetroArch: pasar la guía al juego (se asigna en RetroArch al menú o a salir). */
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
    /** Estilo lista (como MangoHUD: una fila por dato, con el gráfico de tiempos de cuadro) en vez de la barra. */
    fun perfList(ctx: Context) = sp(ctx).getBoolean("perf_list", false)
    fun setPerfList(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("perf_list", v).apply()
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

