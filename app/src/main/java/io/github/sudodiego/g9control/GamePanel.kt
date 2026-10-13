package io.github.sudodiego.g9control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import kotlin.coroutines.resume
import java.util.Date
import java.util.Locale

/**
 * Panel de juego: menú flotante sobre cualquier app (mantener la guía, L3 + R3, el botón de accesibilidad o "Abrir el
 * panel" en la app). Se maneja con el control sin quitarle el foco al juego: el servicio se queda con los botones y,
 * con el panel abierto, con los sticks y la cruceta (`motionEventSources`, que en X-Input y DS5 llega como eje):
 * se mueve con stick o cruceta, A elige, B cierra o vuelve, Y hace la otra acción. También se puede tocar. Además
 * maneja los avisos (captura, batería baja, logros), la imagen congelada del rendimiento y la alerta roja de logros.
 */
class GamePanel(private val svc: AutoModeService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val ui = PanelUi()
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    /** Se cierra solo si no se usa. */
    private val timeout = Runnable { hide() }

    val isOpen get() = view != null

    fun toggle() = if (isOpen) hide() else show()

    fun show() {
        // Para el tema "Sistema" del panel.
        ThemeState.systemDark = (svc.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        inPerfMenu = false
        ui.editing = false
        recentStart = 0
        ui.page = { if (pageRecents(it)) poke() }
        ui.items = items()
        // Empieza en el primer mosaico (Captura), no en las barras.
        ui.sel = ui.items.indexOfFirst { it.section == PanelRow.GAME }.coerceAtLeast(0)
        header()
        if (view == null) { create(); UiSounds.play(svc, UiSounds.Kind.OPEN, panel = true) }
        svc.setPanelSticks(true)
        poke()
        main.removeCallbacks(live); main.postDelayed(live, LIVE_MS)
        if (RaProxy.isEmulator(svc.foregroundApp) && RaProxy.installed(svc)) scope.launch {
            val before = RaProxy.activeNow to RaProxy.setupNow
            RaProxy.stranded(svc)   // también lee si está encendido
            if (RaProxy.activeNow == true && Shell.state == Shell.State.READY) RaProxy.setupNow = RaProxy.setup(svc.foregroundApp)
            if ((RaProxy.activeNow to RaProxy.setupNow) != before && isOpen) refresh()
        }
        // Bypass: igual, lo que se sabe al instante y se confirma en segundo plano (también fuera de los juegos).
        if (Shell.state == Shell.State.READY) scope.launch {
            val before = ZuiBattery.bypassNow
            ZuiBattery.readBypass()
            if (ZuiBattery.bypassNow != before && isOpen && !inPerfMenu) {
                val sel = ui.sel
                ui.items = items()
                ui.sel = sel.coerceAtMost(ui.items.size - 1)
                header()
            }
        }
        // Lo que ya se sabe (Game Assistant visible, modo de rendimiento) se usa al instante; en segundo plano se
        // confirma con Shizuku y solo si cambió se corrige. Así los ítems no aparecen ni se mueven un momento después.
        if (ZuiGame.inGame(svc) && Shell.state == Shell.State.READY) scope.launch {
            val before = ZuiGame.hiddenNow
            ZuiGame.hidden()
            if (ZuiGame.hiddenNow != before && isOpen && !inPerfMenu) {
                val sel = ui.sel
                ui.items = items()
                ui.sel = sel.coerceAtMost(ui.items.size - 1)
            }
            val app = svc.foregroundApp
            val mode = ZuiGame.perfOf(ZuiGame.lastPerfLine()) ?: return@launch
            val changed = mode != ZuiGame.knownPerf(app)
            ZuiGame.rememberPerf(app, mode)
            if (changed && isOpen && inPerfMenu) showPerfMenu()
        }
    }

    /** Nombre de la app al frente, para el encabezado. */
    private fun appLabel(pkg: String?): String = pkg?.let {
        runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
    } ?: "TBF Companion"

    /** Encabezado del panel: la app al frente, la batería (ícono con su nivel), "Bypass" o "Cargando" y la hora. */
    private fun header() {
        ui.sub = false; ui.footer = null
        ui.title = appLabel(svc.foregroundApp)
        ui.stats = Prefs.panelStats(svc)
        ui.mode = ModeSwitcher.detect()?.label
        val i = svc.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ui.battery = i?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        // Cargando: rayo en la batería. Bypass: un enchufe al lado y la batería en celeste (no carga, pero está enchufada).
        // Sin Shizuku no se puede leer: lo último que se supo puede ser viejo (se detuvo Shizuku, lo apagó otro).
        ui.bypass = Shell.state == Shell.State.READY && ZuiBattery.bypassNow == true && ZuiBattery.plugged(svc)
        ui.charging = !ui.bypass && i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) == android.os.BatteryManager.BATTERY_STATUS_CHARGING
        ui.held = !ui.bypass && !ui.charging && (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        // Lo calcula Android (−1 si todavía no sabe, recién enchufada).
        // Hasta el límite de carga si hay (el de ZUI o el propio), si no hasta 100.
        ui.chargeLeft = when {
            ui.charging -> ZuiBattery.chargeTimeLeft(svc)?.let {
                val target = ZuiBattery.chargeTarget(svc)
                val t = ZuiBattery.formatTime(it)
                if (target < 100) tr("Llega a $target % en $t", "Reaches $target % in $t") else tr("Llena en $t", "Full in $t")
            }
            // A batería: cuánto dura al ritmo del último minuto (o al de ahora si todavía no hay promedio).
            !ZuiBattery.plugged(svc) -> ZuiBattery.estimatedLeft(svc)?.let { tr("Quedan ${ZuiBattery.formatTime(it)}", "${ZuiBattery.formatTime(it)} left") }
            else -> null
        }
        val time = android.text.format.DateFormat.getTimeFormat(svc).format(Date())
        ui.status = listOfNotNull(ui.battery.takeIf { it >= 0 }?.let { "$it %" }, time).joinToString(" · ")
        ui.clock = time
    }

    /** Shizuku no está listo: pedirle el permiso, o abrir su app para iniciarlo. */
    private fun fixShizuku() {
        hide()
        if (Shell.state == Shell.State.NO_PERMISSION) Shell.requestPermission()
        else Shell.launchIntent(svc)?.let { runCatching { svc.startActivity(it) } }
            ?: toast(tr("Shizuku no está instalado.", "Shizuku isn't installed."))
    }

    /** Del submenú (rendimiento, frecuencia) al panel, con su mosaico elegido. */
    private var fadeAnim: android.animation.Animator? = null

    /**
     * Cambiar lo que muestra el panel con un fundido, como al abrirlo: el contenido se desvanece (110 ms), cambia y
     * vuelve a aparecer (220 ms, desacelerando). Antes el contenido cambiaba de golpe y solo aparecía.
     */
    private fun crossfade(change: () -> Unit) {
        fadeAnim?.cancel()
        val out = android.animation.ValueAnimator.ofFloat(ui.fade, 0f).apply {
            duration = 110
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener { ui.fade = it.animatedValue as Float }
        }
        val back = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { ui.fade = it.animatedValue as Float }
        }
        out.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (cancelled) return
                change()
                fadeAnim = back.also { it.start() }
            }
        })
        fadeAnim = out.also { it.start() }
    }

    /** Antes de pasar a un submenú: la ventana conserva su alto (y la tarjeta queda al centro). */
    private fun keepHeight() {
        val v = view ?: return
        val lp = params ?: return
        if (lp.height != WindowManager.LayoutParams.WRAP_CONTENT || v.height <= 0) return
        lp.height = v.height
        ui.lockedHeight = true
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun backToMain() {
        inPerfMenu = false
        ui.items = items()
        ui.sel = ui.items.indexOfFirst { it.icon == subFrom }.takeIf { it >= 0 }
            ?: ui.items.indexOfFirst { it.section == PanelRow.GAME }.coerceAtLeast(0)
        header()
        poke()
    }

    /** El mosaico desde el que se abrió el submenú (para volver a él). */
    private var subFrom: ImageVector = Icons.Outlined.Speed

    /** Submenú de frecuencia de la pantalla para la app al frente: la del sistema o una fija (se recuerda por app). */
    private fun showHzMenu() {
        keepHeight()
        inPerfMenu = true
        subFrom = Icons.Outlined.DisplaySettings
        val app = svc.foregroundApp
        val chosen = RefreshRate.forApp(svc, app)
        ui.sub = true; ui.footer = null
        ui.title = tr("Frecuencia de la pantalla", "Screen refresh rate")
        // Sin elección propia: la de todos los juegos (si hay) o la del sistema.
        val games = RefreshRate.forGames(svc)?.takeIf { ZuiGame.inGame(svc) }
        val first = if (games != null) PanelItem(tr("Juegos", "Games"), Icons.Outlined.Restore, sub = "$games Hz", active = chosen == null) { pickHz(null) }
            else PanelItem(tr("Sistema", "System"), Icons.Outlined.Restore, sub = "${RefreshRate.systemPeak(svc)} Hz", active = chosen == null) { pickHz(null) }
        ui.items = listOf(first) +
            RefreshRate.supported(svc).map { hz ->
                PanelItem("$hz Hz", Icons.Outlined.DisplaySettings, sub = if (hz == chosen) tr("Esta app", "This app") else null, active = hz == chosen) { pickHz(hz) }
            }
        ui.sel = ui.items.indexOfFirst { it.active }.coerceAtLeast(0)
        poke()
    }

    /** WiFi o Bluetooth: con Shizuku se cambia y el panel queda abierto mostrando el estado nuevo; sin él, el panel de Android. */
    private fun radio(on: Boolean, onCmd: String, offCmd: String) {
        if (Shell.state != Shell.State.READY) return
        scope.launch {
            Shell.run(if (on) onCmd else offCmd)
            main.postDelayed({ refresh() }, 700)
        }
    }

    private fun openSettings(action: String) {
        runCatching { svc.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun pickHz(hz: Int?) {
        val app = svc.foregroundApp ?: return crossfade { backToMain() }
        RefreshRate.setForApp(svc, app, hz)
        crossfade { backToMain() }
        // Cuando la pantalla ya cambió (unos cientos de ms), el mosaico muestra los Hz nuevos.
        RefreshRate.applyFor(svc, app) { main.postDelayed({ refresh() }, 400) }
    }

    /**
     * Mientras está abierto, encabezado y mosaicos se recalculan cada [LIVE_MS]: el brillo automático que va bajando,
     * enchufar (tiempo de carga), el volumen con los botones, WiFi o Bluetooth cambiados por fuera. Antes solo al abrir
     * o al tocar algo. No en el submenú de rendimiento ni durante un fundido; la selección se mantiene (por nombre).
     */
    private val live: Runnable = object : Runnable {
        override fun run() {
            if (!isOpen) return
            if (!inPerfMenu && fadeAnim == null) { header(); refresh() }
            main.postDelayed(this, LIVE_MS)
        }
    }
    private val LIVE_MS = 1500L

    /** El panel muestra el submenú de rendimiento (no se reemplaza por la lista principal al confirmar algo). */
    private var inPerfMenu = false

    fun hide() {
        main.removeCallbacks(live)
        fadeAnim?.cancel(); fadeAnim = null; ui.fade = 1f
        main.removeCallbacks(timeout)
        main.removeCallbacks(stickRepeat)
        stickDir = 0 to 0
        ui.editing = false
        svc.setPanelSticks(false)
        // Se suelta al instante (deja de recibir botones) pero la ventana se va animada: se desvanece, o se desliza a la
        // derecha en el lateral. Antes se quitaba de golpe y a veces no se veía ninguna animación de salida.
        val v = view; val o = owner
        view = null; owner = null
        if (v != null) v.animate().alpha(0f).translationX(if (ThemeState.panelSide) v.width * 0.5f else 0f).setDuration(160)
            .withEndAction { runCatching { wm.removeView(v) }; o?.destroy() }.start()
        else o?.destroy()
    }

    /** Al apagarse el servicio. */
    fun release() {
        hide()
        flash.hide()
        sounds.release()
        io.shutdown()
        scope.cancel()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Batería baja, como en la Switch: la tarjeta de los avisos con el ícono en rojo, el nivel y cuánto queda. Se queda
     * 6 s (un toast se olvida y la tablet se apagaba).
     */
    fun lowBattery(level: Int, left: String?) {
        if (AutoRules.lowBatterySound(svc)) sounds.play(R.raw.low_battery)
        flash.show(flash.batteryIcon(level), tr("Batería baja · $level %", "Low battery · $level %"),
            listOfNotNull(left?.let { tr("Quedan $it", "$it left") }, tr("Conecta el cargador", "Plug in the charger")).joinToString(" · "), 6000,
            // Sobre el toast de ZUI (abajo al centro), que no se puede quitar: así no se ven los dos.
            if (AutoRules.lowBatteryOverToast(svc)) NoticePos.entries.first { it.h == 0 && it.v == 1 } else null)
    }

    /** Al enchufar: el nivel y cuánto falta para el límite (o para llenarse). Con el color de acento del panel. */
    fun chargeNotice(level: Int, target: Int, left: String?) =
        flash.show(flash.batteryIcon(level, charging = true), tr("$level % · cargando", "$level % · charging"),
            when {
                left == null -> if (target < 100) tr("Hasta $target %", "Up to $target %") else tr("Hasta llenarse", "Until full")
                target < 100 -> tr("Llega a $target % en $left", "Reaches $target % in $left")
                else -> tr("Llena en $left", "Full in $left")
            }, 4500,
            // Igual que el de batería baja: si se pidió, abajo al centro, encima del aviso de carga de ZUI.
            if (AutoRules.lowBatteryOverToast(svc)) NoticePos.entries.first { it.h == 0 && it.v == 1 } else null)

    /** Jingle de carga (al enchufar). */
    fun chargeJingle() = sounds.play(R.raw.charge)

    /** Shizuku no está corriendo (no arrancó al encender) y algo elegido lo necesita. */
    fun shizukuHint() = flash.show(flash.appIcon(Shell.PACKAGE), tr("Shizuku no arrancó", "Shizuku didn't start"),
        tr("Ábrelo y aprieta Start", "Open it and press Start"), 6000)

    /** Emulador sin internet y RAOfflineProxy apagado: el atajo para encenderlo (el porqué está en la app). */
    fun raProxyHint() = flash.show(flash.appIcon(RaProxy.PACKAGE), tr("Sin internet", "Offline"),
        tr("Para tus logros, enciende RAOfflineProxy", "For your achievements, turn on RAOfflineProxy"), 4500)

    /** Proxy apagado con los emuladores apuntándole (tras reiniciar): sin logros ni con internet hasta encenderlo. */
    fun raStrandedHint() = flash.show(flash.appIcon(RaProxy.PACKAGE), tr("RAOfflineProxy está apagado", "RAOfflineProxy is off"),
        tr("Sin él no hay logros, ni con internet: ábrelo (se vuelve a encender solo)", "Without it there are no achievements, even online: open it (it turns back on by itself)"), 7000)

    private var alert: RaAlert? = null
    val alertOpen get() = alert != null
    fun alertKey(e: KeyEvent) { alert?.onKey(e) }
    private fun showAlert(a: RaAlert) { alert?.hide(); hide(); alert = a; a.onClose = { if (alert === a) alert = null }; a.show() }

    // Alertas cortas (título, una línea, A/B): mientras se juega no se lee más. Lo que hay que hacer se dice después
    // de apretar A, en un aviso corto, cuando ya se puede hacer.

    /** Proxy apagado con los emuladores apuntándole: A cierra el juego y abre RAOfflineProxy (para Start); B sigue. */
    fun raStrandedAlert(pkg: String) = showAlert(RaAlert(svc,
        tr("RAOfflineProxy está apagado", "RAOfflineProxy is off"),
        tr("Sin él no hay logros.", "Without it there are no achievements."),
        tr("Cerrar y encenderlo", "Close and turn it on")) {
        scope.launch {
            RaProxy.prepare(svc)
            main.postDelayed({ flash.show(flash.appIcon(RaProxy.PACKAGE), tr("Revisa que diga Stop proxy", "Check it says Stop proxy"),
                tr("Si dice Start, apriétalo; después vuelve al juego", "If it says Start, press it; then go back to the game"), 5000) }, 1200)
        }
    })

    /** El emulador no tiene sesión (o no apunta al proxy): A cierra el juego y abre el emulador para iniciar sesión. */
    fun raSetupAlert(pkg: String, s: RaProxy.Setup) = showAlert(RaAlert(svc,
        if (!s.loggedIn) tr("${s.name} sin sesión", "${s.name} not signed in") else tr("${s.name} sin logros", "No achievements in ${s.name}"),
        if (!s.loggedIn) tr("Sin sesión de RetroAchievements no hay logros.", "Without a RetroAchievements session there are no achievements.")
        else s.problem.orEmpty(),
        if (!s.loggedIn) tr("Cerrar e iniciar sesión", "Close and sign in") else tr("Arreglar", "Fix")) {
        scope.launch {
            if (Shell.state == Shell.State.READY) Shell.run(Shell.stopApp(pkg))
            if (s.loggedIn) RaProxy.open(svc)
            else {
                svc.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { svc.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                // Dónde se inicia sesión, ya en el emulador.
                // Dolphin guarda la sesión recién al salir (con Atrás; cerrado a la fuerza la pierde): decirlo.
                main.postDelayed({ flash.show(flash.appIcon(pkg), tr("Ajustes → RetroAchievements → Iniciar sesión", "Settings → RetroAchievements → Sign in"),
                    tr("Después sal con Atrás hasta cerrar y vuelve a abrir el juego", "Then press Back until it closes and open the game again"), 9000) }, 1500)
                // Y avisar cuando quedó: se mira su configuración cada 3 s (hasta 5 min). Sin esto no había cómo saber
                // que ya estaba y se podía volver al juego. (Relanzarlo solo no se puede: el launcher lo abre con su archivo.)
                if (Shell.state == Shell.State.READY) {
                    repeat(100) {
                        kotlinx.coroutines.delay(3000)
                        if (RaProxy.setup(pkg)?.loggedIn == true) {
                            RaProxy.setupNow = null
                            main.post { flash.show(flash.appIcon(pkg), tr("Sesión guardada", "Session saved"),
                                tr("Ya puedes abrir el juego, con logros", "You can open the game now, with achievements"), 6000) }
                            return@launch
                        }
                    }
                }
            }
        }
    })



    /** Al empezar a transmitir: la guía va al PC, así que las capturas se sacan de otra forma. */
    fun streamTip() {
        // Solo el atajo; la explicación (la guía va al PC) está en Controles.
        val ds5 = ModeSwitcher.detect() == PadMode.DS5
        // Lo que cambia transmitiendo (la guía es del PC): cómo abrir el panel, el menú de la app y las capturas.
        val chord = AutoRules.gamePanel(svc)
        val lines = listOfNotNull(
            if (chord) tr("Panel: L3 + R3", "Panel: L3 + R3") else null,
            if (AutoRules.streamBack(svc)) tr("Menú: Start + Select", "Menu: Start + Select") else null,
            tr("Capturas: ", "Screenshots: ") + if (ds5) tr("M dos veces", "M twice") else tr("mantener L3 + R3", "hold L3 + R3")
        )
        flash.show(flash.appIcon(svc.foregroundApp), tr("Transmitiendo", "Streaming"), lines.joinToString(" · "), 5000)
    }

    /** Ejemplo del aviso de captura (desde Ajustes), con una miniatura de muestra y el sonido elegido. */
    fun previewShot() {
        flash.show(flash.sample())
        sounds.play(AutoRules.shotSound(svc))
    }

    /**
     * Tecla mientras el panel está abierto. Se queda con los botones del control (no llegan al juego);
     * el resto (volumen, encendido) pasa normal.
     */
    fun onKey(e: KeyEvent): Boolean {
        val pad = KeyEvent.isGamepadButton(e.keyCode) || e.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            e.isFromSource(InputDevice.SOURCE_DPAD)
        if (!pad) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        poke()
        val item = ui.items.getOrNull(ui.sel)
        if (ui.editing && item?.adjust != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> adjustSel(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> adjustSel(1)
                KeyEvent.KEYCODE_BUTTON_Y -> if (e.repeatCount == 0) { item.alt?.invoke(); refresh() }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                    if (e.repeatCount == 0) ui.editing = false
                KeyEvent.KEYCODE_BUTTON_MODE -> if (e.repeatCount == 0) hide()
            }
            return true
        }
        if (closeArmed != null && e.keyCode != KeyEvent.KEYCODE_BUTTON_Y) { closeArmed = null; main.removeCallbacks(disarm); refresh() }
        // Sonidos de navegación del panel (si se pidieron también aquí).
        when (e.keyCode) {
            // La cruceta suena en moveGrid, solo si la selección cambió.
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (e.repeatCount == 0) UiSounds.play(svc, UiSounds.Kind.SELECT, panel = true)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> if (e.repeatCount == 0) UiSounds.play(svc, UiSounds.Kind.BACK, panel = true)
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveGrid(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveGrid(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> moveGrid(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveGrid(0, 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (e.repeatCount == 0) {
                if (item?.adjust != null) ui.editing = true else item?.run?.invoke()
            }
            // B o tocar el botón Legion lo cierran (mantenerlo es lo que lo abre).
            // En un submenú B vuelve al panel.
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (e.repeatCount == 0) { if (inPerfMenu) crossfade { backToMain() } else hide() }
            KeyEvent.KEYCODE_BUTTON_MODE -> if (e.repeatCount == 0) hide()
            // Y: la otra acción del mosaico (WiFi y Bluetooth: encender o apagar sin abrir la lista).
            KeyEvent.KEYCODE_BUTTON_Y -> if (e.repeatCount == 0 && item?.alt != null && item.adjust == null) { item.alt.invoke(); refresh() }
        }
        return true
    }

    /**
     * Stick izquierdo o cruceta (en X-Input y DS5 la cruceta llega como eje, no como tecla): ← → dentro de la fila,
     * ↑ ↓ entre filas; ajustando, ← → cambian el valor. Un paso al inclinarlo y, si se mantiene, se repite.
     */
    fun onStick(e: MotionEvent) {
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (kotlin.math.abs(hx) > 0.5f) hx else e.getAxisValue(MotionEvent.AXIS_X)
        val y = if (kotlin.math.abs(hy) > 0.5f) hy else e.getAxisValue(MotionEvent.AXIS_Y)
        // El eje más inclinado gana (en diagonal no se mueve en los dos).
        val dir = when {
            kotlin.math.abs(x) >= kotlin.math.abs(y) && kotlin.math.abs(x) > 0.6f -> (if (x > 0) 1 else -1) to 0
            kotlin.math.abs(y) > 0.6f -> 0 to (if (y > 0) 1 else -1)
            kotlin.math.abs(x) < 0.3f && kotlin.math.abs(y) < 0.3f -> 0 to 0
            else -> return   // entre medio: se mantiene lo de antes
        }
        if (dir == stickDir) return
        stickDir = dir
        main.removeCallbacks(stickRepeat)
        if (dir == 0 to 0) return
        stickStep()
        main.postDelayed(stickRepeat, 400)
    }

    private var stickDir = 0 to 0
    private val stickRepeat: Runnable = object : Runnable {
        override fun run() {
            if (stickDir == 0 to 0 || !isOpen) return
            stickStep()
            main.postDelayed(this, 140)
        }
    }

    private fun stickStep() {
        poke()
        val (dx, dy) = stickDir
        val item = ui.items.getOrNull(ui.sel)
        if (ui.editing && item?.adjust != null) {
            if (dx != 0) adjustSel(dx)
            return
        }
        moveGrid(dx, dy)
    }

    /** Ajusta la barra elegida (brillo, volumen); suena solo si el valor cambió (no en 0 ni en el máximo). */
    private fun adjustSel(dir: Int) {
        val before = ui.items.getOrNull(ui.sel)?.level
        ui.items.getOrNull(ui.sel)?.adjust?.invoke(dir)
        refresh()
        if (ui.items.getOrNull(ui.sel)?.level != before) UiSounds.play(svc, UiSounds.Kind.MOVE, panel = true)
    }

    /**
     * Mover en la grilla, sin dar la vuelta. Entre filas de distinto largo se mantiene el lado (cada fila llena el
     * ancho): de la barra de brillo se baja a los mosaicos de la izquierda.
     */
    private fun moveGrid(dx: Int, dy: Int) {
        val before = ui.sel to recentStart
        moveGridInner(dx, dy)
        // Suena solo si se movió (en el borde, mantener la cruceta o el stick no repite el sonido).
        if ((ui.sel to recentStart) != before) UiSounds.play(svc, UiSounds.Kind.MOVE, panel = true)
    }

    private fun moveGridInner(dx: Int, dy: Int) {
        val rows = panelRows(ui.items)
        val r = rows.indexOfFirst { ui.sel in it }.takeIf { it >= 0 } ?: return
        val c = rows[r].indexOf(ui.sel)
        if (dy != 0) {
            val nr = (r + dy).coerceIn(0, rows.size - 1)
            if (nr == r) return
            // Posición horizontal de 0 a 1 para pasar entre filas de distinto largo.
            val x = (c + 0.5f) / rows[r].size
            ui.sel = rows[nr][(x * rows[nr].size).toInt().coerceIn(0, rows[nr].size - 1)]
        } else if (dx != 0) {
            // En el borde de los recientes, pasa a las siguientes (la selección queda en el mismo lugar).
            if (c + dx !in rows[r].indices && ui.items[ui.sel].section == PanelRow.RECENT && pageRecents(dx)) {
                ui.sel = panelRows(ui.items).getOrNull(r)?.let { it[c.coerceAtMost(it.size - 1)] } ?: ui.sel
                return
            }
            ui.sel = rows[r][(c + dx).coerceIn(0, rows[r].size - 1)]
        }
    }

    /** Primera reciente a la vista: se ven de a [COLUMNS] y se pasa de una en una. */
    private var recentStart = 0
    private var recentTotal = 0

    /** Corre los recientes una posición; false si ya estaba en el borde. */
    private fun pageRecents(dx: Int): Boolean {
        val next = (recentStart + dx).coerceIn(0, (recentTotal - COLUMNS).coerceAtLeast(0))
        if (next == recentStart) return false
        recentStart = next
        ui.items = items()
        return true
    }

    /** Con el dedo en una barra: fija el nivel. */
    private fun setLevel(i: Int, f: Float) {
        poke()
        ui.sel = i
        ui.items.getOrNull(i)?.set?.invoke(f.coerceIn(0f, 1f))
        refresh()
    }

    /** Vuelve a armar los ítems (cambió un valor: brillo, volumen) sin mover la selección. */
    private fun refresh() {
        if (!isOpen || inPerfMenu) return
        // La selección sigue en el mismo mosaico aunque la lista cambie (al cerrar una reciente se corría).
        val sel = ui.sel
        val label = ui.items.getOrNull(sel)?.label
        ui.items = items()
        ui.sel = ui.items.indexOfFirst { it.label == label }.takeIf { it >= 0 } ?: sel.coerceAtMost(ui.items.size - 1)
    }

    /** App reciente marcada para cerrar (primer Y); se olvida a los 3 s o al moverse. */
    private var closeArmed: String? = null
    private val disarm = Runnable { if (closeArmed != null) { closeArmed = null; refresh() } }

    /**
     * Cerrar una app reciente desde el panel, como Detener en Memoria: Y la marca y otro Y la cierra (para no cerrar
     * un juego sin querer). Sale de la lista; detenerla de verdad necesita Shizuku (sin él solo se quita de la lista).
     */
    private fun closeRecent(pkg: String) {
        main.removeCallbacks(disarm)
        if (closeArmed != pkg) { closeArmed = pkg; main.postDelayed(disarm, 3000); refresh(); return }
        closeArmed = null
        val name = RecentApps.label(svc, pkg)
        if (pkg == RaProxy.PACKAGE && RaProxy.activeNow == true) {
            toast(tr("Apágalo con Stop proxy en su app: cerrado así, los emuladores quedan sin logros", "Turn it off with Stop proxy in its app: closed like this, emulators lose achievements"))
            refresh(); return
        }
        RecentApps.forget(svc, pkg)
        if (Shell.state == Shell.State.READY) {
            scope.launch { Memory.stop(svc, pkg) }
            toast(tr("$name cerrada", "$name closed"))
        } else toast(tr("$name quitada de recientes (cerrarla necesita Shizuku)", "$name removed from recents (closing it needs Shizuku)"))
        refresh()
    }

    /**
     * Con audífonos, Android no deja pasar de ~73 % sin confirmar su aviso de volumen alto (volumen seguro), y sin su
     * interfaz el panel solo se quedaba pegado. Se cierra el panel y se pide subir con la interfaz de Android, que
     * muestra el aviso para aceptarlo (con el dedo o el control). Después ya sube normal (Android lo recuerda ~20 h).
     */
    private fun safeVolumeAsk() {
        hide()
        main.postDelayed({ SystemLevels.raiseWithUi(svc) }, 250)
    }

    private fun poke() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 10_000)
    }

    /** Acciones según lo que hay ahora: el panel de ZUI solo en modo juego, los modos según el actual. */
    private fun items(): List<PanelItem> = buildList {
        // Apps usadas hace poco (sin la de ahora): volver a ellas como a un juego en suspensión.
        // Y (o mantener con el dedo) dos veces la cierra: la primera la marca, la segunda confirma.
        // Sin RAOfflineProxy ni Shizuku: son servicios que tienen que quedar abiertos (cerrarlos rompe los logros o
        // lo que usa Shizuku); se abren desde Logros o desde Ajustes.
        val recents = RecentApps.list(svc).filter { it != svc.foregroundApp && it != RaProxy.PACKAGE && it != Shell.PACKAGE }
        recentTotal = recents.size
        recentStart = recentStart.coerceIn(0, (recents.size - COLUMNS).coerceAtLeast(0))
        ui.recentInfo = if (recents.size > COLUMNS) tr("${recentStart + 1}–${minOf(recentStart + COLUMNS, recents.size)} de ${recents.size}",
            "${recentStart + 1}–${minOf(recentStart + COLUMNS, recents.size)} of ${recents.size}") else null
        recents.drop(recentStart).take(COLUMNS).forEach { pkg ->
            val armed = closeArmed == pkg
            add(PanelItem(RecentApps.label(svc, pkg), Icons.Outlined.Layers, PanelRow.RECENT,
                sub = if (armed) tr("¿Cerrar? Pulsa Y", "Close? Press Y") else tr("Volver", "Resume"),
                danger = armed, appIcon = RecentApps.icon(svc, pkg), alt = { closeRecent(pkg) }) { hide(); RecentApps.open(svc, pkg) })
        }
        // Arriba, brillo y volumen como barras (se arrastran con el dedo o se ajustan con A y ← →).
        // Cada barra con su botón al lado (brillo automático, silencio), que también se toca con el dedo.
        if (SystemLevels.canBrightness(svc)) {
            val auto = SystemLevels.autoBrightness(svc)
            val b = SystemLevels.brightness(svc)
            add(PanelItem(
                tr("Brillo", "Brightness"), Icons.Outlined.BrightnessMedium, PanelRow.SLIDER, sub = if (auto) tr("Auto · $b %", "Auto · $b %") else "$b %",
                adjust = { SystemLevels.stepBrightness(svc, it) }, level = b / 100f, set = { SystemLevels.setBrightness(svc, it) },
                hint = tr("← → ajustar · A o B listo", "← → adjust · A or B done")
            ))
            add(PanelItem(tr("Brillo automático", "Auto brightness"), Icons.Outlined.BrightnessAuto, PanelRow.SLIDER, active = auto) {
                SystemLevels.setAutoBrightness(svc, !auto)
                // En un juego: también lo que hace Game Assistant al entrar a los juegos (apagarlo o no), y se avisa.
                if (ZuiGame.inGame(svc) && SystemLevels.setAutoInGames(svc, !auto).isSuccess)
                    toast(if (!auto) tr("Brillo automático: también en los juegos", "Auto brightness: also in games")
                        else tr("Game Assistant lo apagará en los juegos", "Game Assistant will turn it off in games"))
                refresh()
            })
        }
        val vol = SystemLevels.volume(svc)
        add(PanelItem(
            tr("Volumen", "Volume"), Icons.AutoMirrored.Outlined.VolumeUp, PanelRow.SLIDER, sub = if (vol == 0) tr("Silencio", "Muted") else "$vol %",
            adjust = { if (!SystemLevels.stepVolume(svc, it)) safeVolumeAsk() }, level = vol / 100f,
            set = { if (!SystemLevels.setVolume(svc, it)) safeVolumeAsk() },
            hint = tr("← → ajustar · A o B listo", "← → adjust · A or B done")
        ))
        add(PanelItem(tr("Silenciar", "Mute"), Icons.AutoMirrored.Outlined.VolumeOff, PanelRow.SLIDER, active = vol == 0) {
            SystemLevels.toggleMute(svc); refresh()
        })
        // Sin Shizuku lo que lo necesita no sale; en su lugar, un solo mosaico resaltado para activarlo (si la app de
        // Shizuku está instalada y no se apagó ese aviso en Ajustes).
        val ready = Shell.state == Shell.State.READY
        if (!ready && Shell.launchIntent(svc) != null && AutoRules.shizukuTile(svc))
            add(PanelItem(if (Shell.state == Shell.State.NO_PERMISSION) tr("Dar permiso", "Grant permission") else tr("Activar Shizuku", "Start Shizuku"),
                Icons.Outlined.PowerSettingsNew, active = true, sub = tr("Bypass, rendimiento y más", "Bypass, performance and more"), run = ::fixShizuku))
        // El juego. La captura toma solo la ventana del juego: no hace falta esperar a que el panel desaparezca.
        add(PanelItem(tr("Captura", "Screenshot"), Icons.Outlined.Screenshot, sub = tr("Guía + RB", "Guide + RB")) { hide(); screenshot() })
        // Grabar con la grabadora de Legion (ZUI): empezar o parar.
        val rec = recState() == 1
        add(PanelItem(if (rec) tr("Parar grabación", "Stop recording") else tr("Grabar", "Record"), Icons.Outlined.Videocam,
            sub = if (rec) tr("Grabando · Guía + LT", "Recording · Guide + LT") else tr("Guía + LT", "Guide + LT"), active = rec, danger = rec) { toggleRecord() })
        // Shadow Rec de Game Assistant (en un juego): encendido, A guarda los últimos segundos e Y lo apaga.
        if (ZuiGame.inGame(svc)) {
            val shadow = shadowOn()
            add(PanelItem("Shadow Rec", Icons.Outlined.History, active = shadow,
                sub = if (shadow) tr("Guardar clip · Y apagar", "Save clip · Y turn off") else tr("Apagado · Guía + RT", "Off · Guide + RT"),
                alt = { if (shadowOn()) { hide(); shadowToggle() } }) { shadowAction() })
        }
        // Logros sin internet (RAOfflineProxy): en un emulador, si está instalado. Encendido = en color; A lo abre.
        if (RaProxy.isEmulator(svc.foregroundApp) && RaProxy.installed(svc)) {
            val on = RaProxy.activeNow
            // Encendido pero al emulador le falta algo (sesión, apuntar al proxy): en rojo y qué falta.
            val problem = RaProxy.setupNow?.takeIf { on == true }?.problem
                ?: if (on == false && RaProxy.strandedNow) tr("RAOfflineProxy está apagado y tus emuladores le apuntan: sin logros ni con internet. Ábrelo: se vuelve a encender solo.",
                    "RAOfflineProxy is off and your emulators point to it: no achievements even online. Open it and press Start (or Stop).") else null
            add(PanelItem(tr("Logros", "Achievements"), Icons.Outlined.Star,
                sub = problem?.let { if (on == false) tr("Apagado: sin logros", "Off: no achievements") else tr("Falta algo: ver aviso", "Something's missing") } ?: when (on) {
                    true -> tr("Sin internet: activo", "Offline: on"); false -> tr("Sin internet: apagado", "Offline: off"); null -> "RAOfflineProxy" },
                active = on == true && problem == null, danger = problem != null) {
                // Apagado: A lo abre igual (para Start); si falta algo en el emulador, A dice qué.
                if (problem != null && on != false) toast(problem) else { hide(); RaProxy.open(svc) }
            })
        }
        if (ZuiGame.inGame(svc)) {
            // Oculto: abrirlo. Visible: solo ocultarlo (abrirlo ya está a mano con su pestaña).
            val gaVisible = gaShownByUs || ZuiGame.hiddenNow == false
            if (!gaVisible) add(PanelItem("Game Assistant", Icons.Outlined.Tune, sub = tr("Abrir", "Open")) { afterHide { openGameAssistant() } })
            else
                add(PanelItem(tr("Ocultar", "Hide"), Icons.Outlined.VisibilityOff, sub = "Game Assistant") {
                    hide()
                    gaShownByUs = false
                    scope.launch {
                        if (!ZuiGame.setHidden(true)) toast(tr("No se pudo ocultar Game Assistant.", "Couldn't hide Game Assistant."))
                        else AutoRules.setGaHidden(svc, true)
                    }
                })
            // Cambiar el modo desde aquí necesita Shizuku (mostrar Game Assistant si está oculto y leer su registro).
            // Sin él y con Game Assistant a la vista: abre su panel, que tiene el botón de modo. Oculto y sin Shizuku: no sale.
            if (ready)
                add(PanelItem(tr("Rendimiento", "Performance"), Icons.Outlined.Speed,
                    sub = ZuiGame.knownPerf(svc.foregroundApp)?.label ?: tr("Elegir", "Choose")) { crossfade { showPerfMenu() } })
            else if (AutoRules.gaHidden(svc) != true)
                add(PanelItem(tr("Rendimiento", "Performance"), Icons.Outlined.Speed, sub = tr("En Game Assistant", "In Game Assistant")) { afterHide { openGameAssistant() } })
        }
        // Bypass (con Shizuku): con la tablet enchufada, o si ya está activo para poder quitarlo.
        val bypassOn = ZuiBattery.bypassNow == true
        if (ready && (bypassOn || ZuiBattery.plugged(svc)))
        {
            // Con algo automático (bypass automático o límite de carga) son tres estados: Automático → Encendido →
            // Apagado → Automático. Así lo manual siempre puede volver a lo de siempre. Sin nada automático, encendido o apagado.
            val hasAuto = svc.bypassHasAuto()
            val isAuto = hasAuto && !ZuiBattery.manualOverride
            add(PanelItem("Bypass", Icons.Outlined.Power, active = bypassOn, sub = when {
                isAuto -> if (bypassOn) tr("Automático · activo", "Automatic · on") else tr("Automático", "Automatic")
                bypassOn -> tr("Encendido", "On")
                else -> tr("Apagado", "Off")
            }) {
                hide()
                if (!isAuto && !bypassOn && hasAuto) {
                    svc.bypassToAuto()
                    toast(tr("Bypass automático: se activa cuando corresponde.", "Automatic bypass: turns on when it should."))
                    return@PanelItem
                }
                // Desde el automático, el primer toque siempre lo enciende (aunque ya lo estuviera: pasa a ser tuyo).
                val want = if (isAuto) true else !bypassOn
                scope.launch {
                    if (!ZuiBattery.setBypass(want)) toast(tr("No se pudo cambiar el bypass.", "Couldn't change bypass."))
                    else toast(if (want) tr("Bypass encendido: el cargador alimenta la tablet y la batería no carga.", "Bypass on: the charger powers the tablet and the battery doesn't charge.")
                        else tr("Bypass apagado: la batería vuelve a cargar.", "Bypass off: the battery charges again."))
                }
            })
        }
        // Monitor de rendimiento sobre el juego (FPS con Shizuku; lo demás siempre).
        val hud = AutoRules.perfHud(svc)
        add(PanelItem(tr("Monitor", "Monitor"), Icons.Outlined.QueryStats, active = hud,
            sub = if (hud) tr("Encendido", "On") else tr("Apagado", "Off")) {
            AutoRules.setPerfHud(svc, !hud); refresh()
        })
        // Frecuencia de la pantalla para esta app (se recuerda por juego).
        val hzNow = RefreshRate.current(svc)
        val hzChosen = RefreshRate.forApp(svc, svc.foregroundApp)
        add(PanelItem(tr("Frecuencia", "Refresh rate"), Icons.Outlined.DisplaySettings, active = hzChosen != null,
            sub = run {
                    // La de esta app, la de todos los juegos (Tablet → Juego) o la que haya.
                    val games = RefreshRate.forGames(svc)?.takeIf { hzChosen == null && ZuiGame.inGame(svc) }
                    when {
                        hzChosen != null -> tr("$hzChosen Hz · esta app", "$hzChosen Hz · this app")
                        games != null -> tr("$games Hz · juegos", "$games Hz · games")
                        else -> hzNow?.let { "$it Hz" } ?: "—"
                    }
                }) { crossfade { showHzMenu() } })
        if (Aspect.available(svc)) {
            val wide = Aspect.is169(svc)
            add(PanelItem("16:9", Icons.Outlined.AspectRatio, PanelRow.SCREEN,
                sub = if (wide) tr("Encendida", "On") else tr("Apagada", "Off"), active = wide) {
                hide()
                scope.launch { if (!Aspect.toggle(svc)) toast(tr("No se pudo cambiar la pantalla.", "Couldn't change the screen.")) }
            })
        }
        // Proporción de esta app (WideVision de ZUI): Completa → 16:9 → 4:3, sin tocar la resolución de la tablet. Se
        // aplica al volver a abrir el juego (no se reinicia solo: se perdería la partida).
        svc.foregroundApp?.takeIf { it != svc.packageName && ZuiGame.inGame(svc) }?.let { pkg ->
            val r = GameAspect.get(svc, pkg)
            add(PanelItem(tr("Proporción", "Aspect"), Icons.Outlined.AspectRatio, PanelRow.SCREEN,
                sub = r.label + tr(" · este juego", " · this game"), active = r != GameAspect.Ratio.FULL) {
                val next = r.next()
                scope.launch {
                    if (GameAspect.set(svc, pkg, next)) { refresh(); toast(tr("${next.label}: se ve al volver a abrir el juego", "${next.label}: shows when you reopen the game")) }
                    else toast(tr("Necesita Shizuku", "Needs Shizuku"))
                }
            })
        }
        // Con un monitor en espejo y la pantalla de la tablet oscurecida (o encendida a mano): verla un rato o volver.
        if (Monitor.screen(svc) != Monitor.Screen.NORMAL && Monitor.current(svc) != null && Monitor.mirror(svc)) {
            val dark = svc.tabletDark
            add(PanelItem(tr("Tablet oscura", "Tablet dark"), Icons.Outlined.TabletAndroid, PanelRow.SCREEN,
                sub = if (dark) tr("Con el monitor", "With the monitor") else tr("Encendida", "On"), active = dark) {
                hide(); svc.toggleTabletScreen()
            })
        }
        // Abajo, Android: para no tener que tocar la pantalla (con la tablet en un dock, por ejemplo).
        add(PanelItem(tr("Atrás", "Back"), Icons.AutoMirrored.Outlined.ArrowBack, PanelRow.SYSTEM) {
            afterHide { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        })
        add(PanelItem(tr("Inicio", "Home"), Icons.Outlined.Home, PanelRow.SYSTEM) { hide(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) })
        add(PanelItem(tr("Recientes", "Recents"), Icons.Outlined.Layers, PanelRow.SYSTEM) { hide(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) })
        add(PanelItem(tr("Ajustes rápidos", "Quick settings"), Icons.Outlined.ToggleOn, PanelRow.SYSTEM) {
            hide(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        })
        // Los ajustes de Android, para no tener que salir del juego a buscarlos.
        add(PanelItem(tr("Ajustes", "Settings"), Icons.Outlined.Settings, PanelRow.SYSTEM) {
            hide(); runCatching { svc.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        // WiFi y Bluetooth: con Shizuku se encienden y apagan aquí mismo; sin él, abre el panel de Android.
        val cr = svc.contentResolver
        val wifi = android.provider.Settings.Global.getInt(cr, "wifi_on", 0) != 0
        val bt = android.provider.Settings.Global.getInt(cr, "bluetooth_on", 0) != 0
        // A abre la lista de Android (redes o dispositivos: elegir una red o conectar un dispositivo solo lo puede
        // hacer el sistema); Y enciende o apaga ahí mismo (con Shizuku).
        val wifiList = { hide(); openSettings(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY) }
        val btList = { hide(); openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }
        val ySwitch = if (Shell.state == Shell.State.READY) tr(" · Y apagar", " · Y off") to tr(" · Y encender", " · Y on") else "" to ""
        add(PanelItem("WiFi", if (wifi) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, PanelRow.SCREEN, active = wifi,
            sub = tr("Redes", "Networks") + if (wifi) ySwitch.first else ySwitch.second,
            alt = { radio(!wifi, Shell.WIFI_ON, Shell.WIFI_OFF) }, run = wifiList))
        add(PanelItem("Bluetooth", if (bt) Icons.Outlined.Bluetooth else Icons.Outlined.BluetoothDisabled, PanelRow.SCREEN, active = bt,
            sub = tr("Dispositivos", "Devices") + if (bt) ySwitch.first else ySwitch.second,
            alt = { radio(!bt, Shell.BT_ON, Shell.BT_OFF) }, run = btList))
        // Bloquear gestos: el "Disable navigation bar and gesture" de Game Assistant (en sus juegos), un ajuste del
        // sistema que se puede cambiar con el permiso de pantalla.
        if (ZuiGame.installed(svc) && Aspect.canWrite(svc)) {
            val locked = ZuiGame.gesturesLocked(svc)
            add(PanelItem(tr("Bloquear gestos", "Lock gestures"), if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, PanelRow.GAME,
                active = locked, sub = if (locked) tr("En juegos", "In games") else tr("Apagado", "Off")) {
                ZuiGame.setGesturesLocked(svc, !locked); refresh()
            })
        }
        // Mouse virtual: para apps que no usan el control (los sticks dejan de llegar a la app mientras está).
        val mouseOn = svc.isMouseOn
        add(PanelItem(tr("Mouse", "Mouse"), Icons.Outlined.Mouse, PanelRow.SCREEN, active = mouseOn,
            sub = if (mouseOn) tr("Encendido", "On") else tr("Apagado", "Off")) {
            hide(); svc.toggleMouse()
            if (!mouseOn) toast(tr("Mouse: stick izquierdo mueve, A toca (mantener: toque largo; mantener y mover: arrastra), stick derecho desplaza, B atrás.",
                "Mouse: left stick moves, A taps (hold: long press; hold and move: drag), right stick scrolls, B back."))
        })
        val mode = ModeSwitcher.detect()
        // Un solo mosaico para el modo del G9: dice el actual y pasa al siguiente (Android → X-Input → DS5 → Android).
        if (mode != null) {
            val next = PadMode.entries[(PadMode.entries.indexOf(mode) + 1) % PadMode.entries.size]
            add(PanelItem("G9 · ${mode.label}", Icons.Outlined.SportsEsports, PanelRow.SYSTEM, sub = "→ ${next.label}") { hide(); svc.manualSwitch(next) })
        }
        // Abrir TBF (no si ya está al frente: el panel se abrió dentro de la app).
        if (svc.foregroundApp != svc.packageName) add(PanelItem("TBF Companion", Icons.AutoMirrored.Outlined.OpenInNew, PanelRow.SYSTEM) {
            hide()
            svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }

    /** Quita el panel y espera a que desaparezca de la pantalla antes de seguir (si no, sale en la captura). */
    private fun afterHide(action: () -> Unit) {
        hide()
        main.postDelayed(action, 300)
    }

    /** La captura la guardó el emulador (sin shaders): solo el sonido, el emulador ya muestra su propio aviso. */
    fun gameShotNotice() = sounds.play(AutoRules.shotSound(svc))

    /** Captura limpia desde fuera del panel (M dos veces en DS5, Legion + RB). */
    fun capture() = screenshot()

    /** Aviso corto desde el servicio (el mismo de las acciones del panel). */
    fun message(text: String) = toast(text)

    /** Guarda las capturas fuera del hilo principal: codificar un PNG de 2560 × 1600 tarda unos cientos de ms. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val flash = ShotFlash(svc)
    private val sounds = ShotSounds(svc)

    /**
     * Captura solo la ventana del juego (Android 14+): sin el puntero del mouse ni superposiciones (Game
     * Assistant, este panel). Si Android no la entrega, la captura normal de toda la pantalla.
     */
    private fun screenshot() {
        // Android da una captura por segundo a los servicios de accesibilidad: más seguido falla (y antes se caía a
        // la captura del sistema). Si es muy pronto, la siguiente espera su turno; como mucho una en espera.
        val now = android.os.SystemClock.uptimeMillis()
        if (now < nextShot) {
            if (!shotQueued) { shotQueued = true; main.postDelayed({ shotQueued = false; screenshot() }, nextShot - now) }
            return
        }
        nextShot = now + 1100
        // La ventana con el foco ahora (la del evento puede ser una pasajera que ya no existe).
        val id = focusedAppWindow().takeIf { it >= 0 } ?: svc.appWindowId
        // Un launcher (Cocoon, el de ZUI) deja ver el fondo de pantalla, que es otra ventana: con solo la suya el
        // fondo sale negro. Ahí, toda la pantalla (el panel ya se quitó; el puntero puede salir).
        // Recién arrancado el servicio (al actualizar la app) no sabe qué app está al frente hasta que se cambia de app:
        // ahí se le pregunta a la ventana con el foco.
        val launcher = isLauncher(svc.foregroundApp ?: focusedAppPackage())
        if (Build.VERSION.SDK_INT >= 34 && (id >= 0 || launcher)) {
            Log.d(AutoModeService.TAG, if (launcher) "captura de la pantalla (launcher)" else "captura de la ventana $id (del evento: ${svc.appWindowId})")
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val saved = runCatching {
                        val bmp = try {
                            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace) ?: error("sin imagen")
                            hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } ?: error("sin copia")
                        } finally {
                            r.hardwareBuffer.close()
                        }
                        // El aviso (con su miniatura) apenas hay imagen; guardar el PNG sigue en segundo plano.
                        val thumb = flash.thumbnail(bmp)
                        main.post { flash.show(thumb); sounds.play(AutoRules.shotSound(svc)) }
                        try { saveScreenshot(bmp) } finally { bmp.recycle() }
                    }
                    saved.onFailure {
                        Log.w(AutoModeService.TAG, "no se guardó la captura", it)
                        main.post { flash.hide(); toast(tr("No se pudo guardar la captura.", "Couldn't save the screenshot.")) }
                    }
                }
                override fun onFailure(code: Int) {
                    Log.w(AutoModeService.TAG, "captura de la ventana $id falló (código $code)")
                    // Muy seguido: se reintenta en su turno, no con la captura del sistema (que sale distinta).
                    if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) main.post {
                        nextShot = android.os.SystemClock.uptimeMillis() + 1100
                        if (!shotQueued) { shotQueued = true; main.postDelayed({ shotQueued = false; screenshot() }, 1100) }
                    } else main.post { fullScreenshot() }
                }
            }
            if (launcher) svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, io, callback)
            else svc.takeScreenshotOfWindow(id, io, callback)
            return
        }
        Log.w(AutoModeService.TAG, "sin ventana para capturar (id $id)")
        fullScreenshot()
    }

    /** La app es un launcher (responde a Inicio): su fondo es el fondo de pantalla del sistema. */
    private val launchers: Set<String> by lazy {
        svc.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }
    private fun isLauncher(pkg: String?) = pkg != null && pkg in launchers

    /** Cuándo se puede sacar la próxima captura, y si ya hay una esperando su turno. */
    private var nextShot = 0L
    private var shotQueued = false

    /** Ventana de app con el foco (o activa), de la lista de ventanas; solo su número, no su contenido. */
    private fun focusedAppWindow(): Int {
        val all = runCatching { svc.windows }.getOrDefault(emptyList())
        Log.d(AutoModeService.TAG, "ventanas: " + all.joinToString { "${it.id}/t${it.type}/f${if (it.isFocused) 1 else 0}a${if (it.isActive) 1 else 0}" })
        val apps = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return (apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull())?.id ?: -1
    }

    /** Paquete de la ventana de app con el foco (null si no se sabe). */
    private fun focusedAppPackage(): String? = runCatching {
        val apps = svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        (apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive })?.root?.packageName?.toString()
    }.getOrNull()

    private fun fullScreenshot() {
        if (!svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) toast(tr("No se pudo sacar la captura.", "Couldn't take the screenshot."))
    }

    /** Guarda la captura en Pictures/Screenshots, junto a las del sistema. */
    private fun saveScreenshot(bmp: Bitmap) {
        val name = "Screenshot_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = svc.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore no creó el archivo")
        try {
            cr.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
    }

    // ——— Grabación ———
    // Dos, que no pueden ir juntas (con Shadow Rec encendido la grabadora de Legion dice "Game recording in progress"):
    // la grabadora de Legion (el primer mosaico de los ajustes rápidos; guarda en Pictures/screenrecorder) y Shadow
    // Rec de Game Assistant (graba siempre y guarda los últimos segundos en Movies/Highlight). Ninguna se puede
    // llamar desde otra app (servicios con permisos de sistema): se manejan como lo haría el dedo, buscando sus
    // botones por nombre con la accesibilidad.

    /** Estado de la grabadora de Legion (Settings.System, sin permisos): 0 o -1 nada, 2 su control a la vista, 1 grabando. */
    private fun recState() = runCatching { android.provider.Settings.System.getInt(svc.contentResolver, "screenrecordstate") }.getOrDefault(-1)

    /** Un botón de otra app en pantalla, por id o por texto (el primero que aparezca). */
    private fun findNode(pkg: String, texts: List<String> = emptyList(), id: String? = null): android.view.accessibility.AccessibilityNodeInfo? {
        for (w in runCatching { svc.windows }.getOrDefault(emptyList())) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != pkg) continue
            id?.let { root.findAccessibilityNodeInfosByViewId(it).firstOrNull()?.let { n -> return n } }
            for (t in texts) root.findAccessibilityNodeInfosByText(t).firstOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun tapNode(n: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /**
     * Shadow Rec encendido: su botón "»" está en el borde. Con Game Assistant oculto no se ve (aunque sigue grabando):
     * entonces vale lo último que se supo (al encenderlo o apagarlo desde aquí, o al verlo).
     */
    private var shadowKnown = false
    private fun shadowOn(): Boolean {
        if (findNode(ZuiGame.PACKAGE, id = SHADOW_FLOAT) != null) shadowKnown = true
        else if (ZuiGame.hiddenNow == false || gaShownByUs) shadowKnown = false
        return shadowKnown
    }

    /**
     * Hace [block] (gestos sobre otra app) detrás de la imagen congelada del juego, como el cambio de rendimiento:
     * así no se ven los ajustes rápidos ni el panel de Game Assistant. Los botones del control no llegan al juego
     * mientras tanto ([busy]). Como mucho 15 s. Lo grabado (la grabadora o Shadow Rec) puede mostrar un instante la
     * imagen congelada al empezar o al terminar.
     */
    private suspend fun covered(label: String, fail: String, hint: String, quick: Boolean = false, block: suspend () -> Boolean): Boolean {
        if (perfBusy) return false
        perfBusy = true
        val cover = FreezeCover(svc)
        var ok = false
        try {
            delay(300)   // que el panel desaparezca antes de congelar
            cover.show(screenshotDisplay(), label, fail, hint)
            delay(200)
            ok = withTimeoutOrNull(15_000) { block() } ?: false
        } finally {
            cover.done(ok, quick)
            perfBusy = false
        }
        return ok
    }

    /**
     * Grabadora de Legion: grabando, la para con su mosaico de los ajustes rápidos y cierra el control que queda; si
     * no, abre ese mosaico y toca el botón rojo de su control. Todo se busca por nombre o id con la accesibilidad y se
     * toca en el centro de lo que se encontró (no en coordenadas fijas): sirve con otra resolución, en 16:9 o con el
     * control movido. Si ZUI cambia esos nombres, avisa que no lo encontró en vez de tocar al azar.
     */
    fun toggleRecord() {
        hide()
        scope.launch {
            if (shadowOn()) return@launch toast(tr("Apaga Shadow Rec para grabar: no pueden ir juntos", "Turn off Shadow Rec to record: they can't run together"))
            val was = recState()
            var why: String? = null
            val ok = covered(if (was == 1) tr("Parando la grabación", "Stopping the recording") else tr("Empezando a grabar", "Starting to record"),
                tr("No se pudo grabar", "Couldn't record"), tr("Prueba con la grabadora de los ajustes rápidos", "Try the recorder in Quick Settings"), quick = was != 1) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                var tile: android.view.accessibility.AccessibilityNodeInfo? = null
                repeat(10) { if (tile == null) { delay(200); tile = findNode("com.android.systemui", REC_TILE) } }
                val t = tile
                if (t == null) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    why = tr("No encontré Grabar pantalla en los ajustes rápidos", "Couldn't find Screen record in Quick Settings")
                    return@covered false
                }
                if (!t.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) tapNode(t)
                delay(500)
                if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                // Su control flotante (ajustes · rojo · X): sin nombres por dentro, así que el rojo es su centro y la X
                // su quinto derecho, siempre relativos a dónde esté (se puede mover).
                var ctl: android.view.accessibility.AccessibilityNodeInfo? = null
                repeat(15) { if (ctl == null) { delay(200); ctl = findNode("com.android.systemui", id = REC_FLOAT) } }
                val c = ctl
                if (was == 1) {
                    c?.let { val r = android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }; tap(r.right - r.width() * 0.2f, r.exactCenterY()) }
                    delay(400)
                    return@covered recState() != 1
                }
                if (c == null) { why = tr("La grabadora no se abrió", "The recorder didn't open"); return@covered false }
                delay(1200)   // su animación de entrada: antes no recibe el toque
                tapNode(c)
                var w = 0
                while (recState() != 1 && w++ < 10) delay(200)
                recState() == 1
            }
            if (why != null) toast(why!!) else if (ok) toast(if (was == 1) tr("Grabación guardada en Pictures/screenrecorder", "Recording saved in Pictures/screenrecorder")
                else tr("Grabando · Guía + LT para parar", "Recording · Guide + LT to stop"))
        }
    }

    /** Shadow Rec: encendido, guarda el clip; apagado, lo enciende. */
    fun shadowAction() {
        hide()
        if (!ZuiGame.inGame(svc)) return toast(tr("Shadow Rec funciona en los juegos de Game Assistant", "Shadow Rec works in Game Assistant's games"))
        scope.launch {
            var msg: String? = null
            covered(tr("Shadow Rec", "Shadow Rec"), tr("No se pudo usar Shadow Rec", "Couldn't use Shadow Rec"), tr("Prueba desde el panel de Game Assistant", "Try from Game Assistant's panel"), quick = true) {
                var ok = false
                withGa {
                    val f = findNode(ZuiGame.PACKAGE, id = SHADOW_FLOAT)
                    if (f == null) { ok = shadowToggleShown().also { msg = it.second }.first; return@withGa }
                    // Su "»" se despliega en "Back Rec.", que guarda los últimos segundos.
                    tapNode(f)
                    var b: android.view.accessibility.AccessibilityNodeInfo? = null
                    repeat(8) { if (b == null) { delay(150); b = findNode(ZuiGame.PACKAGE, SHADOW_SAVE) } }
                    val bb = b ?: run { msg = tr("No encontré el botón de guardar de Shadow Rec", "Couldn't find Shadow Rec's save button"); return@withGa }
                    tapNode(bb)
                    ok = true
                    msg = tr("Clip guardado en Movies/Highlight", "Clip saved in Movies/Highlight")
                }
                ok
            }
            msg?.let { toast(it) }
        }
    }

    /** Enciende o apaga Shadow Rec desde el panel de Game Assistant (su botón, por nombre). */
    private fun shadowToggle() {
        scope.launch {
            var msg: String? = null
            covered("Shadow Rec", tr("No se pudo usar Shadow Rec", "Couldn't use Shadow Rec"), tr("Prueba desde el panel de Game Assistant", "Try from Game Assistant's panel"), quick = true) {
                var ok = false
                withGa { val (o, m) = shadowToggleShown(); ok = o; msg = m }
                ok
            }
            msg?.let { toast(it) }
        }
    }

    /** Toca "Shadow Rec" en el panel de Game Assistant. Devuelve si se pudo y qué decir. */
    private suspend fun shadowToggleShown(): Pair<Boolean, String> {
        val was = shadowOn()
        val bar = ZuiGame.awaitSideBar(svc) ?: return false to tr("No se pudo abrir Game Assistant.", "Couldn't open Game Assistant.")
        if (!swipe(bar, open = true)) return false to tr("No se pudo abrir Game Assistant.", "Couldn't open Game Assistant.")
        var item: android.view.accessibility.AccessibilityNodeInfo? = null
        repeat(10) { if (item == null) { delay(150); item = findNode(ZuiGame.PACKAGE, listOf("Shadow Rec")) } }
        val i = item
        if (i == null) {
            swipe(bar, open = false)
            return false to tr("Agrega Shadow Rec a su panel: Game Assistant → Shortcut sort", "Add Shadow Rec to its panel: Game Assistant → Shortcut sort")
        }
        tapNode(i)
        shadowKnown = !was
        delay(900)
        // Al tocarlo su panel se cierra solo; si quedó abierto, se cierra.
        if (findNode(ZuiGame.PACKAGE, listOf("Clean RAM", "Screen shot")) != null) swipe(bar, open = false)
        return true to if (was) tr("Shadow Rec apagado", "Shadow Rec off") else tr("Shadow Rec encendido · Guía + RT guarda los últimos segundos", "Shadow Rec on · Guide + RT saves the last seconds")
    }
    /** Con Game Assistant a la vista (si está oculto, se muestra y se vuelve a ocultar al terminar). */
    private suspend fun withGa(block: suspend () -> Unit) {
        val hidden = keepGaHidden() && !gaShownByUs
        if (hidden) {
            if (!ZuiGame.setHidden(false)) return toast(tr("Game Assistant está oculto y Shizuku no responde.", "Game Assistant is hidden and Shizuku isn't responding."))
            delay(600)
        }
        try { block() } finally { if (hidden) withContext(NonCancellable) { delay(1500); ZuiGame.setHidden(true) } }
    }

    /** G9 Control mostró Game Assistant (que el usuario tiene oculto) para abrir su panel: se oculta al cambiar de app. */
    private var gaShownByUs = false
    /** Cambiando el modo de rendimiento (son varios gestos seguidos). */
    private var perfBusy = false
    /** Mientras cambia el rendimiento la imagen está congelada: los botones del control no llegan al juego. */
    val busy get() = perfBusy

    /** Si Game Assistant debe quedar oculto. La primera vez se toma de cómo está ahora y se recuerda. */
    private suspend fun keepGaHidden(): Boolean {
        AutoRules.gaHidden(svc)?.let { return it }
        val now = ZuiGame.hidden() ?: return false
        AutoRules.setGaHidden(svc, now)
        return now
    }

    /**
     * Abre el panel de Game Assistant. Si el usuario lo tiene oculto (appops) y Shizuku está listo, primero lo
     * muestra; se vuelve a ocultar al cambiar de app ([onAppChanged]).
     */
    private fun openGameAssistant() {
        if (!ZuiGame.inGame(svc)) return toast(tr("Game Assistant no está activo en esta app.", "Game Assistant isn't active in this app."))
        scope.launch {
            if (keepGaHidden()) {
                // Oculto y sin Shizuku: su panel se abriría invisible y parecería que no pasó nada.
                if (!ZuiGame.setHidden(false))
                    return@launch toast(tr("Game Assistant está oculto y Shizuku no responde.", "Game Assistant is hidden and Shizuku isn't responding."))
                gaShownByUs = true
                delay(400)
            }
            // Con Game Assistant oculto la pestaña a veces no se publica: se espera a que la publique al mostrarlo.
            val bar = ZuiGame.awaitSideBar(svc)
            // Si mientras tanto se volvió a abrir el panel, el deslizamiento caería sobre él: se cancela.
            if (isOpen) return@launch
            if (bar == null || !swipe(bar, open = true)) toast(tr("No se pudo abrir Game Assistant.", "Couldn't open Game Assistant."))
        }
    }

    /**
     * La app al frente cambió: si G9 Control mostró Game Assistant, lo vuelve a ocultar. Si es un juego, se lee ya
     * (en segundo plano) si Game Assistant se ve y en qué modo quedó, para que el panel lo sepa al abrirse.
     */
    fun onAppChanged() {
        if (gaShownByUs && !perfBusy) {
            gaShownByUs = false
            scope.launch { if (keepGaHidden()) ZuiGame.setHidden(true) }
        }
        val app = svc.foregroundApp
        scope.launch {
            delay(2500)   // ZUI decide si es juego y Game Assistant aplica el modo guardado de esa app
            if (svc.foregroundApp != app || !ZuiGame.inGame(svc) || Shell.state != Shell.State.READY) return@launch
            ZuiGame.hidden()
            ZuiGame.rememberPerf(app, ZuiGame.perfOf(ZuiGame.lastPerfLine()))
        }
    }

    /**
     * Submenú de modos de rendimiento. Marca el actual al instante con lo que ya se sabe de esta app (se lee en
     * segundo plano al abrir el panel y se anota al cambiarlo); leerlo del registro de Game Assistant tarda ~1 s.
     * Solo si todavía no se sabe se espera esa lectura.
     */
    private fun showPerfMenu() {
        keepHeight()
        inPerfMenu = true
        subFrom = Icons.Outlined.Speed
        fun build(current: ZuiGame.PerfMode?) = ZuiGame.PerfMode.entries.map { m ->
            val icon = when (m) {
                ZuiGame.PerfMode.SAVER -> Icons.Outlined.EnergySavingsLeaf
                ZuiGame.PerfMode.BALANCED -> Icons.Outlined.Balance
                ZuiGame.PerfMode.BEAST -> Icons.Outlined.Bolt
            }
            PanelItem(m.label, icon, sub = if (m == current) tr("Actual", "Current") else null, active = m == current) { setPerf(m) }
        }
        val app = svc.foregroundApp
        val known = ZuiGame.knownPerf(app)
        ui.sub = true; ui.footer = null
        ui.title = tr("Rendimiento", "Performance")
        // Cambiarlo pasa por Game Assistant detrás de una imagen fija del juego: que no parezca que se colgó.
        ui.footer = tr("Al cambiar, la imagen queda fija unos segundos · A elegir · B volver",
            "While it changes, the picture freezes for a few seconds · A select · B back")
        ui.items = build(known)
        ui.sel = known?.let { ZuiGame.PerfMode.entries.indexOf(it) } ?: 1
        poke()
        if (known == null) scope.launch {
            val cur = ZuiGame.perfOf(ZuiGame.lastPerfLine()) ?: return@launch
            ZuiGame.rememberPerf(app, cur)
            if (!isOpen || !inPerfMenu) return@launch
            ui.items = build(cur)
            ui.sel = ZuiGame.PerfMode.entries.indexOf(cur)
        }
    }

    /**
     * Cambia el modo de rendimiento como lo haría el dedo, detrás de una imagen congelada del juego (así no se ve
     * Game Assistant): lo muestra si está oculto, abre su panel, toca su botón de modo (que va pasando de uno a
     * otro) hasta que su registro dice el pedido (como mucho tres toques), cierra el panel deslizando hacia el
     * borde y lo vuelve a ocultar.
     */
    private fun setPerf(target: ZuiGame.PerfMode) {
        hide()
        if (!ZuiGame.inGame(svc)) return toast(tr("Game Assistant no está activo en esta app.", "Game Assistant isn't active in this app."))
        if (perfBusy) return
        perfBusy = true
        val cover = FreezeCover(svc)
        scope.launch {
            // Shizuku tiene que responder antes de congelar nada (si no, la imagen quedaba mucho rato).
            if (Shell.run(Shell.GA_GET) == null) {
                perfBusy = false
                toast(tr("Shizuku no responde: revisa que esté iniciado.", "Shizuku isn't responding: check that it's started."))
                return@launch
            }
            var now: ZuiGame.PerfMode? = null
            var hidden = false
            var opened = false
            var bar: ZuiGame.SideBar? = null
            try {
                delay(300)   // que el panel de G9 Control desaparezca antes de congelar la imagen
                cover.show(screenshotDisplay(), target.label)
                // La imagen aparece recién en el siguiente cuadro: si Game Assistant se muestra antes, su pestaña
                // alcanza a verse unos milisegundos.
                delay(200)
                hidden = keepGaHidden()
                // Como mucho unos segundos con la imagen congelada, pase lo que pase.
                withTimeoutOrNull(12_000) {
                    if (hidden && !ZuiGame.setHidden(false)) return@withTimeoutOrNull
                    delay(400)   // que Game Assistant vuelva a dibujar su pestaña
                    // Con Game Assistant oculto la pestaña a veces no se publica: se espera a que la publique.
                    val b = ZuiGame.awaitSideBar(svc) ?: return@withTimeoutOrNull
                    bar = b
                    if (!swipe(b, open = true)) return@withTimeoutOrNull
                    opened = true
                    delay(900)
                    var line = ZuiGame.lastPerfLine()
                    now = ZuiGame.perfOf(line)
                    val (x, y) = ZuiGame.perfButton(b, screenWidth(), screenHeight())
                    var taps = 0
                    while (now != target && taps < 3) {
                        tap(x, y)
                        taps++
                        // Espera a que Game Assistant escriba el modo nuevo (si no, el siguiente toque se pasaría).
                        var tries = 0
                        var next = ZuiGame.lastPerfLine()
                        // Una lectura fallida (Shizuku tardó, null) no es un cambio: seguir esperando. Si no, se daba un
                        // toque de más y pasaba de largo (pedido Ahorro, quedaba Equilibrado).
                        while ((next == null || next == line) && tries++ < 10) { delay(250); next = ZuiGame.lastPerfLine() }
                        if (next == null) break
                        line = next
                        now = ZuiGame.perfOf(line)
                    }
                    Log.d(AutoModeService.TAG, "modo de rendimiento: pedido $target, quedó $now ($taps toques)")
                    ZuiGame.rememberPerf(svc.foregroundApp, now)
                }
                // Solo si se abrió: si no, el deslizamiento le llegaría al juego.
                bar?.let { if (opened) { swipe(it, open = false); delay(400) } }
            } finally {
                // Aunque algo falle a mitad de camino, Game Assistant vuelve a quedar oculto.
                withContext(NonCancellable) { if (hidden && !gaShownByUs) ZuiGame.setHidden(true) }
                cover.done(now == target)
                perfBusy = false
            }
        }
    }

    /**
     * Imagen del juego ahora, para congelarla: solo su ventana (Android 14+), así no sale el panel mientras se
     * desvanece ni otra superposición; si no se puede, toda la pantalla. Como mucho una por segundo. Null si no se pudo.
     */
    private suspend fun screenshotDisplay(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val id = focusedAppWindow().takeIf { it >= 0 } ?: svc.appWindowId
        return suspendCancellableCoroutine { c ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val bmp = runCatching {
                        Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    }.getOrNull()
                    r.hardwareBuffer.close()
                    if (c.isActive) c.resume(bmp)
                }
                override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
            }
            if (Build.VERSION.SDK_INT >= 34 && id >= 0) svc.takeScreenshotOfWindow(id, io, callback)
            else svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, io, callback)
        }
    }

    /**
     * Deslizamiento sobre la pestaña lateral: abrir (desde el borde hacia el centro, como pide Game Assistant: al
     * menos 100 px y en menos de 500 ms) o cerrar (hacia el borde).
     */
    private suspend fun swipe(bar: ZuiGame.SideBar, open: Boolean): Boolean {
        val w = screenWidth()
        val edge = if (bar.right) w - 8f else 8f
        val inner = if (bar.right) edge - (if (open) 320f else 450f) else edge + (if (open) 320f else 450f)
        val path = Path().apply {
            if (open) { moveTo(edge, bar.centerY); lineTo(inner, bar.centerY) } else { moveTo(inner, bar.centerY); lineTo(edge, bar.centerY) }
        }
        return gesture(path, if (open) 120 else 150)
    }

    private suspend fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 50)

    private suspend fun gesture(path: Path, ms: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, main)
        if (!sent && c.isActive) c.resume(false)
    }

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }.x

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }.y

    private var noticeView: android.view.View? = null
    private val noticeOff = Runnable { noticeView?.let { runCatching { wm.removeView(it) } }; noticeView = null }

    /**
     * Aviso corto abajo al centro, en una ventana de accesibilidad: un Toast de Android no siempre se ve sobre un
     * juego en ZUI. No recibe toques.
     */
    private fun toast(text: String) {
        main.removeCallbacks(noticeOff)
        noticeOff.run()
        val dp = svc.resources.displayMetrics.density
        val v = android.widget.TextView(svc).apply {
            this.text = text
            setTextColor(if (light) 0xFF14203A.toInt() else android.graphics.Color.WHITE)
            textSize = 14f
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(if (light) 0xF2F3F6FB.toInt() else 0xEE0E1424.toInt()); cornerRadius = 16 * dp }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (48 * dp).toInt() }
        if (runCatching { wm.addView(v, lp) }.isFailure) return
        noticeView = v
        main.postDelayed(noticeOff, 2500)
    }

    private fun create() {
        val o = OverlayOwner().also { it.start() }
        val cv = ComposeView(svc).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                // Aparece desvaneciéndose (la animación de la ventana sola no siempre se ve al abrir).
                val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) { alpha.animateTo(1f, androidx.compose.animation.core.tween(160)) }
                // Con alto fijo (forma elegida o submenú) la tarjeta va al centro.
                // Lateral: entra deslizándose desde la derecha (como el acceso rápido del Steam Deck).
                val side = ThemeState.panelSide
                val slide = remember { androidx.compose.animation.core.Animatable(if (side) 1f else 0f) }
                LaunchedEffect(Unit) { if (side) slide.animateTo(0f, androidx.compose.animation.core.tween(220)) }
                Box(Modifier.fillMaxWidth().then(if (side || ui.lockedHeight || ThemeState.panelShape.ratio != null) Modifier.fillMaxHeight() else Modifier)
                    .graphicsLayer { this.alpha = if (side) 1f else alpha.value; translationX = slide.value * size.width },
                    contentAlignment = if (side) Alignment.CenterEnd else Alignment.Center) {
                    // La tarjeta se mide con el ancho que necesite (sin el límite de la ventana) y la ventana toma
                    // ese ancho: así no tapa el juego a los lados (un gesto o un toque ahí cerraba el panel).
                    val base = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(base.density * panelScale, base.fontScale)) {
                    PanelCard(ui, Modifier.wrapContentWidth(unbounded = true).onSizeChanged { fitWidth(it.width) },
                        onPick = { i ->
                            poke()
                            ui.sel = i
                            ui.editing = false
                            ui.items.getOrNull(i)?.run?.invoke()
                        },
                        onSet = ::setLevel
                    )
                    }
                }
            }
        }
        // Desde el principio con el ancho de la tarjeta (con WRAP_CONTENT Android la mide con el ancho de un diálogo,
        // ~580 dp, y se cortaba; con todo el ancho y achicándola después, ZUI animaba el cambio como un deslizamiento).
        // Al centro. No toma el foco (el juego lo conserva); tocar fuera lo cierra.
        val dims = panelDims(svc)
        val density = svc.resources.displayMetrics.density * panelScale
        ui.lockedHeight = false
        val lp = WindowManager.LayoutParams(
            (dims.width.value * density).toInt(),
            if (ThemeState.panelSide) WindowManager.LayoutParams.MATCH_PARENT else dims.height?.let { (it.value * density).toInt() } ?: WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Al centro, o pegado a la derecha (lateral; con la ventana sobre la barra de estado, hasta arriba).
            gravity = if (ThemeState.panelSide) Gravity.END or Gravity.TOP else Gravity.CENTER
            if (ThemeState.panelSide) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            // Aparece y se va desvaneciéndose (sin esto, Android lo desliza).
            windowAnimations = android.R.style.Animation_Toast
        }
        cv.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_OUTSIDE) { hide(); true } else false }
        runCatching { wm.addView(cv, lp) }.onSuccess { view = cv; owner = o; params = lp }.onFailure { o.destroy() }
    }

    private var params: WindowManager.LayoutParams? = null

    /** La ventana del panel toma el ancho de la tarjeta (cambia si aparecen o se van ítems). */
    private fun fitWidth(w: Int) {
        val v = view ?: return
        val lp = params ?: return
        if (w <= 0 || lp.width == w) return
        lp.width = w
        runCatching { wm.updateViewLayout(v, lp) }
    }
}


/** Botones de otras apps que se buscan por nombre o id (ver la grabación en [GamePanel]). */
private const val SHADOW_FLOAT = "com.zui.game.service:id/float_container"
private val SHADOW_SAVE = listOf("Back Rec")
private val REC_TILE = listOf("Screen record", "Grabar pantalla", "Grabación de pantalla")
private const val REC_FLOAT = "com.android.systemui:id/lottie_float_view"
