package io.github.sudodiego.g9control

import android.content.Intent
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SWITCH_TO = "switch_to"
        const val LIGHTS_SAVE_MS = 2000L
        /** Pausa sin cambios antes de empezar la cuenta (para tocar + / − varias veces seguidas). */
        const val LIGHTS_QUIET_MS = 600L
    }

    val state = AppState()
    private var connecting = false
    /** Último G9 al que ya se le pidió permiso, para no repetir el diálogo si se rechaza. */
    private var askedDeviceId = -1
    /** true entre onResume y onPause (el ciclo de vida recién marca RESUMED después de onResume). */
    private var resumed = false
    /** Mientras se aplica un perfil, el guardado automático no escribe (lo hace applyProfile, una vez). */
    private var applyingProfile = false

    private val inputListener = object : InputManager.InputDeviceListener {
        // Al enchufar el control, Android muestra su propio aviso ("abrir con G9 Control"):
        // aquí solo se conecta si ya hay permiso, para no sumar un segundo aviso.
        override fun onInputDeviceAdded(id: Int) { refreshDevice(); autoConnect(ask = false) }
        override fun onInputDeviceRemoved(id: Int) = refreshDevice()
        override fun onInputDeviceChanged(id: Int) { refreshDevice(); autoConnect(ask = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state.autoEnabled = AutoRules.enabled(this)
        state.autoRules = AutoRules.rules(this)
        state.autoRemindReturn = AutoRules.remindReturn(this)
        state.openOnConnect = Prefs.openOnConnect(this)
        state.centered = Prefs.centered(this)
        state.profiles = Profiles.load(this)
        SkinState.skin = Prefs.skin(this)
        ThemeState.load(this)
        // Ventana siempre translúcida, con un fondo que reemplaza los píxeles (VeilDrawable): así el fondo de pantalla
        // se pone y se quita sin recrear la actividad (recrearla mandaba al Inicio y dejaba la barra de estado sin fondo).
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        Lang.load(this)
        state.showSetup = !Prefs.setupDone(this)
        state.status = tr("Conecta el G9 por USB en modo Android.", "Connect the G9 over USB in Android mode.")
        setContent {
            G9Theme {
                // Íconos de la barra de estado y de navegación acordes al tema elegido (no solo al del sistema).
                val dark = isDarkTheme()
                // Fondo de pantalla (el animado de Wallpaper Engine también): Android lo dibuja detrás de la ventana.
                val wallpaper = ThemeState.background == AppBackground.WALLPAPER
                // El velo del fondo de pantalla lo pinta la ventana (cubre también la barra de estado; con la
                // navegación por gestos el contenido empieza debajo de ella y ahí quedaba el fondo sin oscurecer).
                val dim = ThemeState.backgroundDim
                androidx.compose.runtime.LaunchedEffect(wallpaper, dim, dark) {
                    showWallpaper(wallpaper, dark, dim)
                }
                androidx.compose.runtime.LaunchedEffect(dark) {
                    val style = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                App(state, this)
            }
        }
        handleIntent(intent)
        startTriggerPreview()
        startAutoSave()
    }

    /**
     * Guardado automático: cada grupo se escribe en el control cuando dejas de cambiarlo por un momento
     * (así mover un slider no genera una escritura por cada paso). Solo se guarda si ya se leyó la
     * configuración del control (para no pisarla con los valores por defecto de la app) y solo lo que cambió.
     */
    @OptIn(FlowPreview::class)
    private fun startAutoSave() {
        fun <T> watch(
            quietMs: Long, read: () -> T, ready: () -> Boolean, onChange: () -> Unit = {}, save: () -> Unit
        ) = lifecycleScope.launch {
            snapshotFlow(read).drop(1).onEach { onChange() }.debounce(quietMs).collect {
                if (ready() && !applyingProfile && G9Usb.isOpen()) save()
            }
        }
        val s = state
        // Luces: 2 s de espera para juntar varios ajustes en una sola escritura (cuida la memoria del control).
        // Con el dedo sobre un slider no se guarda: la espera empieza al soltarlo.
        watch(
            LIGHTS_QUIET_MS + LIGHTS_SAVE_MS, { listOf(s.left, s.right, s.homeToSend(), s.sliderHeld) },
            { s.appliedLeft != null && s.lightsDirty && !s.sliderHeld },
            onChange = { s.lightsChangedAt = android.os.SystemClock.uptimeMillis() }
        ) { applyLights() }
        watch(700, { listOf(s.deadZones.toMap(), s.quickL, s.quickR, s.dpadLock, s.swapDpad, s.sliderHeld) },
            { s.appliedDeadZones != null && s.sticksDirty && !s.sliderHeld }) { saveSticks() }
        watch(500, { s.vibration }, { s.appliedVibration != null && s.vibrationDirty }) { saveVibration() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // Abierta por el aviso de Android al conectar el G9: el permiso ya viene concedido.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) autoConnect(ask = false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputListener, null)
        refreshDevice()
        autoConnect(ask = true)
        KeyBus.listener = { e -> if (state.testMode) addOtherKey(e, viaService = true) }
        AmbientMusic.start(this)
    }

    override fun onPause() {
        AmbientMusic.stop(this)
        resumed = false
        KeyBus.listener = null
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener)
        super.onPause()
    }

    /**
     * Al detectar el G9 en modo Android se conecta y lee la configuración sola. Si falta el permiso USB
     * y [ask], muestra el aviso de acceso una vez por cada conexión del control.
     */
    private fun autoConnect(ask: Boolean) {
        if (G9Usb.isOpen() || connecting) return
        if (!resumed) return
        val d = G9Usb.findDevice(this) ?: return
        if (G9Usb.hasPermission(this, d) || (ask && d.deviceId != askedDeviceId)) {
            askedDeviceId = d.deviceId
            readAll()
        }
    }

    /**
     * Igual que la app oficial: mientras la pantalla está activa y se aprieta LT/RT, manda cada 200 ms
     * una vibración inmediata con fuerza = presión del gatillo × 255. El control la escala con el nivel
     * guardado, así se siente cómo quedó cada lado. No escribe nada en la memoria del control.
     */
    private fun startTriggerPreview() = lifecycleScope.launch {
        var active = false
        while (true) {
            delay(200)
            val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val l = (state.lt * 255).roundToInt()
            val r = (state.rt * 255).roundToInt()
            val want = state.triggerPreview && state.page == Page.VIBRATION && resumed &&
                G9Usb.isOpen() && (l > 8 || r > 8)
            if (want) {
                withContext(Dispatchers.IO) { G9Usb.transact(Gcm.rumble(l, r), expectReply = false) }
                active = true
            } else if (active) {
                if (G9Usb.isOpen()) withContext(Dispatchers.IO) { G9Usb.transact(Gcm.rumble(0, 0), expectReply = false) }
                active = false
            }
        }
    }

    /** Lee sticks y gatillos del control (modo Android) para la prueba en vivo. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val isJoystick = (ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && ev.action == MotionEvent.ACTION_MOVE) {
            state.lx = ev.getAxisValue(MotionEvent.AXIS_X)
            state.ly = ev.getAxisValue(MotionEvent.AXIS_Y)
            state.rx = ev.getAxisValue(MotionEvent.AXIS_Z)
            state.ry = ev.getAxisValue(MotionEvent.AXIS_RZ)
            state.lt = max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            state.rt = max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            state.hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            state.hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            state.inputDevice = ev.device?.name ?: "?"
            markTested()
            // Fuera de la prueba, Android convierte stick y cruceta en flechas para mover el foco.
            // En Sticks solo la cruceta (si no, la prueba en vivo movería el foco).
            val consume = state.capturing || (state.page == Page.STICKS && state.hatX == 0f && state.hatY == 0f)
            return if (consume) true else super.dispatchGenericMotionEvent(ev)
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Botones del control: alimentan la prueba de botones y la barra de "mantén Vista + Menú" del aviso
     * de modo. En la página de prueba se consumen todos (para que B no cierre nada).
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val src = e.source
        val fromPad = KeyEvent.isGamepadButton(e.keyCode) ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!fromPad) return super.dispatchKeyEvent(e)
        val s = state
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                // Sonidos de navegación (no probando botones ni grabando una macro).
                if (!s.capturing) when (e.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> UiSounds.play(this, UiSounds.Kind.SELECT)
                    KeyEvent.KEYCODE_BUTTON_B -> UiSounds.play(this, UiSounds.Kind.BACK)
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 ->
                        UiSounds.play(this, UiSounds.Kind.TAB)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                        UiSounds.play(this, UiSounds.Kind.MOVE)
                }
                s.pressedKeys[e.keyCode] = Unit
                if (padKeyOf(e.keyCode) == null && s.testMode) addOtherKey(e, viaService = false)
            }
            KeyEvent.ACTION_UP -> s.pressedKeys.remove(e.keyCode)
        }
        // Vista + Menú: la misma barra que la guía (HoldTracker).
        val vm = e.keyCode == KeyEvent.KEYCODE_BUTTON_START || e.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        if (vm && e.repeatCount == 0 && (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP)) {
            s.hold.onKey(e.keyCode, e.action == KeyEvent.ACTION_DOWN)
        }
        markTested()
        // Mantener B: 1 s sale de la prueba (o de la grabación), sin soltarlo; 2 s sale de la app, al soltarlo. En el editor
        // de macros B cierra la macro (lo dice la barra de abajo): mantenerlo no sale de la app.
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_B && !(s.editingMacro != null && !s.capturing)) when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                bHoldFired = false
                bHandler.removeCallbacks(bHold)
                val ms = if (s.capturing) 1000L else 2000L
                s.bHold = android.os.SystemClock.uptimeMillis() to ms
                bHandler.postDelayed(bHold, ms)
            }
            KeyEvent.ACTION_UP -> {
                bHandler.removeCallbacks(bHold)
                s.bHold = null
                s.bReady = false
                if (exitOnRelease) { exitOnRelease = false; bHoldFired = false; moveTaskToBack(true); return true }
                if (bHoldFired) { bHoldFired = false; return true }   // ese B ya hizo su acción
            }
        }
        // Probando botones o grabando una macro: todos los botones son para eso.
        if (s.capturing) return true
        return padNavigation(e) || super.dispatchKeyEvent(e)
    }

    private val bHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bHoldFired = false
    /** Se mantuvo B 2 s: la app se va al soltarlo. */
    private var exitOnRelease = false
    private val bHold = Runnable {
        bHoldFired = true
        val s = state
        s.bHold = null
        when {
            s.macroRecording -> stopMacroRecording()
            s.testMode -> s.testMode = false
            // Salir de la app: al SOLTAR B (ver ACTION_UP). Si se sale con B apretado, las repeticiones que Android genera
            // mientras se mantiene (después del filtro del servicio, no se pueden bloquear) le llegan al juego que queda al frente.
            else -> { exitOnRelease = true; s.bReady = true }
        }
    }

    /**
     * Navegar la app con el control: A elige (como el centro de la cruceta), B vuelve, LB/RB cambian
     * de página. Cruceta y stick mueven el foco (Android los convierte en flechas).
     */
    private fun padNavigation(e: KeyEvent): Boolean {
        val s = state
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                val center = KeyEvent(e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, InputDevice.SOURCE_DPAD)
                return super.dispatchKeyEvent(center)
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                // B solo cierra lo que esté abierto (el editor de macro). Los diálogos lo manejan solos.
                if (e.action == KeyEvent.ACTION_UP && s.editingMacro != null) closeMacroEditor()
                return true
            }
            // Vista y Menú no hacen nada en la app: solo sirven para cambiar de modo (si no, Menú
            // termina actuando como un toque en lo que tenga el foco).
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> return true
            // L3/R3 no hacen nada en la app (Android por defecto los usa para ir a Inicio).
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> return true
            // LT/RT: sección anterior o siguiente (Tablet, G9, Ajustes). En Vibración y Sticks no: ahí se usan para
            // probar los gatillos.
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && s.page != Page.VIBRATION && s.page != Page.STICKS) {
                    val all = Section.entries
                    val i = all.indexOf(s.section) + if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L2) -1 else 1
                    all.getOrNull(i)?.let { s.goTo(it) }
                }
                return true
            }
            // LB/RB: las pestañas de la sección, de punta a punta (sin pasar a la otra).
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    val step = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1
                    val pages = s.section.pages
                    s.page = pages[(pages.indexOf(s.page) + step + pages.size) % pages.size]
                }
                return true
            }
        }
        return false
    }

    /** Solo cuenta lo que se aprieta con la página de prueba abierta. */
    private fun markTested() {
        recordMacroInput()
        if (!state.testMode) return
        pressedNow(state).forEach { state.testedKeys[it] = Unit }
    }

    /** Agrega a "Otros botones" una tecla sin lugar en el dibujo, con el dispositivo que la mandó. */
    private fun addOtherKey(e: KeyEvent, viaService: Boolean) {
        val s = state
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val from = e.device?.name ?: tr("dispositivo desconocido", "unknown device")
        val line = "$name · ${tr("código", "code")} ${e.keyCode} · $from" + if (viaService) tr(" (tecla del sistema)", " (system key)") else ""
        s.otherKeys.remove(line)
        s.otherKeys.add(0, line)
        while (s.otherKeys.size > 8) s.otherKeys.removeAt(s.otherKeys.lastIndex)
    }

    /**
     * Pedidos que llegan a la app: cambio de modo desde el ícono de ajustes rápidos o el widget (cuando
     * faltaba el permiso USB), perfil desde el widget y bytes de prueba por adb.
     */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        // Abierta al conectar el G9: directo a su sección.
        if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) state.goTo(Section.G9)
        // Solo en compilaciones de depuración: enviar bytes al control desde el PC con
        // adb shell am start -n io.github.sudodiego.g9control/.MainActivity --es g9hex "08 02 05 00"
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        i.getStringExtra("g9hex")?.let { hex ->
            i.removeExtra("g9hex")
            if (debuggable) console(hex, addChecksum = true)
            return
        }
        // Igual, pero sin agregar checksum (algunos comandos, como 01 01 o 8F, van sin él).
        i.getStringExtra("g9raw")?.let { hex ->
            i.removeExtra("g9raw")
            if (debuggable) console(hex, addChecksum = false)
            return
        }
        val t = i.getStringExtra(EXTRA_SWITCH_TO)?.let { runCatching { PadMode.valueOf(it) }.getOrNull() } ?: return
        i.removeExtra(EXTRA_SWITCH_TO)
        val now = ModeSwitcher.detect()
        val usb = t.target
        if (now == PadMode.ANDROID && usb != null) lifecycleScope.launch {
            // Desde Android se cambia por USB (aquí se pide el permiso si faltaba).
            if (connect()) {
                state.status = ModeSwitcher.switchTo(this@MainActivity, usb)
                state.connected = false
            }
        } else if (now != null && now != t) state.modeTarget = t   // con Vista + Menú: la franja guía
    }

    fun refreshDevice() {
        val mode = ModeSwitcher.detect()
        if (mode != state.padMode) {
            // El control se reconectó en otro modo: no avisa qué botones quedaron apretados.
            state.pressedKeys.clear()
            state.hold.modeChanged()
            if (!G9Usb.isOpen()) state.status = when (mode) {
                null -> tr("No se detecta el G9.", "G9 not detected.")
                PadMode.ANDROID -> tr("Control en modo Android.", "Controller in Android mode.")
                else -> tr("Control en modo ${mode.label}.", "Controller in ${mode.label} mode.")
            }
        }
        state.padMode = mode
        state.autoServiceOn = AutoRules.serviceEnabled(this)
        val d = G9Usb.findDevice(this)
        state.deviceText = d?.let { G9Usb.describe(it) }
            ?: G9Usb.findOtherMode(this)?.let { tr("En modo X-Input/DS5 (la app no se conecta así):\n", "In X-Input/DS5 mode (the app can't connect like this):\n") + G9Usb.describe(it) }
            ?: tr("No se detecta el G9.", "G9 not detected.")
        state.allDevicesText = G9Usb.allDevices(this).joinToString("\n") { G9Usb.describe(it) }
            .ifEmpty { tr("Ningún dispositivo USB.", "No USB devices.") }
        if (d == null && G9Usb.isOpen()) G9Usb.close()
        state.connected = d != null && G9Usb.isOpen()
        state.refreshLog()
    }

    suspend fun connect(): Boolean {
        connecting = true
        try {
            return connectInner()
        } finally {
            connecting = false
        }
    }

    private suspend fun connectInner(): Boolean {
        val d = G9Usb.findDevice(this) ?: run {
            state.fail(if (G9Usb.findOtherMode(this) != null)
                tr("El G9 está en modo X-Input o DS5. La app solo lo configura en modo Android (Vista + Menú).",
                    "The G9 is in X-Input or DS5 mode. The app can only configure it in Android mode (View + Menu).")
            else tr("No encuentro el G9. Conéctalo por USB en modo Android.", "G9 not found. Connect it over USB in Android mode."))
            return false
        }
        if (!G9Usb.requestPermission(this, d)) {
            state.fail(tr("Permiso USB denegado.", "USB permission denied."))
            return false
        }
        val err = withContext(Dispatchers.IO) { G9Usb.open(this@MainActivity) }
        if (err != null) state.fail(err) else state.status = tr("Conectado.", "Connected.")
        state.connected = err == null
        refreshDevice()
        return err == null
    }

    fun launchIo(block: suspend () -> Unit) {
        lifecycleScope.launch {
            if (!G9Usb.isOpen() && !connect()) return@launch
            try {
                block()
            } catch (e: Exception) {
                state.fail("Error: ${e.message}")
            }
            refreshDevice()
        }
    }

    /**
     * Mide el desvío de un stick suelto: pone su zona muerta interna en 0, registra durante 3 s la
     * inclinación máxima que llega sin tocarlo y vuelve a escribir el valor que tenía. Sugiere una
     * zona muerta un poco mayor que ese desvío. Son dos escrituras, solo cuando se pide.
     */
    fun measureDrift(left: Boolean) = launchIo {
        val s = state
        val id = if (left) Gcm.DZ_L_INNER else Gcm.DZ_R_INNER
        val orig = s.appliedDeadZones?.get(id) ?: return@launchIo
        s.drift = Drift(left, measuring = true)
        kotlinx.coroutines.delay(800)   // tiempo para soltar el stick
        var max = 0f
        withContext(Dispatchers.IO) { if (orig != 0) G9Usb.transact(Gcm.setDeadZone(id, 0)) }
        try {
            val end = android.os.SystemClock.uptimeMillis() + 3000
            while (android.os.SystemClock.uptimeMillis() < end) {
                val v = if (left) kotlin.math.hypot(s.lx, s.ly) else kotlin.math.hypot(s.rx, s.ry)
                if (v > max) max = v
                s.drift = Drift(left, measuring = true, progress = 1f - (end - android.os.SystemClock.uptimeMillis()) / 3000f)
                kotlinx.coroutines.delay(20)
            }
        } finally {
            // Se devuelve el valor original aunque la medición se interrumpa (por ejemplo, al cerrar la app).
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { if (orig != 0) G9Usb.transact(Gcm.setDeadZone(id, orig)) }
        }
        val pct = kotlin.math.ceil(max * 100).toInt().coerceIn(0, 100)
        // Margen de 3 puntos sobre lo medido; nunca menos de 2 %.
        s.drift = Drift(left, measuring = false, maxPct = pct, suggest = (pct + 3).coerceIn(2, 40))
    }

    fun readAll() = launchIo {
        state.busy = true
        try { readAllNow() } finally { state.busy = false }
    }

    private suspend fun readAllNow() {
        withContext(Dispatchers.IO) {
            val mode = ModeSwitcher.readMode()
            val dz = (1..8).associateWith { id ->
                G9Usb.transact(Gcm.readDeadZone(id))?.getOrNull(4)?.u()
            }
            val vib = G9Usb.transact(Gcm.readVibration())?.let { Vibration.parse(it) }
            val trig = G9Usb.transact(Gcm.readTriggerQuick())
            val back = BackKey.entries.associate { k ->
                k.id to G9Usb.transact(Gcm.readBackKey(k.id))?.takeIf { it.size >= 5 }?.get(3)?.u()
            }
            val macros = back.filterValues { it == BACK_MACRO }.keys.associateWith { id ->
                val steps = MacroGcm.parseSteps(G9Usb.transact(MacroGcm.read(id))) ?: emptyList()
                MacroGcm.parseSettings(G9Usb.transact(MacroGcm.readSettings(id)), steps) ?: Macro(steps)
            }
            val layout = G9Usb.transact(Gcm.READ_LAYOUT)?.getOrNull(1)?.u()
            val basic = G9Usb.transact(Gcm.READ_BASIC_INFO)
            val uuid = G9Usb.transact(Gcm.READ_UUID)
            val lock = G9Usb.transact(Gcm.readFunc(Gcm.FUNC_DPAD_DIAGONAL_LOCK))?.getOrNull(4)?.toInt()
            val swap = G9Usb.transact(Gcm.readFunc(Gcm.FUNC_LSTICK_SWAP_DPAD))?.getOrNull(4)?.toInt()
            val left = G9Usb.transact(Gcm.readStrip(1))?.let { Light.parseStrip(it) }
            val right = G9Usb.transact(Gcm.readStrip(2))?.let { Light.parseStrip(it) }
            val home = G9Usb.transact(Gcm.readHome())?.let { Light.parseHome(it) }
            withContext(Dispatchers.Main) {
                val s = state
                s.mode = mode
                if (dz.values.all { it != null }) {
                    dz.forEach { (id, v) -> s.deadZones[id] = v!! }
                    s.appliedDeadZones = s.deadZones.toMap()
                }
                vib?.let { s.vibration = it; s.appliedVibration = it }
                if (trig != null && trig.size >= 4) {
                    s.quickL = trig[2].toInt() == 1
                    s.quickR = trig[3].toInt() == 1
                    s.appliedQuick = s.quickL to s.quickR
                }
                if (back.values.all { it != null }) {
                    back.forEach { (id, v) -> s.backKeys[id] = v!! }
                    s.appliedBackKeys = s.backKeys.toMap()
                    s.macros.clear(); s.macros.putAll(macros)
                }
                if (layout != null) s.abSwapped = layout == 0x04
                s.devInfo = buildList {
                    if (basic != null && basic.size >= 19) {
                        fun v(hi: Int, lo: Int) = "${basic[hi].u()}.${basic[lo].u()}"
                        add("Firmware" to v(12, 11))
                        add("Hardware" to v(8, 7))
                        add(tr("Protocolo GCM", "GCM protocol") to v(18, 17))
                    }
                    if (uuid != null && uuid.size >= 17) add(tr("Identificador", "Identifier") to Gcm.hex(uuid.copyOfRange(1, 17)).replace(" ", ""))
                }
                if (lock != null && swap != null) {
                    s.dpadLock = lock == 1
                    s.swapDpad = swap == 1
                    s.appliedFuncs = s.funcs
                }
                left?.let { s.left = it.copy(id = 1); s.appliedLeft = s.left }
                right?.let { s.right = it.copy(id = 2); s.appliedRight = s.right }
                home?.let {
                    // Home "apagado" = luminosidad 0 (los efectos respiración/apagado no funcionan en el G9).
                    s.homeOff = it.lum == 0
                    s.home = if (it.lum == 0) it.copy(effect = 1, lum = 50) else it.copy(effect = 1)
                    s.appliedHome = s.homeToSend()
                }
                if (left != null && right != null && s.left.copy(id = 0).relevant() != s.right.copy(id = 0).relevant()) {
                    s.lightTarget = LightTarget.LEFT
                }
                if (mode != null) s.status = tr("Configuración leída.", "Settings read.")
                else s.fail(tr("El control no respondió.", "The controller didn't respond."))
            }
        }
    }

    /** Manda solo las luces que cambiaron desde la última lectura o aplicación. */
    fun applyLights() = launchIo { applyLightsNow() }

    private suspend fun applyLightsNow() {
        val s = state
        // Se guarda lo que había al empezar: si algo cambia mientras se escribe, queda pendiente.
        val left = s.left
        val right = s.right
        val home = s.homeToSend()
        val ops = buildList {
            if (left != s.appliedLeft) add(Gcm.setStrip(left))
            if (right != s.appliedRight) add(Gcm.setStrip(right))
            if (home != s.appliedHome) add(Gcm.setHome(home))
        }
        if (ops.isEmpty()) { s.status = tr("No hay cambios en las luces.", "No light changes."); return }
        withContext(Dispatchers.IO) { ops.forEach { G9Usb.transact(it) } }
        s.appliedLeft = left; s.appliedRight = right; s.appliedHome = home
        s.status = tr("Luces aplicadas.", "Lights applied.")
    }

    fun saveVibration() = launchIo { saveVibrationNow() }

    /** Devuelve true si el control tomó los valores (verificado releyendo). */
    private suspend fun saveVibrationNow(): Boolean {
        val v = state.vibration
        val back = withContext(Dispatchers.IO) {
            G9Usb.transact(Gcm.setVibration(v))
            G9Usb.transact(Gcm.readVibration())?.let { Vibration.parse(it) }
        }
        state.appliedVibration = back ?: v
        val ok = back != null && back.leftGrip == v.leftGrip && back.rightGrip == v.rightGrip
        when {
            back == null -> state.fail(tr("Vibración enviada, pero no pude releerla para verificar.", "Vibration sent, but it couldn't be read back to verify."))
            ok -> state.status = tr("Vibración guardada.", "Vibration saved.")
            else -> state.fail(tr("El control devolvió izq ${back.leftGrip}, der ${back.rightGrip}: no tomó los valores.",
                "The controller returned left ${back.leftGrip}, right ${back.rightGrip}: it didn't take the values."))
        }
        return ok
    }

    /** Guarda zonas muertas y gatillo rápido; solo escribe lo que cambió y lo verifica releyendo. */
    fun saveSticks() = launchIo { saveSticksNow() }

    /** Devuelve true si todo quedó verificado (o no había cambios). */
    private suspend fun saveSticksNow(): Boolean {
        val s = state
        val wanted = s.deadZones.toMap()
        val changed = wanted.filter { (id, v) -> s.appliedDeadZones?.get(id) != v }
        val quick = s.quickL to s.quickR
        val quickChanged = quick != s.appliedQuick
        val funcs = s.funcs
        val oldFuncs = s.appliedFuncs
        val lockChanged = oldFuncs != null && funcs.first != oldFuncs.first
        val swapChanged = oldFuncs != null && funcs.second != oldFuncs.second
        if (changed.isEmpty() && !quickChanged && !lockChanged && !swapChanged) { s.status = tr("No hay cambios.", "No changes."); return true }
        val funcsOk = withContext(Dispatchers.IO) {
            fun write(sub: Int, on: Boolean) =
                G9Usb.transact(Gcm.setFunc(sub, on)).let { G9Usb.transact(Gcm.readFunc(sub))?.getOrNull(4)?.toInt() == (if (on) 1 else 0) }
            (!lockChanged || write(Gcm.FUNC_DPAD_DIAGONAL_LOCK, funcs.first)) &&
                (!swapChanged || write(Gcm.FUNC_LSTICK_SWAP_DPAD, funcs.second))
        }
        if (oldFuncs != null) s.appliedFuncs = funcs
        val (wrong, quickBack) = withContext(Dispatchers.IO) {
            changed.forEach { (id, v) -> G9Usb.transact(Gcm.setDeadZone(id, v)) }
            val wrong = changed.filter { (id, v) -> G9Usb.transact(Gcm.readDeadZone(id))?.getOrNull(4)?.u() != v }.keys
            val qb = if (quickChanged) {
                G9Usb.transact(Gcm.setTriggerQuick(quick.first, quick.second))
                G9Usb.transact(Gcm.readTriggerQuick())
            } else null
            wrong to qb
        }
        s.appliedDeadZones = wanted
        if (quickChanged) s.appliedQuick = quick
        val quickOk = !quickChanged || (quickBack != null && quickBack.size >= 4 &&
            (quickBack[2].toInt() == 1) == quick.first && (quickBack[3].toInt() == 1) == quick.second)
        when {
            wrong.isNotEmpty() -> s.fail(tr("Guardado, pero no se pudieron verificar las zonas $wrong.", "Saved, but dead zones $wrong couldn't be verified."))
            !quickOk -> s.fail(tr("Guardado; el gatillo rápido no se pudo verificar.", "Saved; hair trigger couldn't be verified."))
            !funcsOk -> s.fail(tr("Guardado; los ajustes de la cruceta no se pudieron verificar.", "Saved; D-pad options couldn't be verified."))
            else -> s.status = tr("Guardado y verificado.", "Saved and verified.")
        }
        return wrong.isEmpty() && quickOk && funcsOk
    }

    /**
     * Carga el perfil en la app y lo escribe en el control: solo lo que cambia, una vez cada cosa
     * (sin escrituras repetidas). El guardado automático ve que ya no hay cambios pendientes.
     */
    fun applyProfile(p: Profile) = launchIo {
        val s = state
        if (!s.configKnown) { s.fail(tr("Primero hay que leer la configuración del control.", "The controller settings must be read first.")); return@launchIo }
        s.left = p.left.copy(id = 1)
        s.right = p.right.copy(id = 2)
        s.home = p.home
        s.homeOff = p.homeOff
        p.deadZones.forEach { (k, v) -> s.deadZones[k] = v }
        s.quickL = p.quickL
        s.quickR = p.quickR
        s.dpadLock = p.dpadLock
        s.swapDpad = p.swapDpad
        s.vibration = p.vibration
        s.lightTarget = if (s.left.copy(id = 0) == s.right.copy(id = 0)) LightTarget.BOTH else LightTarget.LEFT
        applyingProfile = true
        try {
            applyProfileNow(p)
        } finally {
            applyingProfile = false
        }
    }

    private suspend fun applyProfileNow(p: Profile) {
        val s = state
        if (s.lightsDirty) applyLightsNow()
        val sticksOk = !s.sticksDirty || saveSticksNow()
        val sticksMsg = s.status
        val vibOk = !s.vibrationDirty || saveVibrationNow()
        val backOk = applyBackKeysNow(p)
        when {
            !sticksOk -> s.fail(tr("Perfil «${p.name}»: $sticksMsg", "Profile “${p.name}”: $sticksMsg"))
            !vibOk -> s.fail(tr("Perfil «${p.name}»: ${s.status}", "Profile “${p.name}”: ${s.status}"))
            !backOk -> s.fail(tr("Perfil «${p.name}»: no se pudieron confirmar los botones traseros.", "Profile “${p.name}”: the back buttons couldn't be confirmed."))
            else -> s.status = tr("Perfil «${p.name}» aplicado.", "Profile “${p.name}” applied.")
        }
    }

    /** Traseros del perfil: solo se escriben los que difieren (macro completa o botón). */
    private suspend fun applyBackKeysNow(p: Profile): Boolean {
        val s = state
        if (p.backKeys.isEmpty() || s.appliedBackKeys == null) return true
        var ok = true
        for (k in BackKey.entries) {
            val want = p.backKeys[k.id] ?: continue
            val wantMacro = if (want == BACK_MACRO) p.macros[k.id] else null
            if (want == s.backKeys[k.id] && (wantMacro == null || wantMacro == s.macros[k.id])) continue
            val (mapping, steps) = withContext(Dispatchers.IO) {
                if (wantMacro != null) {
                    G9Usb.transact(MacroGcm.writeSettings(k.id, wantMacro))
                    G9Usb.transact(MacroGcm.writeSteps(k.id, wantMacro.steps))
                } else {
                    if (s.backKeys[k.id] == BACK_MACRO) G9Usb.transact(MacroGcm.clear(k.id))
                    G9Usb.transact(if (want == 0) Gcm.clearBackKey(k.id) else Gcm.setBackKey(k.id, want))
                }
                G9Usb.transact(Gcm.readBackKey(k.id))?.takeIf { it.size >= 5 }?.get(3)?.u() to
                    (if (wantMacro != null) MacroGcm.parseSteps(G9Usb.transact(MacroGcm.read(k.id))) else null)
            }
            if (mapping != null) s.backKeys[k.id] = mapping
            if (wantMacro != null && steps != null) s.macros[k.id] = wantMacro.copy(steps = steps) else if (mapping != BACK_MACRO) s.macros.remove(k.id)
            if (mapping != want) ok = false
        }
        s.appliedBackKeys = s.backKeys.toMap()
        return ok
    }

    /** Asigna un trasero: una escritura y una relectura para confirmar. */
    fun setBackKey(k: BackKey, dst: Int) = launchIo {
        val s = state
        val back = withContext(Dispatchers.IO) {
            if (s.backKeys[k.id] == BACK_MACRO) G9Usb.transact(MacroGcm.clear(k.id))
            G9Usb.transact(if (dst == 0) Gcm.clearBackKey(k.id) else Gcm.setBackKey(k.id, dst))
            G9Usb.transact(Gcm.readBackKey(k.id))?.takeIf { it.size >= 5 }?.get(3)?.u()
        }
        if (back != null) {
            s.backKeys[k.id] = back
            s.appliedBackKeys = s.backKeys.toMap()
            if (back != BACK_MACRO) s.macros.remove(k.id)
        }
        when (back) {
            null -> s.fail(tr("No pude releer ${k.label} para confirmar.", "Couldn't read ${k.label} back to confirm."))
            dst -> s.status = "${k.label}: ${backTargetName(dst)}."
            else -> s.fail(tr("El control dejó ${k.label} en ${backTargetName(back)}.", "The controller set ${k.label} to ${backTargetName(back)}."))
        }
    }

    // ---- Macros de los traseros ----

    fun openMacroEditor(k: BackKey) {
        state.editingMacro = k
        state.macroDraft = state.macros[k.id] ?: Macro()
        state.macroRecording = false
    }

    fun closeMacroEditor() {
        state.macroRecording = false
        state.editingMacro = null
    }

    private val recDown = mutableMapOf<PadKey, Long>()
    private var recLastUp = 0L
    private var recPrev = emptySet<PadKey>()

    fun startMacroRecording() {
        recDown.clear(); recLastUp = 0L; recPrev = pressedNow(state)
        state.recordedSteps.clear()
        state.macroRecording = true
    }

    fun stopMacroRecording() {
        state.macroRecording = false
        if (state.recordedSteps.isNotEmpty()) state.macroDraft = state.macroDraft.copy(steps = state.recordedSteps.toList())
    }

    /** Grabación: cada botón soltado se convierte en un paso (cuánto se mantuvo y la pausa previa). */
    private fun recordMacroInput() {
        val s = state
        if (!s.macroRecording) return
        val now = android.os.SystemClock.uptimeMillis()
        val cur = pressedNow(s) - setOf(PadKey.STICK_L, PadKey.STICK_R, PadKey.M)
        for (k in cur - recPrev) recDown[k] = now
        for (k in recPrev - cur) {
            val down = recDown.remove(k) ?: continue
            val byte = MACRO_KEY_BYTE[k] ?: continue
            val wait = if (s.recordedSteps.isEmpty()) 0 else (down - recLastUp).coerceAtLeast(0)
            s.recordedSteps.add(MacroStep(byte, holdMs = (now - down).toInt().coerceIn(5, MACRO_MAX_MS), waitMs = wait.toInt().coerceIn(0, MACRO_MAX_MS)))
            recLastUp = now
            if (s.recordedSteps.size >= MACRO_MAX_STEPS) { stopMacroRecording(); break }
        }
        recPrev = cur
    }

    /** Guarda la macro: ajustes primero y después los pasos (al revés, los ajustes borran los pasos). */
    fun saveMacro() = launchIo {
        val s = state
        val k = s.editingMacro ?: return@launchIo
        val m = s.macroDraft
        val (steps, mapping) = withContext(Dispatchers.IO) {
            G9Usb.transact(MacroGcm.writeSettings(k.id, m))
            G9Usb.transact(MacroGcm.writeSteps(k.id, m.steps))
            MacroGcm.parseSteps(G9Usb.transact(MacroGcm.read(k.id))) to
                G9Usb.transact(Gcm.readBackKey(k.id))?.takeIf { it.size >= 5 }?.get(3)?.u()
        }
        if (mapping != null) { s.backKeys[k.id] = mapping; s.appliedBackKeys = s.backKeys.toMap() }
        val ok = steps != null && steps.size == m.steps.size && steps.map { it.key } == m.steps.map { it.key }
        if (ok) s.macros[k.id] = m.copy(steps = steps!!)
        if (ok) s.status = tr("Macro de ${k.label} guardada (${m.steps.size} pasos).", "${k.label} macro saved (${m.steps.size} steps).")
        else s.fail(tr("La macro de ${k.label} no se pudo confirmar.", "The ${k.label} macro couldn't be confirmed."))
    }

    fun clearMacro(k: BackKey) = launchIo {
        val s = state
        val mapping = withContext(Dispatchers.IO) {
            G9Usb.transact(MacroGcm.clear(k.id))
            G9Usb.transact(Gcm.readBackKey(k.id))?.takeIf { it.size >= 5 }?.get(3)?.u()
        }
        if (mapping != null) { s.backKeys[k.id] = mapping; s.appliedBackKeys = s.backKeys.toMap() }
        s.macros.remove(k.id)
        s.macroDraft = Macro()
        s.status = tr("Macro de ${k.label} quitada.", "${k.label} macro removed.")
    }

    /**
     * Valores de fábrica: la acción "restaurar" de zonas muertas, luces y vibración; borrar macros y
     * dejar los traseros sin asignar; cruceta y gatillo rápido desactivados. Después se relee todo.
     * Son ~20 escrituras: solo a pedido, con confirmación.
     */
    private val FACTORY_DEAD_ZONES = mapOf(1 to 10, 2 to 10, 3 to 100, 4 to 100, 5 to 5, 6 to 5, 7 to 95, 8 to 95)

    fun factoryReset() = launchIo {
        state.busy = true
        try { factoryResetNow() } finally { state.busy = false }
        state.status = tr("Valores de fábrica restaurados. Leyendo el control…", "Factory settings restored. Reading the controller…")
        readAll()
    }

    private suspend fun factoryResetNow() {
        withContext(Dispatchers.IO) {
            // Zonas muertas y vibración no tienen "restaurar" en el G9 (solo leer/escribir): se escriben
            // los valores predeterminados de la app oficial (LegionG9DevCfg / JoystickCfg / TriggerCfg / VibrationCfg).
            FACTORY_DEAD_ZONES.forEach { (id, v) -> G9Usb.transact(Gcm.setDeadZone(id, v)) }
            G9Usb.transact(Gcm.setTriggerQuick(false, false))
            G9Usb.transact(Gcm.setFunc(Gcm.FUNC_DPAD_DIAGONAL_LOCK, false))
            G9Usb.transact(Gcm.setFunc(Gcm.FUNC_LSTICK_SWAP_DPAD, false))
            G9Usb.transact(Gcm.setLayout(false))
            G9Usb.transact(Gcm.setVibration(Vibration(75, 75, false, false, 75, 75, false, false)))
            G9Usb.transact(Gcm.restoreStrip(1))
            G9Usb.transact(Gcm.restoreStrip(2))
            G9Usb.transact(Gcm.restoreHome())
            for (k in BackKey.entries) {
                if (state.backKeys[k.id] == BACK_MACRO) G9Usb.transact(MacroGcm.clear(k.id))
                G9Usb.transact(Gcm.clearBackKey(k.id))
            }
        }
    }

    fun setAbSwap(on: Boolean) = launchIo {
        val back = withContext(Dispatchers.IO) {
            G9Usb.transact(Gcm.setLayout(on))
            G9Usb.transact(Gcm.READ_LAYOUT)?.getOrNull(1)?.u()
        }
        if (back != null) state.abSwapped = back == 0x04
        if (state.abSwapped == on) state.status = tr("Distribución guardada.", "Layout saved.")
        else state.fail(tr("El control no confirmó la distribución.", "The controller didn't confirm the layout."))
    }

    /** Exportar: el sistema pregunta dónde guardar el archivo (por defecto en Descargas). */
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(Profiles.exportJson(state.profiles).toByteArray()) }
        }.onSuccess { state.status = tr("Respaldo guardado (${state.profiles.size} perfiles).", "Backup saved (${state.profiles.size} profiles).") }
            .onFailure { state.fail(tr("Error al guardar el respaldo: ${it.message}", "Error saving the backup: ${it.message}")) }
    }

    /** Importar: se agregan los perfiles del archivo; los de igual nombre se reemplazan. */
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val got = Profiles.importJson(text)
            val names = got.map { it.name }.toSet()
            var id = System.currentTimeMillis()
            saveProfiles(state.profiles.filter { it.name !in names } + got.map { it.copy(id = id++) })
            got.size
        }.onSuccess { state.status = if (it == 1) tr("Se importó 1 perfil.", "Imported 1 profile.") else tr("Se importaron $it perfiles.", "Imported $it profiles.") }
            .onFailure { state.fail(tr("Error: el archivo no es un respaldo de TBF Companion.", "Error: the file isn't a TBF Companion backup.")) }
    }

    fun exportProfiles() = exportLauncher.launch(tr("G9Control-perfiles", "G9Control-profiles") + "-${java.time.LocalDate.now()}.json")
    fun importProfiles() = importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))

    /** Fondo de la app: una imagen o GIF de la tablet (el permiso de leerlo queda guardado). */
    private val backgroundLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Prefs.setBackgroundUri(this, uri.toString()); Prefs.setBackground(this, AppBackground.IMAGE)
        ThemeState.backgroundUri = uri.toString(); ThemeState.background = AppBackground.IMAGE
    }
    fun pickBackground() = backgroundLauncher.launch(arrayOf("image/*"))

    /** Fondo del panel de juego: su propia imagen o GIF. */
    private val panelBackgroundLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Prefs.setPanelUri(this, uri.toString()); Prefs.setPanelBackground(this, PanelBackground.IMAGE)
        ThemeState.panelUri = uri.toString(); ThemeState.panelBackground = PanelBackground.IMAGE
        ImageCache.preload(this, "panel", uri.toString())
    }
    fun pickPanelBackground() = panelBackgroundLauncher.launch(arrayOf("image/*"))

    /** Fondo de la vista previa del panel: una imagen propia (por ejemplo una captura de un juego). */
    private val previewLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Prefs.setPreviewUri(this, uri.toString()); ThemeState.previewUri = uri.toString()
    }
    fun pickPreviewImage() = previewLauncher.launch(arrayOf("image/*"))

    /** Música ambiental: un archivo de audio propio. */
    private val musicLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Prefs.setMusicUri(this, uri.toString()); Prefs.setMusic(this, true)
        AmbientMusic.restart(this)
    }
    fun pickMusic() = musicLauncher.launch(arrayOf("audio/*"))

    /**
     * Fondo de la ventana con el fondo de pantalla: el velo REEMPLAZA los píxeles (modo SRC) en vez de sumarse. Con un
     * ColorDrawable translúcido la ventana no se limpiaba entre cuadros: el velo se apilaba hasta tapar el fondo y
     * quedaban restos de la página anterior.
     */
    private class VeilDrawable(private val color: Int) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = canvas.drawColor(color, android.graphics.PorterDuff.Mode.SRC)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Color del velo sobre el fondo de pantalla: azul marino (u hielo en claro) con la opacidad de "Oscurecer". */
    private fun wallpaperVeil(dark: Boolean, dim: Int): Int {
        val alpha = ((0.15f + 0.75f * dim / 100f) * 255).toInt().coerceIn(0, 255)
        return if (dark) android.graphics.Color.argb(alpha, 0x0A, 0x0F, 0x1F) else android.graphics.Color.argb(alpha, 0xEA, 0xF0, 0xFA)
    }

    /**
     * Con el fondo de pantalla: Android lo dibuja detrás y la ventana pone el velo encima. Sin él: la ventana es opaca
     * del color de fondo del tema (lo que se vea bajo la barra de estado coincide con la app).
     */
    private fun showWallpaper(on: Boolean, dark: Boolean, dim: Int) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        if (on) window.addFlags(flag) else window.clearFlags(flag)
        window.setBackgroundDrawable(VeilDrawable(
            if (on) wallpaperVeil(dark, dim) else if (dark) android.graphics.Color.rgb(0x0A, 0x0F, 0x1F) else android.graphics.Color.rgb(0xEA, 0xF0, 0xFA)))
    }

    fun saveProfiles(list: List<Profile>) {
        state.profiles = list
        Profiles.save(this, list)
    }

    /** Permiso de lista de apps de ZUI; al responder, se recarga la lista. */
    private val appListPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        state.appsReload++
    }

    fun requestAppListPermission() {
        if (checkSelfPermission(AutoRules.PERM_APP_LIST) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        appListPermission.launch(AutoRules.PERM_APP_LIST)
    }

    fun switchMode(t: Target) = launchIo {
        state.status = ModeSwitcher.switchTo(this@MainActivity, t)
        state.connected = false
    }

    /** Vibra ~1 s un lado, repitiendo el comando cada 200 ms como la app oficial. */
    fun testRumble(l: Int, r: Int) = launchIo {
        withContext(Dispatchers.IO) {
            repeat(5) {
                G9Usb.transact(Gcm.rumble(l, r), expectReply = false)
                delay(200)
            }
            G9Usb.transact(Gcm.rumble(0, 0), expectReply = false)
        }
        state.status = tr("Prueba de vibración enviada.", "Vibration test sent.")
    }

    fun console(hex: String, addChecksum: Boolean) = launchIo {
        var b = Gcm.parseHex(hex) ?: run { state.fail(tr("Hex inválido.", "Invalid hex.")); return@launchIo }
        if (addChecksum) {
            b = b.copyOf(b.size + 1)
            b[b.size - 1] = Gcm.checksum(b)
        }
        val r = withContext(Dispatchers.IO) { G9Usb.transact(b) }
        state.consoleOut = "→ ${Gcm.hex(b)}\n← ${Gcm.hex(r)}"
    }
}

/**
 * Páginas. HOME…PROFILES y AUTO son del G9 (HOME = su inicio, AUTO = modo por juego, TEST = "Botones"); TABLET_HOME,
 * BATTERY, SCREEN y GAMEPANEL son de la tablet (sirven sin el G9); DIAGNOSTICS = "Ajustes", en las dos secciones.
 */
enum class Page { HOME, LIGHTS, STICKS, VIBRATION, TEST, BACK, PROFILES, AUTO, G9_PREFS, TABLET_HOME, BATTERY, SCREEN, GAMEPANEL, CONTROLS, DIAGNOSTICS, PANEL }

/**
 * Las tres partes de la app: la tablet (para cualquiera con la Y700 y cualquier control), el G9 (con sus
 * preferencias) y los ajustes de la app. Cada una con sus pestañas, en el orden de LB/RB; LT/RT pasan de una a otra.
 */
enum class Section(val pages: List<Page>) {
    TABLET(listOf(Page.TABLET_HOME, Page.BATTERY, Page.SCREEN, Page.GAMEPANEL, Page.PANEL, Page.CONTROLS, Page.TEST)),
    G9(listOf(Page.HOME, Page.AUTO, Page.LIGHTS, Page.STICKS, Page.VIBRATION, Page.BACK, Page.G9_PREFS)),
    SETTINGS(listOf(Page.DIAGNOSTICS)),
}

enum class LightTarget { BOTH, LEFT, RIGHT, HOME }

data class Drift(val left: Boolean, val measuring: Boolean, val progress: Float = 0f, val maxPct: Int = 0, val suggest: Int = 0)

class AppState {
    var section by mutableStateOf(Section.TABLET)
    var page by mutableStateOf(Page.TABLET_HOME)

    /** Cambia de sección, a su primera pestaña (o a Ajustes, si se estaba ahí). */
    fun goTo(sec: Section) {
        if (sec == section) return
        section = sec
        if (page !in sec.pages) page = sec.pages.first()
    }
    /** Línea de estado de la barra inferior. Asignarla la marca como normal; [fail] la marca como problema. */
    var status: String
        get() = statusText
        set(v) { statusText = v; statusError = false }
    private var statusText by mutableStateOf("")
    /** El último mensaje es un problema: se muestra en rojo y no se borra solo. */
    var statusError by mutableStateOf(false)
        private set
    fun fail(msg: String) { statusText = msg; statusError = true }
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var padMode by mutableStateOf<PadMode?>(null)
    var deviceText by mutableStateOf("")
    var allDevicesText by mutableStateOf("")
    var mode by mutableStateOf<ModeSwitcher.ModeInfo?>(null)

    // Luces: lo que se edita y lo último que se leyó o aplicó (null = desconocido).
    var lightTarget by mutableStateOf(LightTarget.BOTH)
    var left by mutableStateOf(Light(1))
    var right by mutableStateOf(Light(2))
    var home by mutableStateOf(Light(0, hue = 0, sat = 0, lum = 100))
    var homeOff by mutableStateOf(false)
    var appliedLeft by mutableStateOf<Light?>(null)
    var appliedRight by mutableStateOf<Light?>(null)
    var appliedHome by mutableStateOf<Light?>(null)
    /** Último cambio en las luces (uptime): la barra de "Guardando en 2 s" cuenta desde aquí. */
    var lightsChangedAt by mutableStateOf(0L)
    /** Prueba de botones en curso (se entra a propósito con "Probar botones"). */
    var testMode by mutableStateOf(false)
    /** Los botones del control se usan para probar o grabar, no para navegar. */
    val capturing get() = testMode || macroRecording
    /** El dedo está sobre algún slider. */
    var sliderHeld by mutableStateOf(false)
    fun homeToSend() = home.copy(effect = 1, lum = if (homeOff) 0 else home.lum)
    val lightsDirty get() = left != appliedLeft || right != appliedRight || homeToSend() != appliedHome

    // Sticks y gatillos.
    val deadZones = mutableStateMapOf(1 to 10, 2 to 10, 3 to 100, 4 to 100, 5 to 5, 6 to 5, 7 to 95, 8 to 95)
    var appliedDeadZones by mutableStateOf<Map<Int, Int>?>(null)
    var quickL by mutableStateOf(false)
    var quickR by mutableStateOf(false)
    var appliedQuick by mutableStateOf<Pair<Boolean, Boolean>?>(null)
    // Cruceta: bloqueo de diagonales e intercambio con el stick izquierdo.
    var dpadLock by mutableStateOf(false)
    var swapDpad by mutableStateOf(false)
    /** Diagonales y stick izq. ↔ cruceta, como están en el control. */
    var appliedFuncs by mutableStateOf<Pair<Boolean, Boolean>?>(null)
    val funcs get() = dpadLock to swapDpad
    /** Botones traseros: id de origen (1–4) → byte del botón que copian (0 = sin asignar). */
    val backKeys = mutableStateMapOf<Int, Int>()
    var appliedBackKeys by mutableStateOf<Map<Int, Int>?>(null)
    /** A↔B y X↔Y intercambiados (null = sin leer). */
    var abSwapped by mutableStateOf<Boolean?>(null)
    /** Macros guardadas en el control, por trasero (id 1–4). */
    val macros = mutableStateMapOf<Int, Macro>()
    /** Editor de macro abierto (null = cerrado) y lo que se está editando. */
    var editingMacro by mutableStateOf<BackKey?>(null)
    var macroDraft by mutableStateOf(Macro())
    var macroRecording by mutableStateOf(false)
    val recordedSteps = mutableStateListOf<MacroStep>()
    val sticksDirty get() = deadZones.toMap() != appliedDeadZones || (quickL to quickR) != appliedQuick ||
        (appliedFuncs != null && funcs != appliedFuncs)

    // Vibración.
    var vibration by mutableStateOf(Vibration())
    var appliedVibration by mutableStateOf<Vibration?>(null)
    var triggerPreview by mutableStateOf(true)
    val vibrationDirty get() = vibration != appliedVibration

    /** Información del control (Diagnóstico). */
    var devInfo by mutableStateOf<List<Pair<String, String>>>(emptyList())

    // Perfiles guardados en la tablet.
    var profiles by mutableStateOf<List<Profile>>(emptyList())
    /** Ya se leyó todo del control: se puede guardar un perfil con valores reales. */
    val configKnown get() = appliedLeft != null && appliedDeadZones != null && appliedVibration != null && appliedFuncs != null

    // Entrada en vivo.
    var lx by mutableFloatStateOf(0f)
    var ly by mutableFloatStateOf(0f)
    var rx by mutableFloatStateOf(0f)
    var ry by mutableFloatStateOf(0f)
    var lt by mutableFloatStateOf(0f)
    var rt by mutableFloatStateOf(0f)
    var inputDevice by mutableStateOf<String?>(null)
    var hatX by mutableFloatStateOf(0f)
    var hatY by mutableFloatStateOf(0f)
    /** Teclas del control apretadas ahora (código de Android). */
    val pressedKeys = mutableStateMapOf<Int, Unit>()
    /** Botones que ya se apretaron en la prueba. */
    val testedKeys = mutableStateMapOf<PadKey, Unit>()
    /** Botones sin lugar en el dibujo (por ejemplo, traseros), el más reciente primero. */
    val otherKeys = mutableStateListOf<String>()
    /** Vista + Menú para la franja de modo (igual que la guía). */
    val hold = HoldTracker()
    /** Modo al que lleva la franja (Android, salvo que se elija otro en Inicio o en ajustes rápidos). */
    var modeTarget by mutableStateOf(PadMode.ANDROID)

    // Cambio automático por app.
    var autoEnabled by mutableStateOf(true)
    var autoRules by mutableStateOf<Map<String, AutoTarget>>(emptyMap())
    var autoRemindReturn by mutableStateOf(true)
    var autoServiceOn by mutableStateOf(false)
    var openOnConnect by mutableStateOf(false)
    /** Medición de desvío en curso o su resultado. */
    var drift by mutableStateOf<Drift?>(null)
    /** B mantenido: cuándo empezó (uptime ms) y cuánto hay que mantenerlo; null si no se mantiene. */
    var bHold by mutableStateOf<Pair<Long, Long>?>(null)
    /** Se cumplieron los 2 s de B: la barra dice "Suelta para salir" y el aro queda lleno hasta soltarlo. */
    var bReady by mutableStateOf(false)
    var centered by mutableStateOf(false)
    /** Configuración inicial abierta (la primera vez, o desde Ajustes). */
    var showSetup by mutableStateOf(false)
    /** Cambia cuando hay que volver a cargar la lista de apps (por ejemplo, tras dar el permiso). */
    var appsReload by mutableIntStateOf(0)

    var consoleOut by mutableStateOf("")
    var logText by mutableStateOf("")

    fun refreshLog() {
        logText = synchronized(G9Usb.log) { G9Usb.log.toList().takeLast(40).joinToString("\n") }
    }
}
