package io.github.sudodiego.g9control

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** true en pantallas anchas (tablet en horizontal): la app usa riel lateral y dos columnas. */
val LocalWide = compositionLocalOf { false }

/**
 * false cuando la app no puede escribir en el control (no está conectado en modo Android): los
 * controles de las páginas de ajustes se ven atenuados y no responden.
 */
val LocalEditable = compositionLocalOf { true }

/** Avisa cuando el dedo está sobre un slider: mientras tanto no se guarda ni corre la cuenta atrás. */
val LocalSliderHeld = compositionLocalOf<(Boolean) -> Unit> { {} }

/** Páginas cuyos controles escriben en el control. */
private val EDIT_PAGES = setOf(Page.LIGHTS, Page.STICKS, Page.VIBRATION)

private data class NavItem(val page: Page, val es: String, val en: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    val label get() = tr(es, en)
}

private val NAV = listOf(
    // Tablet: sirve sin el G9 y con cualquier control.
    NavItem(Page.TABLET_HOME, "Inicio", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    NavItem(Page.BATTERY, "Batería", "Battery", Icons.Outlined.BatteryChargingFull, Icons.Filled.BatteryChargingFull),
    NavItem(Page.SCREEN, "Pantalla", "Screen", Icons.Outlined.Monitor, Icons.Filled.Monitor),
    NavItem(Page.GAMEPANEL, "Juego", "In game", Icons.Outlined.SportsEsports, Icons.Filled.SportsEsports),
    NavItem(Page.CONTROLS, "Controles", "Controls", Icons.Outlined.VideogameAsset, Icons.Filled.VideogameAsset),
    // G9.
    NavItem(Page.HOME, "Inicio", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    NavItem(Page.AUTO, "Por juego", "Per game", Icons.Outlined.Apps, Icons.Filled.Apps),
    NavItem(Page.LIGHTS, "Luces", "Lights", Icons.Outlined.Lightbulb, Icons.Filled.Lightbulb),
    NavItem(Page.STICKS, "Sticks", "Sticks", Icons.Outlined.Gamepad, Icons.Filled.Gamepad),
    NavItem(Page.VIBRATION, "Vibración", "Vibration", Icons.Outlined.Vibration, Icons.Filled.Vibration),
    NavItem(Page.TEST, "Botones", "Buttons", Icons.Outlined.RadioButtonChecked, Icons.Filled.RadioButtonChecked),
    NavItem(Page.BACK, "Traseros", "Paddles", Icons.Outlined.FlipToBack, Icons.Filled.FlipToBack),
    NavItem(Page.PROFILES, "Perfiles", "Profiles", Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark),
    // Las dos.
    NavItem(Page.DIAGNOSTICS, "Ajustes", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(s: AppState, a: MainActivity) {
    Box(Modifier.fillMaxSize()) { Backdrop() }
    BoxWithConstraints(
        Modifier.fillMaxSize().safeDrawingPadding()
    ) {
        val wide = maxWidth >= 720.dp
        // Foco bien visible para navegar con el control (por defecto Material lo marca muy suave).
        val focusRipple = RippleConfiguration(
            color = MaterialTheme.colorScheme.primary,
            rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.30f, hoveredAlpha = 0.08f, pressedAlpha = 0.14f)
        )
        CompositionLocalProvider(
            // Texto fuera de los paneles con el color del tema (si no, queda negro en modo oscuro).
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalWide provides wide, LocalSliderHeld provides { s.sliderHeld = it }, LocalCentered provides s.centered,
            LocalRippleConfiguration provides focusRipple
        ) {
            Column(Modifier.fillMaxSize()) {
                TabBar(s, a)
                // La franja de Vista + Menú, solo en el inicio del G9 (en las demás páginas basta el aviso de cada una).
                if (s.page == Page.HOME) ModeStrip(s)
                PageHost(s, a, Modifier.weight(1f))
                HintBar(s)
            }
            if (s.showSetup) SetupDialog(s, a) { s.showSetup = false; Prefs.setSetupDone(a) }
        }
    }
}

/** Pestañas horizontales, como en una consola: LB/RB a los lados para cambiar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabBar(s: AppState, a: MainActivity) {
    val c = MaterialTheme.colorScheme
    // Arriba: a la izquierda Tablet/G9 (LT RT), al centro las pestañas en una píldora (LB RB a sus lados) y a la
    // derecha el estado del G9.
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PadBadge("LT")
            Spacer(Modifier.width(6.dp))
            SectionSwitch(s)
            Spacer(Modifier.width(6.dp))
            PadBadge("RT")
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.weight(6f, fill = false).clip(CircleShape).background(c.surfaceContainerHigh.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            PadBadge("LB")
            // Si las pestañas no caben, la elegida se desplaza a la vista.
            val tabScroll = rememberScrollState()
            val tabX = remember { mutableStateMapOf<Page, Pair<Int, Int>>() }
            LaunchedEffect(s.page, tabX[s.page]) {
                val (x, w) = tabX[s.page] ?: return@LaunchedEffect
                val view = tabScroll.viewportSize
                if (x < tabScroll.value || x + w > tabScroll.value + view)
                    tabScroll.animateScrollTo((x + w / 2 - view / 2).coerceAtLeast(0))
            }
            val tabFocus = remember { Page.entries.associateWith { FocusRequester() } }
            var tabFocused by remember { mutableStateOf(false) }
            LaunchedEffect(s.page) { if (tabFocused) runCatching { tabFocus.getValue(s.page).requestFocus() } }
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(tabScroll).padding(horizontal = 6.dp)
                    .onFocusChanged { tabFocused = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                NAV.filter { it.page in s.section.pages }.forEach { item ->
                    // Ajustes (común a las dos secciones) después de un divisor.
                    if (item.page == Page.DIAGNOSTICS)
                        VerticalDivider(Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp).height(24.dp), color = c.outlineVariant)
                    val sel = s.page == item.page
                    // El foco del control se marca con un contorno, no con relleno: con relleno parecía otra pestaña
                    // elegida (se veían dos resaltadas a la vez).
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Surface(
                        onClick = { s.page = item.page },
                        shape = CircleShape,
                        color = if (sel) c.primaryContainer else Color.Transparent,
                        border = if (focused) BorderStroke(2.dp, c.primary) else null,
                        interactionSource = source,
                        modifier = Modifier.focusRequester(tabFocus.getValue(item.page))
                            .onGloballyPositioned { tabX[item.page] = it.positionInParent().x.toInt() to it.size.width }
                    ) {
                        // Como un carrusel: la elegida con su nombre, las demás solo el ícono (así caben todas).
                        Row(Modifier.animateContentSize().padding(horizontal = if (sel) 14.dp else 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (sel) item.selectedIcon else item.icon, item.label, Modifier.size(22.dp),
                                tint = if (sel) c.onPrimaryContainer else c.onSurfaceVariant
                            )
                            if (sel) {
                                Spacer(Modifier.width(8.dp))
                                Text(item.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onPrimaryContainer)
                            }
                        }
                    }
                    }
                }
            }
            PadBadge("RB")
            }
            Spacer(Modifier.weight(1f))
            // Conexión del G9: solo en su sección (tocarla vuelve a leer el control).
            if (s.section == Section.G9) ConnectionPill(s) { if (!s.busy) a.readAll() }
        }
    }
}

/**
 * Las dos partes de la app: Tablet (para cualquiera, con cualquier control) y G9. Con el control se pasa de una a
 * otra siguiendo con LB/RB más allá de la última pestaña.
 */
@Composable
private fun SectionSwitch(s: AppState) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(CircleShape).background(c.surfaceContainerHighest).padding(3.dp)) {
        listOf(Section.TABLET to Pair(tr("Tablet", "Tablet"), Icons.Outlined.TabletAndroid),
            Section.G9 to Pair("G9", Icons.Outlined.SportsEsports)).forEach { (sec, info) ->
            val sel = s.section == sec
            Row(
                Modifier.clip(CircleShape).background(if (sel) c.primary else Color.Transparent)
                    .clickable { s.goTo(sec) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(info.second, null, Modifier.size(18.dp), tint = if (sel) c.onPrimary else c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(info.first, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (sel) c.onPrimary else c.onSurfaceVariant)
                // El G9 conectado: un punto verde (aunque se esté en la sección Tablet).
                if (sec == Section.G9 && s.padMode != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF34C759)))
                }
            }
        }
    }
}

/** Botón del control dibujado como etiqueta (LB, A, B…). */
@Composable
fun PadBadge(label: String, round: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.clip(if (round) CircleShape else RoundedCornerShape(6.dp)).background(c.onSurface.copy(alpha = 0.85f))
            .then(if (round) Modifier.size(22.dp) else Modifier.padding(horizontal = 7.dp, vertical = 2.dp)),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.surface) }
}

/** Botón B con un aro que se llena mientras se mantiene (1 s o 3 s); al soltar vuelve a cero. */
@Composable
private fun HoldBadge(hold: Pair<Long, Long>?) {
    val c = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    LaunchedEffect(hold) {
        if (hold == null) progress.animateTo(0f, tween(200))
        else {
            val (since, ms) = hold
            val start = ((android.os.SystemClock.uptimeMillis() - since).toFloat() / ms).coerceIn(0f, 1f)
            progress.snapTo(start)
            progress.animateTo(1f, tween(((1f - start) * ms).toInt(), easing = LinearEasing))
        }
    }
    val ring = c.primary
    Box(
        Modifier.size(30.dp).drawBehind {
            val w = 3.dp.toPx()
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(ring.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            if (progress.value > 0f) drawArc(
                ring, -90f, 360f * progress.value, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round)
            )
        },
        contentAlignment = Alignment.Center
    ) { PadBadge("B", round = true) }
}

/**
 * Barra inferior: a la izquierda los avisos (se borran solos salvo los problemas) y a la derecha cómo
 * se navega con el control, solo si hay un control conectado.
 */
@Composable
private fun HintBar(s: AppState) {
    val c = MaterialTheme.colorScheme
    val problem = s.statusError
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(s.status) {
        shown = true
        if (!problem) { kotlinx.coroutines.delay(4000); shown = false }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Estado de guardado de la página (siempre en el mismo lugar).
        val save: (@Composable () -> Unit)? = when (s.page) {
            Page.LIGHTS -> { { SaveStatus(s.lightsDirty, s.appliedLeft != null && s.connected, s.lightsChangedAt,
                MainActivity.LIGHTS_SAVE_MS, held = s.sliderHeld, quietMs = MainActivity.LIGHTS_QUIET_MS, modifier = Modifier) } }
            Page.STICKS -> { { SaveStatus(s.sticksDirty, s.appliedDeadZones != null && s.connected, modifier = Modifier) } }
            Page.VIBRATION -> { { SaveStatus(s.vibrationDirty, s.appliedVibration != null && s.connected, modifier = Modifier) } }
            else -> null
        }
        if (save != null) { save(); Spacer(Modifier.width(20.dp)) }
        Text(
            if (shown) s.status else "", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (problem) c.error else c.onSurfaceVariant
        )
        if (s.padMode != null || s.connected) {
            val hints = if (s.capturing) listOf("B" to tr("Mantén 1 s para salir", "Hold 1 s to exit"))
            else if (s.editingMacro != null) listOf("A" to tr("Elegir", "Select"), "B" to tr("Cerrar macro", "Close macro"), "LB RB" to tr("Pestañas", "Tabs"))
            else listOf("A" to tr("Elegir", "Select"), "LB RB" to tr("Pestañas", "Tabs"), "LT RT" to tr("Tablet / G9", "Tablet / G9"),
                "B" to tr("Mantén 3 s para salir", "Hold 3 s to exit"))
            hints.forEach { (k, v) ->
                Spacer(Modifier.width(18.dp))
                if (k == "B") HoldBadge(s.bHold) else if (k.length == 1) PadBadge(k, round = true) else PadBadge(k)
                Spacer(Modifier.width(6.dp))
                Text(v, fontSize = 13.sp, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PageHost(s: AppState, a: MainActivity, modifier: Modifier) {
    // Al cambiar de pestaña se termina la prueba de botones.
    LaunchedEffect(s.page) { if (s.page != Page.TEST) s.testMode = false }
    AnimatedContent(
        targetState = s.page,
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "page"
    ) { page ->
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val editable = page !in EDIT_PAGES || s.connected
            CompositionLocalProvider(LocalEditable provides editable) {
                Column(
                    Modifier.alpha(if (editable) 1f else 0.5f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (page) {
                        Page.HOME -> HomePage(s, a)
                        Page.LIGHTS -> LightsPage(s, a)
                        Page.STICKS -> SticksPage(s, a)
                        Page.VIBRATION -> VibrationPage(s, a)
                        Page.PROFILES -> ProfilesPage(s, a)
                        Page.AUTO -> AutoPage(s, a)
                        Page.TEST -> ControlsPage(s, a)
                        Page.BACK -> BackPage(s, a)
                        Page.TABLET_HOME -> TabletHomePage(s, a)
                        Page.BATTERY -> BatteryPage(s, a)
                        Page.SCREEN -> ScreenPage(s, a)
                        Page.GAMEPANEL -> GamePanelPage(s, a)
                        Page.CONTROLS -> ShortcutsPage(s, a)
                        Page.DIAGNOSTICS -> DiagnosticsPage(s, a)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionPill(s: AppState, onConnect: () -> Unit) {
    val (text, color) = when {
        s.connected -> tr("Conectado", "Connected") to Color(0xFF3DDC84)
        s.padMode == PadMode.XINPUT -> tr("En X-Input", "In X-Input") to MaterialTheme.colorScheme.tertiary
        s.padMode == PadMode.DS5 -> tr("En DS5", "In DS5") to MaterialTheme.colorScheme.tertiary
        else -> tr("Sin conexión", "Not connected") to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onConnect)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Componentes compartidos
// ---------------------------------------------------------------------------------------------

/** Tarjeta base de todas las páginas. */
@Composable
fun Panel(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Vidrio sobre el fondo: translúcida con un borde que brilla arriba y se apaga abajo.
    val glass = ThemeState.glass
    val dark = isDarkTheme()
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (glass) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (dark) 0.58f else 0.66f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (glass) BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.7f), Color.White.copy(alpha = if (dark) 0.03f else 0.2f)))) else null,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (subtitle != null) Text(
                            subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** Diseño elegido en Ajustes: dibujo del control al centro o en columna. */
val LocalCentered = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Página con el dibujo del control: en columnas (dibujo a la izquierda, [side] a la derecha) o, con
 * "dibujo al centro", el dibujo arriba y centrado y [side] debajo en dos columnas.
 */
@Composable
fun DrawingPane(
    drawing: @Composable ColumnScope.() -> Unit, side: @Composable ColumnScope.() -> Unit
) {
    // Mismo peso en todas las páginas: el dibujo tiene el mismo tamaño y lugar en Inicio, Luces, Controles y Traseros.
    val drawingWeight = 1.4f
    val wide = LocalWide.current
    if (wide && !LocalCentered.current) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(drawingWeight), verticalArrangement = Arrangement.spacedBy(16.dp), content = drawing)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = side)
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth(if (wide) 0.72f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = drawing)
            Column(Modifier.fillMaxWidth(if (wide) 0.72f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = side)
        }
    }
}

/** Dos columnas en pantallas anchas; una debajo de la otra en angostas. */
@Composable
fun TwoPane(first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    if (LocalWide.current) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = first)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = second)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { first(); second() }
    }
}

/**
 * Estado del guardado automático: los cambios se escriben solos en el control poco después
 * de dejar de mover un control de la pantalla. [known] = ya se leyó la configuración del control.
 */
@Composable
fun SaveStatus(
    dirty: Boolean, known: Boolean, changedAt: Long = 0L, waitMs: Long = 0L,
    held: Boolean = false, quietMs: Long = 0L, modifier: Modifier = Modifier.fillMaxWidth()
) {
    val c = MaterialTheme.colorScheme
    // Cuenta atrás hasta el guardado (si la página la usa). No corre con el dedo sobre un slider ni
    // durante [quietMs] después del último cambio (por ejemplo, tocando + varias veces).
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(changedAt, dirty, held) {
        progress.snapTo(0f)
        if (!dirty || waitMs <= 0 || changedAt <= 0) { pending = false; return@LaunchedEffect }
        pending = true
        if (held) return@LaunchedEffect
        val elapsed = android.os.SystemClock.uptimeMillis() - changedAt
        if (elapsed < quietMs) kotlinx.coroutines.delay(quietMs - elapsed)
        pending = false
        val start = ((elapsed - quietMs).toFloat() / waitMs).coerceIn(0f, 1f)
        progress.snapTo(start)
        progress.animateTo(1f, androidx.compose.animation.core.tween(((1f - start) * waitMs).toInt(),
            easing = androidx.compose.animation.core.LinearEasing))
    }
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            !known -> {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(tr("Conecta el control para guardar los cambios.", "Connect the controller to save changes."), fontSize = 13.sp, color = c.onSurfaceVariant)
            }
            dirty && waitMs > 0 && pending -> {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(tr("Cambios sin guardar", "Unsaved changes"), fontSize = 13.sp, color = c.onSurfaceVariant)
            }
            dirty && waitMs > 0 && progress.value < 1f -> {
                Text(tr("Guardando en ${waitMs / 1000} s…", "Saving in ${waitMs / 1000} s…"), fontSize = 13.sp, color = c.primary)
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { progress.value }, Modifier.width(140.dp).height(6.dp),
                    trackColor = c.surfaceContainerHighest, drawStopIndicator = {}
                )
            }
            dirty -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(tr("Guardando…", "Saving…"), fontSize = 13.sp, color = c.primary)
            }
            else -> {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = Color(0xFF3DDC84))
                Spacer(Modifier.width(8.dp))
                Text(tr("Guardado en el control", "Saved to the controller"), fontSize = 13.sp, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Hint(text: String, icon: ImageVector = Icons.Outlined.Info) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Fila con etiqueta a la izquierda y valor a la derecha, sobre un slider. */
@Composable
fun SliderRow(
    label: String, value: Int, range: IntRange, valueText: String = "$value",
    onChange: (Int) -> Unit
) {
    // Mismo estilo que los sliders de color, con − y + a los lados para ajustar de a 1.
    val c = MaterialTheme.colorScheme
    val span = (range.last - range.first).coerceAtLeast(1)
    val editable = LocalEditable.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Text(valueText, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - 1).coerceAtLeast(range.first)) }, enabled = editable && value > range.first) {
                Icon(Icons.Filled.Remove, tr("Bajar 1", "Down 1"))
            }
            Box(Modifier.weight(1f)) {
                GradientTrack(
                    (value - range.first).toFloat() / span,
                    Brush.horizontalGradient(listOf(c.primary.copy(alpha = 0.18f), c.primary)), c.primary,
                    keyStep = 1f / span
                ) { f -> onChange((range.first + Math.round(f * span)).coerceIn(range.first, range.last)) }
            }
            IconButton(onClick = { onChange((value + 1).coerceAtMost(range.last)) }, enabled = editable && value < range.last) {
                Icon(Icons.Filled.Add, tr("Subir 1", "Up 1"))
            }
        }
    }
}

/** Slider con fondo degradado (para tono, saturación y luminosidad). [value] va de 0 a 1. */
@Composable
fun GradientSlider(
    label: String, valueText: String, value: Float, brush: Brush, thumb: Color,
    onChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Text(valueText, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        GradientTrack(value, brush, thumb, onChange = onChange)
    }
}

/** Barra del slider (sin etiqueta): degradado, perilla redonda, toque o arrastre. [value] va de 0 a 1. */
@Composable
fun GradientTrack(value: Float, brush: Brush, thumb: Color, keyStep: Float = 0.02f, onChange: (Float) -> Unit) {
    val onChangeNow by rememberUpdatedState(onChange)
    val border = MaterialTheme.colorScheme.onSurface
    val editable = LocalEditable.current
    val held by rememberUpdatedState(LocalSliderHeld.current)
    val valueNow by rememberUpdatedState(value)
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    run {
        Canvas(
            Modifier.fillMaxWidth().height(32.dp)
                // Con el control: el slider recibe el foco y se ajusta con izquierda/derecha.
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (!editable || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { onChangeNow((valueNow - keyStep).coerceIn(0f, 1f)); true }
                        Key.DirectionRight -> { onChangeNow((valueNow + keyStep).coerceIn(0f, 1f)); true }
                        else -> false
                    }
                }
                .focusable(editable)
                .pointerInput(editable) {
                    if (!editable) return@pointerInput
                    val r = 16.dp.toPx()
                    detectTapGestures { onChangeNow(((it.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)) }
                }
                .pointerInput(editable) {
                    if (!editable) return@pointerInput
                    val r = 16.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { held(true) }, onDragEnd = { held(false) }, onDragCancel = { held(false) }
                    ) { change, _ ->
                        change.consume()
                        onChangeNow(((change.position.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f))
                    }
                }
        ) {
            val r = 16.dp.toPx()
            val h = 12.dp.toPx()
            drawRoundRect(
                brush, topLeft = Offset(r, center.y - h / 2), size = Size(size.width - 2 * r, h),
                cornerRadius = CornerRadius(h / 2)
            )
            val x = r + value.coerceIn(0f, 1f) * (size.width - 2 * r)
            drawCircle(Color.Black.copy(alpha = 0.25f), r - 1.dp.toPx(), Offset(x, center.y + 1.dp.toPx()))
            if (focused) drawCircle(ring.copy(alpha = 0.35f), r + 4.dp.toPx(), Offset(x, center.y))
            drawCircle(thumb, r - 3.dp.toPx(), Offset(x, center.y))
            drawCircle(border, r - 3.dp.toPx(), Offset(x, center.y), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/** Fila de opciones tipo "pastilla". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = selected == v, onClick = { onSelect(v) }, label = { Text(label) },
                enabled = LocalEditable.current,
                leadingIcon = if (selected == v) {
                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .clickable(enabled = LocalEditable.current) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = LocalEditable.current)
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary, size: Dp = 0.dp) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
        Text(
            text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp).then(if (size > 0.dp) Modifier.width(size) else Modifier),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color
        )
    }
}

/**
 * Franja fija bajo las pestañas cuando el control no está en Android. Se comporta como la guía: barra
 * que se llena al mantener Vista + Menú, "suelta y vuelve a mantener" tras cada cambio, "¡Bien! Una vez
 * más" y "Listo" en verde al llegar. Lleva a Android o al modo elegido en Inicio o en ajustes rápidos.
 */
@Composable
fun ModeStrip(s: AppState) {
    val mode = s.padMode
    val target = if (s.modeTarget != mode) s.modeTarget else PadMode.ANDROID
    // "Listo" en verde un momento al llegar al modo pedido.
    var done by remember { mutableStateOf<PadMode?>(null) }
    var last by remember { mutableStateOf(mode) }
    // Cambios que faltaban al empezar (para "¡Bien! Una vez más").
    var startSteps by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) { startSteps = mode?.takeIf { it != target }?.let { ModeGuide.steps(it, target) } ?: 0 }
    LaunchedEffect(mode) {
        val was = last
        last = mode
        if (mode == null || mode == target) startSteps = 0
        else if (startSteps == 0) startSteps = ModeGuide.steps(mode, target)
        if (mode != null && was != null && mode != was && mode == s.modeTarget) {
            done = mode
            s.modeTarget = PadMode.ANDROID
        }
    }
    LaunchedEffect(done) { if (done != null) { kotlinx.coroutines.delay(1600); done = null } }

    AnimatedVisibility(
        visible = done != null || (!s.connected && mode != null && mode != PadMode.ANDROID),
        enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()
    ) {
        val progress = remember { androidx.compose.animation.core.Animatable(0f) }
        val since = s.hold.holdSince
        LaunchedEffect(since) {
            if (since != null) {
                val start = ((android.os.SystemClock.uptimeMillis() - since).toFloat() / HOLD_MS).coerceIn(0f, 1f)
                progress.snapTo(start)
                progress.animateTo(
                    1f, androidx.compose.animation.core.tween(((1f - start) * HOLD_MS).toInt(), easing = androidx.compose.animation.core.LinearEasing)
                )
            } else progress.animateTo(0f, androidx.compose.animation.core.tween(250))
        }
        val c = MaterialTheme.colorScheme
        val ok = Color(0xFF2EB872)
        val d = done
        val fill = c.primary.copy(alpha = 0.22f)
        Row(
            Modifier.fillMaxWidth().background(if (d != null) ok.copy(alpha = 0.22f) else c.primaryContainer.copy(alpha = 0.55f))
                .drawBehind { if (d == null) drawRect(fill, size = size.copy(width = size.width * progress.value)) }
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (d != null) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(ok), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Check, null, Modifier.size(18.dp), tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(tr("Listo: el control está en ${d.label}", "Done: the controller is in ${d.label}"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                return@Row
            }
            val m = mode ?: return@Row
            val left = ModeGuide.steps(m, target)
            RoundKey(menu = false, small = true)
            Spacer(Modifier.width(4.dp))
            RoundKey(menu = true, small = true)
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        s.hold.release -> tr("Suelta Vista + Menú y vuelve a mantenerlos", "Release View + Menu and hold them again")
                        left == 1 && startSteps > 1 -> tr("¡Bien! Una vez más", "Nice! One more time")
                        else -> tr("Mantén Vista + Menú unos 2 segundos", "Hold View + Menu for about 2 seconds") +
                            (if (left > 1) tr(" ($left veces)", " ($left times)") else "")
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onPrimaryContainer
                )
                Text(
                    if (target == PadMode.ANDROID) tr("El control está en ${m.label}. Para configurarlo tiene que estar en Android.",
                        "The controller is in ${m.label}. It must be in Android mode to configure it.")
                    else tr("Para pasar de ${m.label} a ${target.label}.", "To switch from ${m.label} to ${target.label}."),
                    fontSize = 13.sp, color = c.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            if (target != PadMode.ANDROID) {
                Spacer(Modifier.width(14.dp))
                TextButton(onClick = { s.modeTarget = PadMode.ANDROID }) { Text(tr("Cancelar", "Cancel")) }
            }
        }
    }
}
