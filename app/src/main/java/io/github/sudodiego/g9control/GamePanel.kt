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
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Screenshot
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

/** Dónde va un ítem del panel: las barras arriba, después el juego, la pantalla y conexiones, y abajo Android. */
private enum class PanelRow { SLIDER, GAME, SCREEN, SYSTEM }

/**
 * Una acción del panel de juego, como un mosaico de los ajustes rápidos: [sub] es su estado o un detalle y
 * [active] lo pinta como encendido. Las barras ([PanelRow.SLIDER], brillo y volumen) tienen [level] (0..1): A entra
 * a ajustarlas (← → llaman a [adjust] con −1 o +1, Y llama a [alt], A o B salen), y con el dedo [set] fija el nivel;
 * [hint] dice qué hace cada botón mientras se ajusta.
 */
private class PanelItem(
    val label: String, val icon: ImageVector, val section: PanelRow = PanelRow.GAME,
    val sub: String? = null, val active: Boolean = false,
    /** Atenuado: no funciona ahora (falta Shizuku); elegirlo lleva a arreglarlo. */
    val enabled: Boolean = true,
    val adjust: ((Int) -> Unit)? = null, val level: Float? = null, val alt: (() -> Unit)? = null,
    val set: ((Float) -> Unit)? = null, val hint: String? = null, val run: () -> Unit = {}
)

/** Columnas de mosaicos. */
private const val COLUMNS = 4

/**
 * Las filas del panel (índices de [items]): las barras juntas y los mosaicos de cada sección repartidos parejo, de a
 * [COLUMNS] como mucho (6 quedan en 3 + 3, no en 4 + 2). Cada fila llena el ancho: sin huecos.
 */
private fun panelRows(items: List<PanelItem>): List<List<Int>> {
    val by = items.indices.groupBy { items[it].section }
    fun even(list: List<Int>): List<List<Int>> {
        val rows = (list.size + COLUMNS - 1) / COLUMNS
        return list.chunked((list.size + rows - 1) / rows)
    }
    return buildList {
        by[PanelRow.SLIDER]?.let { add(it) }
        by[PanelRow.GAME]?.let { addAll(even(it)) }
        by[PanelRow.SCREEN]?.let { addAll(even(it)) }
        by[PanelRow.SYSTEM]?.let { addAll(even(it)) }
    }
}

/** Estado que dibuja el panel. */
private class PanelUi {
    var items by mutableStateOf(listOf<PanelItem>())
    var sel by mutableIntStateOf(0)
    /** Ajustando el ítem elegido (brillo, volumen). */
    var editing by mutableStateOf(false)
    /** Encabezado: la app al frente (o el submenú), la batería (−1 = no mostrar) y el estado y la hora. */
    var title by mutableStateOf("")
    var battery by mutableIntStateOf(-1)
    var charging by mutableStateOf(false)
    var bypass by mutableStateOf(false)
    /** Cargando: lo que falta para la carga completa ("1 h 20 min"), a la izquierda de la batería. */
    var chargeLeft by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    /** Modo del G9 ahora (Android, X-Input, DS5), como recordatorio; null si no está conectado. */
    var mode by mutableStateOf<String?>(null)
    /** Mini monitor (RAM, W, CPU, temperatura) debajo del encabezado. */
    var stats by mutableStateOf(true)
    /** En un submenú (rendimiento): encabezado sin batería ni hora, y B vuelve. */
    var sub by mutableStateOf(false)
}

/**
 * Panel de juego: menú flotante que se abre con L3 + R3 desde cualquier app. Se maneja con el control
 * sin quitarle el foco al juego, porque el servicio de accesibilidad se queda con los botones mientras
 * está abierto: LB/RB (o la cruceta, si llega como tecla) mueven, A elige y B cierra. En X-Input y DS5
 * la cruceta llega como eje y el servicio no la ve; por eso LB/RB. También se puede tocar.
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
        inPerfMenu = false
        ui.editing = false
        ui.items = items()
        // Empieza en el primer mosaico (Captura), no en las barras.
        ui.sel = ui.items.indexOfFirst { it.section != PanelRow.SLIDER }.coerceAtLeast(0)
        header()
        if (view == null) create()
        svc.setPanelSticks(true)
        poke()
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
        ui.sub = false
        ui.title = appLabel(svc.foregroundApp)
        ui.stats = Prefs.panelStats(svc)
        ui.mode = ModeSwitcher.detect()?.label
        val i = svc.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ui.battery = i?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        // Cargando: rayo en la batería. Bypass: un enchufe al lado y la batería en celeste (no carga, pero está enchufada).
        // Sin Shizuku no se puede leer: lo último que se supo puede ser viejo (se detuvo Shizuku, lo apagó otro).
        ui.bypass = Shell.state == Shell.State.READY && ZuiBattery.bypassNow == true && ZuiBattery.plugged(svc)
        ui.charging = !ui.bypass && i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) == android.os.BatteryManager.BATTERY_STATUS_CHARGING
        // Lo calcula Android (−1 si todavía no sabe, recién enchufada).
        // Hasta el límite de carga si hay (el de ZUI o el propio), si no hasta 100.
        ui.chargeLeft = if (!ui.charging) null else ZuiBattery.chargeTimeLeft(svc)?.let {
            val target = ZuiBattery.chargeTarget(svc)
            ZuiBattery.formatTime(it) + if (target < 100) tr(" a $target %", " to $target %") else ""
        }
        val time = android.text.format.DateFormat.getTimeFormat(svc).format(Date())
        ui.status = listOfNotNull(ui.battery.takeIf { it >= 0 }?.let { "$it %" }, time).joinToString(" · ")
    }

    /** Shizuku no está listo: pedirle el permiso, o abrir su app para iniciarlo. */
    private fun fixShizuku() {
        hide()
        if (Shell.state == Shell.State.NO_PERMISSION) Shell.requestPermission()
        else Shell.launchIntent(svc)?.let { runCatching { svc.startActivity(it) } }
            ?: toast(tr("Shizuku no está instalado.", "Shizuku isn't installed."))
    }

    /** Del submenú (rendimiento, frecuencia) al panel, con su mosaico elegido. */
    private fun backToMain() {
        inPerfMenu = false
        ui.items = items()
        ui.sel = ui.items.indexOfFirst { it.icon == subFrom }.takeIf { it >= 0 }
            ?: ui.items.indexOfFirst { it.section != PanelRow.SLIDER }.coerceAtLeast(0)
        header()
        poke()
    }

    /** El mosaico desde el que se abrió el submenú (para volver a él). */
    private var subFrom: ImageVector = Icons.Outlined.Speed

    /** Submenú de frecuencia de la pantalla para la app al frente: la del sistema o una fija (se recuerda por app). */
    private fun showHzMenu() {
        inPerfMenu = true
        subFrom = Icons.Outlined.DisplaySettings
        val app = svc.foregroundApp
        val chosen = RefreshRate.forApp(svc, app)
        ui.sub = true
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
        val app = svc.foregroundApp ?: return backToMain()
        RefreshRate.setForApp(svc, app, hz)
        backToMain()
        // Cuando la pantalla ya cambió (unos cientos de ms), el mosaico muestra los Hz nuevos.
        RefreshRate.applyFor(svc, app) { main.postDelayed({ refresh() }, 400) }
    }

    /** El panel muestra el submenú de rendimiento (no se reemplaza por la lista principal al confirmar algo). */
    private var inPerfMenu = false

    fun hide() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(stickRepeat)
        stickDir = 0 to 0
        ui.editing = false
        svc.setPanelSticks(false)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner?.destroy()
        owner = null
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
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_LEFT -> { item.adjust.invoke(-1); refresh() }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_RIGHT -> { item.adjust.invoke(1); refresh() }
                KeyEvent.KEYCODE_BUTTON_Y -> if (e.repeatCount == 0) { item.alt?.invoke(); refresh() }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                    if (e.repeatCount == 0) ui.editing = false
                KeyEvent.KEYCODE_BUTTON_MODE -> if (e.repeatCount == 0) hide()
            }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> move(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveGrid(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveGrid(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> moveGrid(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveGrid(0, 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (e.repeatCount == 0) {
                if (item?.adjust != null) ui.editing = true else item?.run?.invoke()
            }
            // B o tocar el botón Legion lo cierran (mantenerlo es lo que lo abre).
            // En un submenú B vuelve al panel.
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (e.repeatCount == 0) { if (inPerfMenu) backToMain() else hide() }
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
            if (dx != 0) { item.adjust.invoke(dx); refresh() }
            return
        }
        moveGrid(dx, dy)
    }

    /**
     * Mover en la grilla, sin dar la vuelta. Entre filas de distinto largo se mantiene el lado (cada fila llena el
     * ancho): de la barra de brillo se baja a los mosaicos de la izquierda.
     */
    private fun moveGrid(dx: Int, dy: Int) {
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
            ui.sel = rows[r][(c + dx).coerceIn(0, rows[r].size - 1)]
        }
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
        val sel = ui.sel
        ui.items = items()
        ui.sel = sel.coerceAtMost(ui.items.size - 1)
    }

    private fun move(d: Int) {
        val n = ui.items.size
        if (n > 0) ui.sel = (ui.sel + d + n) % n
    }

    private fun poke() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 10_000)
    }

    /** Acciones según lo que hay ahora: el panel de ZUI solo en modo juego, los modos según el actual. */
    private fun items(): List<PanelItem> = buildList {
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
            adjust = { SystemLevels.stepVolume(svc, it) }, level = vol / 100f, set = { SystemLevels.setVolume(svc, it) },
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
                    sub = ZuiGame.knownPerf(svc.foregroundApp)?.label ?: tr("Elegir", "Choose")) { showPerfMenu() })
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
            sub = (hzNow?.let { "$it Hz" } ?: "—") + if (hzChosen != null) tr(" · esta app", " · this app") else "") { showHzMenu() })
        if (Aspect.available(svc)) {
            val wide = Aspect.is169(svc)
            add(PanelItem("16:9", Icons.Outlined.AspectRatio, PanelRow.SCREEN,
                sub = if (wide) tr("Encendida", "On") else tr("Apagada", "Off"), active = wide) {
                hide()
                scope.launch { if (!Aspect.toggle(svc)) toast(tr("No se pudo cambiar la pantalla.", "Couldn't change the screen.")) }
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
        add(PanelItem("TBF Companion", Icons.Outlined.OpenInNew, PanelRow.SYSTEM) {
            hide()
            svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }

    /** Quita el panel y espera a que desaparezca de la pantalla antes de seguir (si no, sale en la captura). */
    private fun afterHide(action: () -> Unit) {
        hide()
        main.postDelayed(action, 300)
    }

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
        ui.sub = true
        ui.title = tr("Rendimiento", "Performance")
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
                        while (next == line && tries++ < 10) { delay(250); next = ZuiGame.lastPerfLine() }
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
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEE0E1424.toInt()); cornerRadius = 16 * dp }
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
                Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value }, contentAlignment = Alignment.Center) {
                    // La tarjeta se mide con el ancho que necesite (sin el límite de la ventana) y la ventana toma
                    // ese ancho: así no tapa el juego a los lados (un gesto o un toque ahí cerraba el panel).
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
        // Desde el principio con el ancho de la tarjeta (con WRAP_CONTENT Android la mide con el ancho de un diálogo,
        // ~580 dp, y se cortaba; con todo el ancho y achicándola después, ZUI animaba el cambio como un deslizamiento).
        // Al centro. No toma el foco (el juego lo conserva); tocar fuera lo cierra.
        val cardWidth = ((TILE_W * COLUMNS + GAP * (COLUMNS - 1) + CARD_PAD * 2).value * svc.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            cardWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
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

// Colores del panel, como los ajustes rápidos de Android en oscuro: fondo casi negro, mosaicos grises y los
// encendidos en el color de acento con el contenido oscuro.
// Azul marino con lo encendido en celeste, como la paleta de la app (los fondos de la Y700).
private val PANEL_BG = Color(0xF00E1424)
private val TILE = Color(0xFF1F2A42)
private val TILE_ON = Color(0xFF6EC9FF)
private val ON_TILE_ON = Color(0xFF052238)
private val PANEL_ACCENT = Color(0xFF5CC8FF)
private val PANEL_MUTED = Color(0xFF9AA6BF)
private val SELECTED = Color.White
// Con fondo (degradado o imagen) los mosaicos son de vidrio: blanco translúcido con un borde claro, como en la app.
private val TILE_GLASS = Color(0x2EFFFFFF)
private val GLASS_EDGE = Color(0x24FFFFFF)
private val glassPanel get() = ThemeState.panelBackground != PanelBackground.PLAIN
// Transparente: los mosaicos llevan más cuerpo (azul translúcido) para leerse sobre un juego claro.
private val TILE_CLEAR = Color(0xB8141C30)
private val tileColor get() = when (ThemeState.panelBackground) {
    PanelBackground.PLAIN -> TILE; PanelBackground.CLEAR -> TILE_CLEAR; else -> TILE_GLASS
}

private val TILE_W = 172.dp
private val TILE_H = 68.dp
private val GAP = 10.dp
private val CARD_PAD = 22.dp

/**
 * El panel, como los ajustes rápidos de Android: encabezado (la app al frente, batería y hora), brillo y volumen
 * como barras, y los mosaicos del juego y de Android en una grilla. El elegido lleva borde blanco.
 */
@Composable
private fun PanelCard(ui: PanelUi, modifier: Modifier = Modifier, onPick: (Int) -> Unit, onSet: (Int, Float) -> Unit) {
    val width = TILE_W * COLUMNS + GAP * (COLUMNS - 1)
    val shape = RoundedCornerShape(28.dp)
    Surface(color = if (ThemeState.panelBackground == PanelBackground.CLEAR) Color.Transparent else PANEL_BG, shape = shape, modifier = modifier,
        border = if (glassPanel) BorderStroke(1.dp, GLASS_EDGE) else null) {
      Box {
        // Fondo propio del panel (degradado, imagen o GIF) con un velo para que se lea sobre cualquier imagen.
        if (glassPanel) Box(Modifier.matchParentSize().clip(shape)) {
            PanelBackdrop()
            if (ThemeState.panelBackground != PanelBackground.CLEAR) Box(Modifier.matchParentSize().background(Color(0x660A0F1F)))
        }
        Column(Modifier.padding(horizontal = CARD_PAD, vertical = 20.dp).width(width)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // El nombre ocupa lo que necesita (hasta la mitad); el resto empuja la batería y la hora a la derecha.
                Text(ui.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 340.dp))
                // Modo del control, pequeño, como recordatorio.
                if (!ui.sub) ui.mode?.let { m ->
                    Spacer(Modifier.width(10.dp))
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(tileColor).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SportsEsports, null, Modifier.size(14.dp), tint = PANEL_MUTED)
                        Spacer(Modifier.width(5.dp))
                        Text(m, color = PANEL_MUTED, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                // En un submenú solo el título: lo demás ya se vio en el panel.
                if (!ui.sub) {
                    if (ui.bypass) { Icon(Icons.Outlined.Power, "Bypass", Modifier.size(18.dp), tint = TILE_ON); Spacer(Modifier.width(4.dp)) }
                    ui.chargeLeft?.let { Text(it, color = PANEL_MUTED, fontSize = 14.sp, maxLines = 1); Spacer(Modifier.width(6.dp)) }
                    if (ui.battery >= 0) { BatteryGlyph(ui.battery, ui.charging, ui.bypass); Spacer(Modifier.width(8.dp)) }
                    Text(ui.status, color = PANEL_MUTED, fontSize = 14.sp, maxLines = 1)
                }
            }
            if (!ui.sub && ui.stats) { Spacer(Modifier.height(12.dp)); PanelStats() }
            Spacer(Modifier.height(16.dp))
            val rows = panelRows(ui.items)
            rows.forEachIndexed { r, row ->
                val section = ui.items[row[0]].section
                // Un poco más de aire entre secciones (barras, juego, Android) que entre filas, y su nombre chico (no en
                // los submenús, que tienen una sola sección).
                val newSection = r == 0 || ui.items[rows[r - 1][0]].section != section
                if (r > 0) Spacer(Modifier.height(if (newSection) 14.dp else GAP))
                if (newSection && !ui.sub && section != PanelRow.SLIDER) {
                    Text(when (section) {
                        PanelRow.GAME -> tr("Juego", "Game"); PanelRow.SCREEN -> tr("Pantalla y conexión", "Screen and connection")
                        else -> "Android"
                    }, color = PANEL_MUTED, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                }
                // Cada fila llena el ancho: los mosaicos se reparten el espacio (sin huecos al final).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    row.forEach { i ->
                        val item = ui.items[i]
                        when {
                            section != PanelRow.SLIDER -> PanelTile(item, i == ui.sel, Modifier.weight(1f)) { onPick(i) }
                            item.level != null -> PanelSlider(item, i == ui.sel, ui.editing && i == ui.sel, Modifier.weight(1f)) { onSet(i, it) }
                            else -> PanelToggle(item, i == ui.sel) { onPick(i) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                ui.items.getOrNull(ui.sel)?.takeIf { ui.editing }?.hint
                    ?: if (ui.sub) tr("A elegir · B volver", "A select · B back")
                    else tr("Stick, cruceta o LB / RB mover · A elegir · B cerrar", "Stick, D-pad or LB / RB move · A select · B close"),
                color = PANEL_MUTED, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
      }
    }
}

/**
 * Mini monitor del panel: RAM libre, consumo (ahora y el promedio del último minuto), la CPU y la temperatura de la
 * batería. Se actualiza cada 2 s mientras el panel está abierto.
 */
@Composable
private fun PanelStats() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val ram = remember(tick) { Memory.info(ctx) }
    val w = remember(tick) { ZuiBattery.watts(ctx) }
    val plugged = remember(tick) { ZuiBattery.plugged(ctx) }
    val cpu = remember(tick) { Stats.cpuMhz() }
    val temp = remember(tick) { Stats.batteryTemp(ctx) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        StatChip(Icons.Outlined.Memory, Memory.gb(ram.freeBytes), tr("RAM libre", "Free RAM"), Modifier.weight(1f))
        StatChip(Icons.Outlined.Bolt, w?.let { (if (plugged) "+" else "") + "%.1f W".format(it) } ?: "—",
            PowerLog.recentAvg?.takeIf { !plugged }?.let { tr("prom. %.1f W", "avg %.1f W").format(it) } ?: if (plugged) tr("Entra", "Charging") else tr("Consumo", "Draw"),
            Modifier.weight(1f))
        StatChip(Icons.Outlined.Speed, Stats.cpuText(cpu), "CPU", Modifier.weight(1f))
        StatChip(Icons.Outlined.Thermostat, temp?.let { "%.0f °C".format(it) } ?: "—", tr("Batería", "Battery"), Modifier.weight(1f))
    }
}

@Composable
private fun StatChip(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(tileColor).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = PANEL_ACCENT)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, color = PANEL_MUTED, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Ícono de batería como el de la barra de Android: se llena con el nivel; rayo si carga, celeste en bypass. */
@Composable
private fun BatteryGlyph(level: Int, charging: Boolean, bypass: Boolean) {
    val low = level <= 15 && !charging && !bypass
    androidx.compose.foundation.Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
        val stroke = 1.5.dp.toPx()
        val nub = 2.5.dp.toPx()
        val body = androidx.compose.ui.geometry.Size(size.width - nub, size.height)
        val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(PANEL_MUTED, size = body, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRoundRect(PANEL_MUTED, topLeft = androidx.compose.ui.geometry.Offset(body.width, size.height * 0.3f),
            size = androidx.compose.ui.geometry.Size(nub, size.height * 0.4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
        val pad = stroke + 1.dp.toPx()
        val inner = androidx.compose.ui.geometry.Size((body.width - 2 * pad) * level.coerceIn(0, 100) / 100f, body.height - 2 * pad)
        drawRoundRect(if (low) Color(0xFFFF6B6B) else if (charging || bypass) TILE_ON else Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(pad, pad), size = inner, cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
        if (charging) {
            // Rayo al centro, con borde oscuro para que se vea sobre el relleno.
            val cx = body.width / 2; val h = body.height
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + h * 0.10f, h * 0.12f); lineTo(cx - h * 0.22f, h * 0.56f); lineTo(cx - h * 0.02f, h * 0.56f)
                lineTo(cx - h * 0.10f, h * 0.88f); lineTo(cx + h * 0.22f, h * 0.44f); lineTo(cx + h * 0.02f, h * 0.44f); close()
            }
            drawPath(bolt, PANEL_BG, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawPath(bolt, Color.White)
        }
    }
}

/** Botón pequeño al lado de una barra (brillo automático, silencio): encendido = en color de acento. */
@Composable
private fun PanelToggle(item: PanelItem, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.size(52.dp).clip(shape).background(if (item.active) TILE_ON else tileColor)
            .then(if (selected) Modifier.border(2.5.dp, SELECTED, shape) else Modifier).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(item.icon, item.label, Modifier.size(22.dp), tint = if (item.active) ON_TILE_ON else Color.White)
    }
}

/** Mosaico: ícono, nombre y estado; encendido = en color de acento. Toma el ancho que le da su fila. */
@Composable
private fun PanelTile(item: PanelItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (item.active) ON_TILE_ON else Color.White
    Surface(
        color = if (item.active) TILE_ON else tileColor, shape = RoundedCornerShape(22.dp),
        border = if (selected) BorderStroke(2.5.dp, SELECTED) else if (glassPanel && !item.active) BorderStroke(1.dp, GLASS_EDGE) else null,
        modifier = modifier.height(TILE_H).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)
            .graphicsLayer { alpha = if (item.enabled) 1f else 0.45f }
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null, Modifier.size(24.dp), tint = fg)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                item.sub?.let {
                    Text(it, color = fg.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Barra de brillo o volumen, como la de los ajustes rápidos: se llena con el nivel. Con el dedo se toca o se
 * arrastra; con el control, A la activa (se ve más intensa) y ← → la mueven.
 */
@Composable
private fun PanelSlider(item: PanelItem, selected: Boolean, editing: Boolean, modifier: Modifier, onSet: (Float) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.height(52.dp).clip(shape).background(tileColor)
            .then(if (selected) Modifier.border(2.5.dp, SELECTED, shape) else Modifier)
            .pointerInput(Unit) { detectTapGestures { onSet(it.x / size.width) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onSet(change.position.x / size.width) } }
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((item.level ?: 0f).coerceIn(0.08f, 1f)).clip(shape)
            .background(if (editing) PANEL_ACCENT else TILE_ON.copy(alpha = 0.55f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null, Modifier.size(22.dp), tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(item.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            item.sub?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
        }
    }
}

/**
 * Brillo y volumen desde el panel de juego. El brillo se escribe en los ajustes del sistema: sirve el permiso de
 * pantalla de adb (WRITE_SECURE_SETTINGS) o "Modificar ajustes del sistema". Los pasos del brillo no son lineales
 * (el ojo nota mucho más los cambios abajo): 11 niveles, de 0 a 100 %.
 */
private object SystemLevels {
    private val LEVELS = intArrayOf(2, 5, 10, 18, 29, 44, 64, 90, 125, 180, 255)

    fun canBrightness(ctx: Context) = Aspect.canWrite(ctx) || Settings.System.canWrite(ctx)

    fun autoBrightness(ctx: Context) = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    fun setAutoBrightness(ctx: Context, on: Boolean) = runCatching {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }

    /**
     * Game Assistant apaga el brillo automático al entrar a un juego si este ajuste global es 1. Con el permiso de
     * pantalla de adb se cambia (sin él, no pasa nada: el brillo automático igual cambia, solo que en el próximo juego
     * Game Assistant lo vuelve a apagar).
     */
    fun setAutoInGames(ctx: Context, on: Boolean) = runCatching {
        Settings.Global.putInt(ctx.contentResolver, "key_game_assistant_disable_auto_brightness", if (on) 0 else 1)
    }

    /** Nivel más cercano al brillo actual (0..10). */
    private fun level(ctx: Context): Int {
        val v = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return LEVELS.indices.minBy { kotlin.math.abs(LEVELS[it] - v) }
    }

    fun brightness(ctx: Context) = level(ctx) * 10

    /** Con el dedo en la barra: el nivel más cercano a esa posición (0..1). */
    fun setBrightness(ctx: Context, f: Float) = runCatching {
        setAutoBrightness(ctx, false)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, LEVELS[(f * 10).roundToInt().coerceIn(0, 10)])
    }

    fun setVolume(ctx: Context, f: Float) {
        val a = audio(ctx)
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, (f * max).roundToInt().coerceIn(0, max), 0)
    }

    /** Un paso más o menos; deja el brillo en manual (si no, el automático lo cambia enseguida). */
    fun stepBrightness(ctx: Context, dir: Int) = runCatching {
        setAutoBrightness(ctx, false)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, LEVELS[(level(ctx) + dir).coerceIn(0, 10)])
    }

    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    /** Volumen de la música y los juegos, en %. */
    fun volume(ctx: Context): Int {
        val a = audio(ctx)
        if (a.isStreamMute(AudioManager.STREAM_MUSIC)) return 0
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    fun stepVolume(ctx: Context, dir: Int) =
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)

    fun toggleMute(ctx: Context) =
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
}

/**
 * Mientras G9 Control cambia el modo de rendimiento: la pantalla congelada (una imagen del juego tomada justo
 * antes, solo en memoria: no se guarda) a pantalla completa. Primero se ve igual que el juego y enseguida se
 * oscurece, con una tarjeta que dice qué pasa; así no se ve el panel de Game Assistant. No recibe toques: los
 * gestos simulados la atraviesan y llegan a Game Assistant (las ventanas de accesibilidad quedan por encima de
 * las suyas). Sin imagen, un fondo negro.
 */
private class FreezeCover(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: android.view.View? = null
    private var dim: android.view.View? = null
    private var card: android.view.View? = null
    private var spinner: android.view.View? = null
    private var check: android.view.View? = null
    private var title: android.widget.TextView? = null
    private var subtitle: android.widget.TextView? = null
    private var shot: Bitmap? = null
    private var mode = ""

    fun show(image: Bitmap?, modeLabel: String) {
        mode = modeLabel
        val dp = svc.resources.displayMetrics.density
        val px = { v: Int -> (v * dp).toInt() }
        val bar = android.widget.ProgressBar(svc).apply { isIndeterminate = true }
        // El ✓ ocupa el lugar de la ruedita al terminar: la tarjeta no cambia de tamaño.
        val ok = android.widget.TextView(svc).apply {
            text = "✓"
            setTextColor(0xFF5CC8FF.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            visibility = android.view.View.INVISIBLE
        }
        val icon = android.widget.FrameLayout(svc).apply {
            addView(bar, android.widget.FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER))
            addView(ok, android.widget.FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER))
        }
        val t = android.widget.TextView(svc).apply {
            // El título no cambia en todo el proceso (si cambia al final no se alcanza a leer).
            text = tr("Rendimiento: $modeLabel", "Performance: $modeLabel")
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val s = android.widget.TextView(svc).apply {
            text = tr("Un momento…", "One moment…")
            setTextColor(0xFF9AA6BF.toInt())
            textSize = 13f
        }
        val texts = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
            addView(t)
            addView(s)
        }
        val c = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(14), px(22), px(14))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEE0E1424.toInt()); cornerRadius = 16 * dp }
            addView(icon, android.widget.LinearLayout.LayoutParams(px(28), px(28)))
            addView(texts)
            alpha = 0f
        }
        val d = android.view.View(svc).apply { setBackgroundColor(android.graphics.Color.BLACK); alpha = 0f }
        val match = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        val root = android.widget.FrameLayout(svc).apply {
            if (image != null) addView(android.widget.ImageView(svc).apply {
                setImageBitmap(image)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            })
            else setBackgroundColor(android.graphics.Color.BLACK)
            addView(d, android.widget.FrameLayout.LayoutParams(match, match))
            addView(c, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Toma el foco (sin FLAG_NOT_FOCUSABLE): así los sticks y gatillos del control le llegan a ella y no al
            // juego mientras la imagen está congelada (el servicio solo puede filtrar botones, no movimientos). El
            // juego recupera el foco al quitarla. Sigue sin recibir toques: los gestos llegan a Game Assistant.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, _, _ -> true }
        root.setOnGenericMotionListener { _, _ -> true }
        if (runCatching { wm.addView(root, lp) }.isFailure) { image?.recycle(); return }
        root.requestFocus()
        view = root; dim = d; card = c; spinner = bar; check = ok; title = t; subtitle = s; shot = image
        // Se oscurece y aparece la tarjeta: se nota que es una pausa y no que el juego se colgó.
        d.animate().alpha(0.55f).setDuration(250).start()
        c.animate().alpha(1f).setDuration(250).start()
    }

    /**
     * Resultado: el modo que quedó lo confirma el registro de Game Assistant (no se supone). El título no cambia:
     * la ruedita pasa a ✓ y la línea de abajo dice «Listo». Se ve 1,2 s y la imagen se va con un fundido.
     */
    fun done(ok: Boolean) {
        if (view == null) return
        // Que el cambio de texto no achique la tarjeta.
        (subtitle?.parent as? android.view.View)?.let { it.minimumWidth = it.width }
        spinner?.visibility = android.view.View.INVISIBLE
        if (ok) {
            check?.visibility = android.view.View.VISIBLE
            subtitle?.text = tr("Listo", "Done")
        } else {
            title?.text = tr("No se pudo cambiar el rendimiento", "Couldn't change performance")
            subtitle?.text = tr("Prueba otra vez o usa Game Assistant", "Try again or use Game Assistant")
        }
        main.postDelayed({
            val v = view ?: return@postDelayed
            v.animate().alpha(0f).setDuration(250).withEndAction { hide() }.start()
        }, if (ok) 1200 else 2200)
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        // Sin recycle(): removeView es asíncrono y la ventana todavía se dibuja una vez (se caía la app). Al
        // soltar la referencia, la memoria se libera sola.
        shot = null
    }
}

/**
 * Aviso de captura, como el de la Switch: una tarjeta con la miniatura y «Captura guardada» que entra desde
 * el borde (por defecto arriba a la izquierda; se elige en Ajustes), se queda 2 s y vuelve a salir. No recibe
 * toques ni foco y no sale en la captura (que toma solo la ventana del juego). Con Views simples: es liviano.
 */
private class ShotFlash(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val dp = svc.resources.displayMetrics.density
    private var frame: android.widget.FrameLayout? = null
    private val slideOut = Runnable { leave() }

    /** Miniatura de la captura (56 dp de alto). Se puede llamar desde cualquier hilo. */
    fun thumbnail(bmp: Bitmap): Bitmap {
        val h = (56 * dp).toInt()
        return Bitmap.createScaledBitmap(bmp, (bmp.width * h / bmp.height).coerceAtLeast(1), h, true)
    }

    fun show(thumb: Bitmap) {
        hide()
        val px = { v: Int -> (v * dp).toInt() }
        val image = android.widget.ImageView(svc).apply {
            setImageBitmap(thumb)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, 8 * dp)
            }
            clipToOutline = true
        }
        val text = android.widget.TextView(svc).apply {
            text = tr("Captura guardada", "Screenshot saved")
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(px(14), 0, px(6), 0)
        }
        val card = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(16), px(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE0E1424.toInt()); cornerRadius = 16 * dp
            }
            addView(image, android.widget.LinearLayout.LayoutParams(thumb.width, thumb.height))
            addView(text)
            visibility = android.view.View.INVISIBLE
        }
        // La ventana toca el borde de la pantalla (el margen va adentro): la tarjeta entra desde ese borde y lo
        // que queda fuera se recorta. En una esquina o a un lado entra de costado; arriba o abajo al centro, en
        // vertical; en el centro aparece sin moverse.
        val pos = AutoRules.shotPos(svc)
        val f = android.widget.FrameLayout(svc).apply { setPadding(px(16), px(16), px(16), px(16)); addView(card) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (pos.h) { -1 -> Gravity.START; 1 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL } or
                when (pos.v) { -1 -> Gravity.TOP; 1 -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
        }
        if (runCatching { wm.addView(f, lp) }.isFailure) return
        frame = f
        shown = Shown(pos, AutoRules.shotAnim(svc))
        // Al primer dibujo (ya medida) se pone en su estado oculto y entra.
        card.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                val out = outState(card, f)
                card.visibility = android.view.View.VISIBLE
                if (out != null) {
                    card.translationX = out.x; card.translationY = out.y
                    card.alpha = out.alpha; card.scaleX = out.scale; card.scaleY = out.scale
                    card.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                return true
            }
        })
        main.postDelayed(slideOut, 2200)
    }

    /** Posición y animación con que se mostró (se leen al mostrar: si cambian en Ajustes, sale igual que entró). */
    private class Shown(val pos: NoticePos, val anim: NoticeAnim)
    private var shown: Shown? = null

    /** Estado de la tarjeta antes de entrar y al salir. */
    private class Out(val x: Float = 0f, val y: Float = 0f, val alpha: Float = 1f, val scale: Float = 1f)

    /** Miniatura de muestra para la vista previa: un degradado del tamaño de una captura. */
    fun sample(): Bitmap {
        val h = (56 * dp).toInt()
        val w = h * 16 / 10
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).drawPaint(android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                    0xFF1F2A42.toInt(), 0xFF5CC8FF.toInt(), android.graphics.Shader.TileMode.CLAMP)
            })
        }
    }

    /**
     * Deslizar: fuera del borde más cercano (en el centro, sin borde, aparece como Suave). Suave: transparente
     * y un poco más chica, en su lugar. Sin animación: null (aparece y desaparece de golpe).
     */
    private fun outState(card: android.view.View, f: android.view.View): Out? {
        val s = shown ?: return null
        val soft = Out(alpha = 0f, scale = 0.92f)
        return when (s.anim) {
            NoticeAnim.NONE -> null
            NoticeAnim.SMOOTH -> soft
            NoticeAnim.SLIDE -> when {
                s.pos.h == -1 -> Out(x = -(card.width + f.paddingLeft).toFloat())
                s.pos.h == 1 -> Out(x = (card.width + f.paddingRight).toFloat())
                s.pos.v == -1 -> Out(y = -(card.height + f.paddingTop).toFloat())
                s.pos.v == 1 -> Out(y = (card.height + f.paddingBottom).toFloat())
                else -> soft
            }
        }
    }

    private fun leave() {
        val f = frame ?: return
        val card = f.getChildAt(0)
        val out = outState(card, f) ?: return hide()
        card.animate().translationX(out.x).translationY(out.y).alpha(out.alpha).scaleX(out.scale).scaleY(out.scale)
            .setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (frame === f) hide() }.start()
    }

    fun hide() {
        main.removeCallbacks(slideOut)
        frame?.let { it.getChildAt(0).animate().cancel(); runCatching { wm.removeView(it) } }
        frame = null
        shown = null
    }
}

/**
 * Sonido de la captura: un obturador (dos chasquidos) o un jingle corto de dos notas, archivos en `res/raw`. Van por
 * el volumen multimedia, el mismo del juego (el obturador del sistema, `MediaActionSound`, usa el volumen del sistema
 * y en esta tablet no se oye). Cada captura usa un reproductor nuevo: uno guardado (antes un `AudioTrack` fijo) queda
 * mudo sin avisar si Android reinicia el audio o cambia la salida (audífonos, Bluetooth, monitor).
 */
private class ShotSounds(private val ctx: android.content.Context) {
    private var player: android.media.MediaPlayer? = null
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_GAME)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /**
     * Foco de audio pasajero que deja bajar a los demás: Android baja el volumen del juego mientras suena (si no,
     * los dos sonidos se mezclaban y no se distinguía) y lo devuelve al soltarlo, como con una notificación.
     */
    private val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setWillPauseWhenDucked(false).build()
    private val unfocus = Runnable { audio.abandonAudioFocusRequest(focus) }

    fun play(s: ShotSound) {
        if (s == ShotSound.NONE) return
        val res = if (s == ShotSound.SHUTTER) R.raw.shot_shutter else R.raw.shot_jingle
        runCatching {
            player?.release()
            val p = android.media.MediaPlayer.create(ctx, res, attrs, audio.generateAudioSessionId()) ?: error("MediaPlayer.create devolvió null")
            player = p
            main.removeCallbacks(unfocus)
            audio.requestAudioFocus(focus)
            // Al terminar se suelta (un poco después, para que el juego no vuelva de golpe).
            p.setOnCompletionListener { it.release(); if (player === it) player = null; main.postDelayed(unfocus, 250) }
            p.start()
        }.onFailure { Log.w(AutoModeService.TAG, "no se pudo reproducir el sonido de captura", it) }
    }

    fun release() {
        main.removeCallbacks(unfocus)
        audio.abandonAudioFocusRequest(focus)
        player?.release()
        player = null
    }
}
