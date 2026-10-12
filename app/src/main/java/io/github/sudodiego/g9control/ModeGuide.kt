package io.github.sudodiego.g9control

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Guía y avisos de cambio de modo, en una ventana de accesibilidad (no necesita permisos extra): la
 * tarjeta con el recorrido de Vista + Menú, el aviso pequeño y la etiqueta del modo. Sin bloqueo no
 * recibe toques; con bloqueo cubre la pantalla y, si se pide, también toma el foco (sticks y gatillos).
 */
class ModeGuide(private val ctx: AccessibilityService) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val ui = GuideUi()
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var next: PadMode? = null
    private val km = ctx.getSystemService(KeyguardManager::class.java)
    private val timeout = Runnable { hide() }

    /**
     * Si nadie hace caso a la tarjeta, se achica a un aviso pequeño arriba que sigue reteniendo Vista/Menú y
     * queda hasta que el control cambie de modo (para no quedarse en DS5/X-Input sin saberlo).
     */
    private val minimize = Runnable {
        val t = ui.target
        if (t != null && ui.hold && !ui.done && !ui.mini) {
            val n = next
            hide()
            main.postDelayed(reshow, 400)
            pendingMini = t to n
        }
    }
    private var pendingMini: Pair<PadMode, PadMode?>? = null
    private val reshow = Runnable { pendingMini?.let { (t, n) -> pendingMini = null; show(t, next = n, mini = true) } }

    /**
     * Se retienen Start/Select mientras la tarjeta pide mantenerlos y también mientras muestra "Listo",
     * para que al soltar tarde (o volver a apretar por inercia) el juego no los reciba. El aviso
     * pequeño también retiene, pero no se tapa la pantalla.
     */
    val isRetaining get() = (view != null && ui.hold) || android.os.SystemClock.uptimeMillis() < retainUntil

    /** Después de "Listo" la guía se va rápido, pero Vista/Menú se siguen reteniendo hasta este momento. */
    private var retainUntil = 0L

    /** Vista (Select) o Menú (Start) apretado/soltado: arranca o reinicia la barra de progreso. */
    fun onKey(keyCode: Int, down: Boolean) = ui.keys.onKey(keyCode, down, counting = !ui.done)

    /**
     * Muestra la guía hacia [t]. Con la pantalla bloqueada no muestra nada y devuelve false.
     * [mini]: directamente el aviso pequeño (por ejemplo al desbloquear).
     */
    fun show(t: PadMode, hold: Boolean = true, next: PadMode? = null, mini: Boolean = false, big: Boolean = false): Boolean {
        main.removeCallbacks(reshow)
        pendingMini = null
        if (km.isKeyguardLocked) { hide(); return false }
        // Preferencias de los avisos (se leen cada vez: se pueden cambiar desde la app en cualquier momento).
        ui.anim = AutoRules.noticeAnim(ctx)
        val small = (mini || (!big && AutoRules.guideSmall(ctx))) && hold
        // Cada guía tiene su propio bloqueo; la etiqueta del modo nunca bloquea.
        block = when { !hold -> false; small -> AutoRules.miniBlock(ctx); else -> AutoRules.guideBlock(ctx) }
        ui.blocking = block
        ui.pos = if (hold) AutoRules.guidePos(ctx) else AutoRules.noticePos(ctx)
        // Tarjeta, aviso pequeño y etiqueta de esquina usan ventanas distintas: se cambia la ventana.
        // También se rehace si cambió la posición o el bloqueo.
        val win = when { !hold -> Win.CORNER; small -> Win.TOP; else -> Win.FULL }
        if (view != null && (kind != win || madeWith != Triple(ui.pos, block, grabFor(small)))) removeNow()
        this.next = next
        val current = ModeSwitcher.detect()
        ui.mini = small
        ui.hold = hold
        ui.target = t
        ui.done = false
        ui.keys.reset()
        ui.current = current
        ui.path = pathFrom(current, t, hold)
        if (view == null) create(win)
        ui.visible = true
        main.removeCallbacks(timeout)
        main.removeCallbacks(minimize)
        when {
            small -> Unit                                   // queda hasta que cambie el modo
            hold -> main.postDelayed(minimize, MINIMIZE_MS)
            else -> main.postDelayed(timeout, 45_000)       // aviso de cambio por la app: por si no se confirma
        }
        update()
        return true
    }

    fun update() {
        val t = ui.target ?: return
        if (view == null || ui.done) return
        val current = ModeSwitcher.detect()
        if (current != ui.current) {
            ui.holdSince?.let {
                android.util.Log.d("G9Auto", "cambio de modo tras mantener ${android.os.SystemClock.uptimeMillis() - it} ms")
            }
            ui.keys.modeChanged()
        }
        ui.current = current
        if (current != null && current !in ui.path) ui.path = pathFrom(current, t, ui.hold)
        if (current == t) {
            ui.done = true
            main.removeCallbacks(timeout)
            main.removeCallbacks(minimize)
            val n = next
            if (n != null) main.postDelayed({ show(n, mini = ui.mini) }, 1200)
            else if (ui.mini) {
                retainUntil = android.os.SystemClock.uptimeMillis() + 4500
                main.postDelayed(timeout, 1500)
            }
            else if (ui.hold) {
                retainUntil = android.os.SystemClock.uptimeMillis() + 4200
                main.postDelayed(timeout, 1200)
            } else main.postDelayed(timeout, 1300)
        }
    }

    /** La guía está en pantalla pidiendo mantener Vista + Menú (no el aviso ni el "Listo"). */
    val isGuiding get() = view != null && ui.visible && ui.hold && !ui.done

    /** Bloqueo elegido en Ajustes para el aviso que está en pantalla (la etiqueta del modo nunca bloquea). */
    private var block = false
    val blocksInput get() = block && isGuiding

    /** Lo que estaba en pantalla al apagarse la pantalla, para volver a mostrarlo al desbloquear. */
    private var beforeLock: Triple<PadMode, Boolean, PadMode?>? = null

    fun suspendForLock() {
        val t = ui.target
        // Si ya había algo guardado (pantalla apagada dos veces seguidas), se conserva.
        if (view != null && t != null && ui.hold && !ui.done) beforeLock = Triple(t, ui.mini, next)
        android.util.Log.d("G9Auto", "pantalla apagada: guarda $beforeLock")
        hide()
    }

    /** Vuelve a mostrar la guía que había antes de bloquear, si el control aún no llegó a ese modo. */
    fun resumeAfterLock(tries: Int = 0): Boolean {
        val (t, mini, n) = beforeLock ?: return false
        val mode = ModeSwitcher.detect()
        android.util.Log.d("G9Auto", "desbloqueo: guía $t, modo $mode, bloqueada=${km.isKeyguardLocked}, intento $tries")
        if (mode == t) { beforeLock = null; return false }
        // Justo al desbloquear (sobre todo con la cara) la pantalla de bloqueo puede seguir activa un
        // momento, o el control se está reconectando: se reintenta unas veces.
        if (km.isKeyguardLocked || mode == null) {
            if (tries < 8) main.postDelayed({ resumeAfterLock(tries + 1) }, 500)
            return true
        }
        beforeLock = null
        return show(t, next = n, mini = mini)
    }

    /** Salida de emergencia del bloqueo: mantener presionada la pantalla 2 s. */
    internal fun escape() { block = false; hide() }

    /** Ejemplo para ver posición y animación desde la app: se va solo a los pocos segundos. */
    fun preview(kind: String) {
        val now = ModeSwitcher.detect() ?: PadMode.ANDROID
        val other = if (now == PadMode.ANDROID) PadMode.XINPUT else PadMode.ANDROID
        when (kind) {
            "mini" -> show(other, mini = true)
            "big" -> show(other, big = true)
            else -> show(PadMode.DS5, hold = false)
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 4000)
    }

    fun hide() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(minimize)
        main.removeCallbacks(reshow)
        pendingMini = null
        val v = view ?: return
        ui.visible = false
        // Se espera la animación de salida antes de quitar la ventana.
        main.postDelayed({
            if (view === v && !ui.visible) {
                runCatching { wm.removeView(v) }
                owner?.destroy()
                view = null; owner = null; kind = null; ui.target = null; next = null
            }
        }, 350)
    }

    /** Quita la ventana sin animación (al pasar de tarjeta a aviso pequeño o al revés). */
    private fun removeNow() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.destroy()
        view = null; owner = null; kind = null
    }

    /** Ventana de la guía: pantalla completa (tarjeta), arriba al centro (aviso) o esquina (etiqueta). */
    private enum class Win { FULL, TOP, CORNER }
    private var kind: Win? = null
    private var madeWith: Triple<NoticePos, Boolean, Boolean>? = null

    /** La ventana toma el foco para descartar sticks y gatillos (solo con bloqueo). */
    private fun grabFor(small: Boolean) = block && (if (small) AutoRules.miniBlockSticks(ctx) else AutoRules.blockSticks(ctx))

    private fun create(win: Win) {
        val small = win != Win.FULL
        val o = OverlayOwner().also { it.start() }
        val cv = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { GuideOverlay(ui) { escape() } }
        }
        // La ventana cubre la pantalla y el aviso se ubica dentro (así sus animaciones no quedan recortadas).
        // Con bloqueo recibe los toques (no pasan al juego); sin bloqueo los deja pasar todos.
        val size = WindowManager.LayoutParams.MATCH_PARENT
        val touch = if (block) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val grab = grabFor(small)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Con bloqueo la ventana toma el foco: así los sticks y gatillos (que el servicio de accesibilidad no
            // puede filtrar) le llegan a ella y no al juego. Sin bloqueo no toma el foco.
            (if (grab) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or touch or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (grab) {
            // Se descarta todo lo que llegue del control a esta ventana (sticks, gatillos y botones).
            cv.isFocusable = true
            cv.isFocusableInTouchMode = true
            cv.setOnGenericMotionListener { _, _ -> true }
            cv.setOnKeyListener { _, _, _ -> true }
        }
        runCatching { wm.addView(cv, lp) }.onSuccess { view = cv; owner = o; kind = win; madeWith = Triple(ui.pos, block, grab); if (grab) cv.requestFocus() }
            .onFailure { o.destroy() }
    }

    companion object {
        /** Orden en que Start + Select recorre los modos (confirmado en el control). */
        val CYCLE = listOf(PadMode.ANDROID, PadMode.XINPUT, PadMode.DS5)

        fun steps(from: PadMode, to: PadMode) =
            (CYCLE.indexOf(to) - CYCLE.indexOf(from) + CYCLE.size) % CYCLE.size

        /** Modos que se recorren desde [from] hasta [to]. Sin Start + Select (cambio por la app) es directo. */
        private fun pathFrom(from: PadMode?, to: PadMode, hold: Boolean): List<PadMode> {
            if (from == null || from == to) return listOf(to)
            if (!hold) return listOf(from, to)
            val i = CYCLE.indexOf(from)
            return (0..steps(from, to)).map { CYCLE[(i + it) % CYCLE.size] }
        }
    }
}

/** Ciclo de vida mínimo para poder usar Compose en una ventana de servicio (la guía y el panel de juego). */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

// ---------------------------------------------------------------------------------------------
// Dibujo de la guía
// ---------------------------------------------------------------------------------------------

// Con el tema de lo que se dibuja sobre el juego (Ajustes → Juego → Panel de juego → Tema): en claro, tarjetas
// blancas azuladas con texto marino; en oscuro, azul marino con texto blanco.
private val lightUi get() = ThemeState.panelLight
private val CARD get() = if (lightUi) Color(0xFFF3F6FB) else Color(0xFF111A2C)
private val CARD_DONE get() = if (lightUi) Color(0xFFD7F3E3) else Color(0xFF145A35)
private val ACCENT get() = if (lightUi) Color(0xFF1E7FC8) else Color(0xFF5CC8FF)
private val OK get() = if (lightUi) Color(0xFF1FA463) else Color(0xFF3DDC84)
private val MUTED get() = if (lightUi) Color(0xFF56607A) else Color(0xFF9AA6BF)
private val LINE get() = if (lightUi) Color(0xFFD5DCE8) else Color(0xFF2A3550)
/** Texto principal sobre las tarjetas. */
private val FG get() = if (lightUi) Color(0xFF14203A) else Color.White

/** Estado que dibuja la guía. */
private class GuideUi {
    var visible by mutableStateOf(false)
    var hold by mutableStateOf(true)
    var path by mutableStateOf(listOf<PadMode>())
    var current by mutableStateOf<PadMode?>(null)
    var target by mutableStateOf<PadMode?>(null)
    var done by mutableStateOf(false)
    /** Aviso pequeño arriba en vez de la tarjeta (la tarjeta se ignoró o se acaba de desbloquear). */
    var mini by mutableStateOf(false)
    /** Vista + Menú: desde cuándo se mantienen y si hay que soltarlos primero. */
    val keys = HoldTracker()
    val holdSince get() = keys.holdSince
    val release get() = keys.release
    var pos by mutableStateOf(NoticePos.TOP_END)
    /** La ventana bloquea los toques (y ocupa toda la pantalla). */
    var blocking by mutableStateOf(false)
    var anim by mutableStateOf(NoticeAnim.SMOOTH)
}

/** Entrada y salida de los avisos pequeños según la animación elegida (deslizan desde su borde). */
private fun smallEnter(ui: GuideUi): EnterTransition = when (ui.anim) {
    NoticeAnim.NONE -> EnterTransition.None
    NoticeAnim.SMOOTH -> fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.9f)
    NoticeAnim.SLIDE -> fadeIn(tween(200)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) {
        if (ui.pos.v == 1) it else -it
    }
}

private fun smallExit(ui: GuideUi): ExitTransition = when (ui.anim) {
    NoticeAnim.NONE -> ExitTransition.None
    NoticeAnim.SMOOTH -> fadeOut(tween(400))
    NoticeAnim.SLIDE -> fadeOut(tween(250)) + slideOutVertically(tween(300)) {
        if (ui.pos.v == 1) it else -it
    }
}

@Composable
private fun GuideOverlay(ui: GuideUi, onEscape: () -> Unit) {
    // La ventana cubre la pantalla; el aviso pequeño y la etiqueta se ubican en su posición.
    // Con bloqueo, mantener presionada la pantalla 2 s lo quita (salida de emergencia).
    Box(
        Modifier.fillMaxSize().then(
            if (ui.blocking) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val up = withTimeoutOrNull(2000) { waitForUpOrCancellation() }
                    if (up == null) onEscape()
                }
            } else Modifier
        ),
        contentAlignment = androidx.compose.ui.BiasAlignment(ui.pos.h.toFloat(), ui.pos.v.toFloat())
    ) { GuideContent(ui) }
}

@Composable
private fun GuideContent(ui: GuideUi) {
    // Entrada/salida: el fondo se oscurece y la tarjeta aparece con un leve zoom.
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = ui.visible
    if (!ui.hold) {
        // Cambio hecho por la app al abrir un juego: etiqueta pequeña en la esquina, sin tapar nada.
        AnimatedVisibility(
            shown,
            modifier = Modifier.padding(top = 14.dp, end = 16.dp, start = 16.dp, bottom = 14.dp),
            enter = smallEnter(ui),
            exit = smallExit(ui)
        ) { Banner(ui) }
        return
    }
    if (ui.mini) {
        // Ventana del tamaño del aviso: sin fondo oscuro ni capa a pantalla completa.
        AnimatedVisibility(
            shown,
            modifier = Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 20.dp),
            enter = smallEnter(ui),
            exit = smallExit(ui)
        ) { MiniHint(ui) }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Hay que hacer algo (Vista + Menú): tarjeta al centro con el fondo oscurecido.
        val none = ui.anim == NoticeAnim.NONE
        AnimatedVisibility(
            shown,
            enter = if (none) EnterTransition.None else fadeIn(tween(220)),
            exit = if (none) ExitTransition.None else fadeOut(tween(300))
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }
        AnimatedVisibility(
            shown,
            enter = when (ui.anim) {
                NoticeAnim.NONE -> EnterTransition.None
                NoticeAnim.SLIDE -> fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 3 }
                NoticeAnim.SMOOTH -> fadeIn(tween(220)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.85f)
            },
            exit = when (ui.anim) {
                NoticeAnim.NONE -> ExitTransition.None
                NoticeAnim.SLIDE -> fadeOut(tween(250)) + slideOutVertically(tween(300)) { it / 3 }
                NoticeAnim.SMOOTH -> fadeOut(tween(250)) + scaleOut(tween(300), targetScale = 0.9f)
            }
        ) { GuideCard(ui) }
    }
}

@Composable
private fun GuideCard(ui: GuideUi) {
    val bg by animateColorAsState(if (ui.done) CARD_DONE else CARD, tween(400), label = "bg")
    val target = ui.target
    val current = ui.current
    val left = if (current != null && target != null) ModeGuide.steps(current, target) else 0
    val total = (ui.path.size - 1).coerceAtLeast(1)
    val title = when {
        ui.done -> tr("Listo: ${target?.label}", "Done: ${target?.label}")
        !ui.hold -> tr("Cambiando a ${target?.label}…", "Switching to ${target?.label}…")
        ui.release -> tr("Suelta y vuelve a mantener", "Release and hold again")
        left == 1 && total > 1 -> tr("¡Bien! Una vez más", "Nice! One more time")
        else -> tr("Mantén Vista + Menú", "Hold View + Menu")
    }
    val subtitle = when {
        ui.done && ui.hold -> tr("Ya puedes soltar los botones.", "You can release the buttons now.")
        ui.done -> tr("El control ya está en ${target?.label}.", "The controller is now in ${target?.label}.")
        !ui.hold -> tr("El control se está reconectando.", "The controller is reconnecting.")
        current == null -> tr("Cambiando de modo…", "Switching mode…")
        ui.release -> tr("El control solo cuenta Vista + Menú si los aprietas de nuevo.", "The controller only counts View + Menu if you press them again.")
        left == 1 -> tr("Unos 2 segundos, hasta que cambie a ${target?.label}.", "About 2 seconds, until it switches to ${target?.label}.")
        else -> tr("Unos 2 segundos por cada cambio: faltan $left.", "About 2 seconds per switch: $left to go.")
    }

    Column(
        Modifier.padding(24.dp).width(500.dp).heightIn(min = 410.dp)
            .clip(RoundedCornerShape(32.dp)).background(bg)
            .border(1.dp, FG.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
            .padding(horizontal = 36.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Ícono superior: botones que laten, carga o ✓.
        Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = when { ui.done -> 2; ui.hold -> 0; else -> 1 },
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith fadeOut() },
                label = "icon"
            ) { kind ->
                when (kind) {
                    0 -> ButtonsHint()
                    1 -> CircularProgressIndicator(Modifier.size(48.dp), color = ACCENT, strokeWidth = 4.dp)
                    else -> Box(
                        Modifier.size(64.dp).clip(CircleShape).background(OK),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Check, null, Modifier.size(40.dp), tint = Color.White) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = FG, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = if (ui.done) (if (lightUi) Color(0xFF145A35) else Color(0xFFCFF5DF)) else MUTED, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        HoldBar(ui)
        Spacer(Modifier.height(22.dp))
        Box(Modifier.height(80.dp), contentAlignment = Alignment.Center) {
            if (ui.path.size > 1) Stepper(ui.path, current, ui.done)
        }
    }
}

/** Tiempo aproximado que hay que mantener Vista + Menú para que el control cambie de modo (medido con getevent: 2,02–2,03 s). */
internal const val HOLD_MS = 2000

/** Si la tarjeta sigue sin respuesta este tiempo, se achica al aviso pequeño de arriba. */
private const val MINIMIZE_MS = 20_000L

/** Avance (0..1) de mantener Vista + Menú: se llena en HOLD_MS, vuelve a cero al soltar. */
@Composable
private fun rememberHoldProgress(ui: GuideUi): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    val since = ui.holdSince
    LaunchedEffect(since, ui.done) {
        when {
            ui.done -> progress.animateTo(1f, tween(200))
            since != null -> {
                val elapsed = (android.os.SystemClock.uptimeMillis() - since).coerceAtLeast(0)
                val start = (elapsed.toFloat() / HOLD_MS).coerceAtMost(1f)
                progress.snapTo(start)
                progress.animateTo(1f, tween(((1f - start) * HOLD_MS).toInt(), easing = LinearEasing))
            }
            else -> progress.animateTo(0f, tween(250))
        }
    }
    return progress
}

/**
 * Barra que se llena mientras se mantienen Vista + Menú (HOLD_MS). Vuelve a cero al soltar y se
 * completa al confirmarse el cambio. Solo se ve cuando la guía pide mantener los botones.
 */
@Composable
private fun HoldBar(ui: GuideUi) {
    val progress = rememberHoldProgress(ui)
    val visible = ui.hold
    Box(
        Modifier.width(320.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(if (visible) LINE else Color.Transparent)
    ) {
        if (visible) Box(
            Modifier.fillMaxHeight().fillMaxWidth(progress.value)
                .clip(RoundedCornerShape(5.dp)).background(if (ui.done) OK else ACCENT)
        )
    }
}

/**
 * Aviso pequeño que queda arriba cuando la tarjeta se ignoró: recuerda en qué modo está el control y
 * qué hacer sin tapar la pantalla. Pasa a verde al llegar al modo pedido.
 */
@Composable
private fun MiniHint(ui: GuideUi) {
    val bg by animateColorAsState(if (ui.done) CARD_DONE else CARD, tween(400), label = "mini")
    val target = ui.target?.label
    // El mismo progreso que la barra de la tarjeta, como relleno del aviso de izquierda a derecha.
    val progress = rememberHoldProgress(ui)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg.copy(alpha = 0.92f))
            .drawBehind {
                if (!ui.done) drawRect(ACCENT.copy(alpha = 0.32f), size = size.copy(width = size.width * progress.value))
            }
            .border(1.dp, if (ui.done) Color.Transparent else ACCENT.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ui.done) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(OK), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text(tr("Control en $target", "Controller in $target"), color = FG, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        } else {
            RoundKey(menu = false, small = true)
            Spacer(Modifier.width(4.dp))
            RoundKey(menu = true, small = true)
            Spacer(Modifier.width(10.dp))
            val now = ui.current?.label
            Text(
                if (ui.release) tr("Suelta Vista + Menú y vuelve a mantenerlos", "Release View + Menu and hold them again")
                else if (now != null) tr("Control en $now · mantén Vista + Menú para ", "Controller in $now · hold View + Menu to ") +
                    (if (ui.target == PadMode.ANDROID) tr("volver a $target", "return to $target") else tr("cambiar a $target", "switch to $target"))
                else tr("Cambiando de modo…", "Switching mode…"),
                color = FG, fontSize = 15.sp
            )
        }
    }
}

/** Etiqueta de esquina: carga + "DS5" mientras cambia y ✓ + "DS5" al confirmarse. */
@Composable
private fun Banner(ui: GuideUi) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (lightUi) Color.White.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.55f))
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (ui.done) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = OK)
            else CircularProgressIndicator(Modifier.size(11.dp), color = FG.copy(alpha = 0.8f), strokeWidth = 1.5.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(ui.target?.label ?: "", color = FG.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Los dos botones que hay que mantener, dibujados como en el G9: Vista (dos cuadrados) y Menú (≡).
 * Android los recibe como Select y Start.
 */
@Composable
private fun ButtonsHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundKey(menu = false)
        Text("   +   ", color = MUTED, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        RoundKey(menu = true)
    }
}

@Composable
internal fun RoundKey(menu: Boolean, small: Boolean = false) {
    Box(
        Modifier.size(if (small) 28.dp else 64.dp).clip(CircleShape).background(if (lightUi) Color.White else Color(0xFF0B1120))
            .border(if (small) 1.dp else 2.dp, ACCENT, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(if (small) 13.dp else 30.dp)) {
            val c = if (lightUi) Color(0xFF14203A) else Color(0xFFD6DAE0)
            val sw = size.width * 0.09f
            if (menu) {
                for (f in listOf(0.22f, 0.5f, 0.78f)) {
                    drawLine(c, Offset(size.width * 0.12f, size.height * f), Offset(size.width * 0.88f, size.height * f), sw, StrokeCap.Round)
                }
            } else {
                val q = size.width * 0.58f
                drawRect(c, Offset(size.width * 0.08f, size.height * 0.08f), Size(q, q), style = Stroke(sw))
                drawRect(c, Offset(size.width * 0.34f, size.height * 0.34f), Size(q, q), style = Stroke(sw))
            }
        }
    }
}

/** Recorrido de modos: hechos (✓ verde), actual (celeste, latiendo) y pendientes (contorno). */
@Composable
private fun Stepper(path: List<PadMode>, current: PadMode?, done: Boolean) {
    val at = if (done) path.lastIndex else path.indexOf(current).coerceAtLeast(0)
    val pulse = rememberInfiniteTransition(label = "node")
    val glow by pulse.animateFloat(
        0.35f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "glow"
    )
    Row(verticalAlignment = Alignment.Top) {
        path.forEachIndexed { i, mode ->
            if (i > 0) {
                val lineColor by animateColorAsState(if (i <= at) OK else LINE, tween(400), label = "line")
                Box(
                    Modifier.padding(top = 21.dp).width(56.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(lineColor)
                )
            }
            val state = when {
                i < at || (done && i == at) -> 2   // hecho
                i == at -> 1                       // actual
                else -> 0                          // pendiente
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                val fill by animateColorAsState(
                    when (state) { 2 -> OK; 1 -> ACCENT; else -> Color.Transparent }, tween(400), label = "fill"
                )
                Box(
                    Modifier.size(46.dp)
                        .then(if (state == 1) Modifier.border(6.dp, ACCENT.copy(alpha = glow * 0.5f), CircleShape) else Modifier)
                        .padding(if (state == 1) 4.dp else 0.dp)
                        .clip(CircleShape).background(fill)
                        .border(2.dp, if (state == 0) MUTED else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (state == 2) Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = Color.White)
                    else Text("${i + 1}", color = if (state == 1) (if (lightUi) Color.White else Color(0xFF052238)) else MUTED, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    mode.label, fontSize = 14.sp, textAlign = TextAlign.Center,
                    color = if (state == 0) MUTED else FG,
                    fontWeight = if (state == 1) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
