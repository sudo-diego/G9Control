package io.github.sudodiego.g9control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomePage(s: AppState, a: MainActivity) {
    DrawingPane(
        drawing = {
            Panel {
                ControllerPreview(s, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Legion Gaming Controller G9", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                s.connected -> tr("Conectado por USB · modo Android", "Connected over USB · Android mode")
                                s.padMode != null -> tr("Detectado en modo ${s.padMode!!.label}", "Detected in ${s.padMode!!.label} mode")
                                else -> tr("No detectado", "Not detected")
                            },
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // En X-Input/DS5 no se puede conectar: el aviso de arriba explica cómo volver a Android.
                    if (!s.connected && (s.padMode == null || s.padMode == PadMode.ANDROID))
                        Button(onClick = { a.readAll() }) { Text(tr("Conectar", "Connect")) }
                }
            }
        },
        // Los perfiles viven aquí (antes tenían su propia pestaña).
        side = { ModePanel(s, a); ProfilesPage(s, a); ShortcutsPanel() }
    )
}

@Composable
private fun ModePanel(s: AppState, a: MainActivity) = Panel(tr("Modo del control", "Controller mode")) {
    val current = if (s.connected) PadMode.ANDROID else s.padMode
    // Fuera de Android solo se cambia con Vista + Menú: tocar un modo hace que la franja de arriba guíe hasta él.
    val guiding = current != null && current != PadMode.ANDROID
    val target = if (s.modeTarget != current) s.modeTarget else PadMode.ANDROID
    fun tap(m: PadMode): (() -> Unit)? = when {
        m == current -> null
        s.connected && !s.busy -> m.target?.let { t -> { a.switchMode(t) } }
        guiding -> { { s.modeTarget = m } }
        else -> null
    }
    fun desc(m: PadMode) = if (guiding && m == target) tr("Mantén Vista + Menú", "Hold View + Menu") else ""
    // Mismo alto para los tres; el punto de color es el del indicador del control en ese modo (manual).
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((m, led) in listOf(PadMode.ANDROID to Color(0xFF1E3A8A), PadMode.XINPUT to Color(0xFF22C55E), PadMode.DS5 to Color.White)) {
            ModeTile(m.label, desc(m), led, current == m, Modifier.weight(1f).fillMaxHeight(), onClick = tap(m))
        }
    }
}

@Composable
private fun ModeTile(
    name: String, desc: String, led: Color, active: Boolean, modifier: Modifier, onClick: (() -> Unit)? = null
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (active) c.primaryContainer else c.surfaceContainerHigh)
            .border(1.5.dp, if (active) c.primary else Color.Transparent, MaterialTheme.shapes.medium)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(led).border(1.dp, c.outline, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(name, fontWeight = FontWeight.SemiBold, color = if (active) c.onPrimaryContainer else c.onSurface)
        }
        if (desc.isNotBlank()) Text(
            desc, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
            color = if (active) c.onPrimaryContainer else c.onSurfaceVariant
        )
    }
}

/** Perfiles a un toque: el que está en uso queda marcado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickProfiles(s: AppState, a: MainActivity) {
    if (s.profiles.isEmpty()) return
    Panel(tr("Perfiles", "Profiles")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.profiles.forEach { p ->
                val active = s.configKnown && p.matches(s)
                FilterChip(
                    selected = active, onClick = { if (!active) a.applyProfile(p) }, enabled = s.connected && !s.busy,
                    label = { Text(p.name) },
                    leadingIcon = if (active) { { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) } } else null
                )
            }
        }
    }
}

/**
 * El servicio de accesibilidad está apagado y hay algo que lo usa (cambio por juego, panel de juego, botón Legion o
 * volumen invertido): por ejemplo, tras reiniciar o actualizar. El aviso dice lo que deja de funcionar.
 */
@Composable
fun ServiceWarning(s: AppState, a: MainActivity) {
    var on by remember { mutableStateOf(AutoRules.warnService(a)) }
    val used = (s.autoEnabled && s.autoRules.isNotEmpty()) || AutoRules.gamePanel(a) || AutoRules.legionKeys(a) || AutoRules.swapVolume(a) ||
        AutoRules.smartBypass(a) || Monitor.all169(a) || Monitor.screen(a) != Monitor.Screen.NORMAL
    if (!on || s.autoServiceOn || !used) return
    WarningStrip(
        tr("El servicio de accesibilidad está apagado: el panel y los atajos no funcionan.",
            "The accessibility service is off: the panel and shortcuts don't work."),
        tr("Activar", "Turn on"), { AutoRules.openServiceSettings(a) },
        onDismiss = { on = false; AutoRules.setWarnService(a, false) }
    )
}

/**
 * Shizuku no está listo y hay algo elegido que lo necesita ([Shell.wanted]). Pasa tras
 * cada reinicio de la tablet: Shizuku hay que iniciarlo a mano.
 */
@Composable
fun ShizukuWarning(a: MainActivity) {
    var on by remember { mutableStateOf(AutoRules.warnShizuku(a)) }
    LaunchedEffect(Unit) { Shell.init(a); Shell.refresh() }
    val st = Shell.state
    if (!on || st == Shell.State.READY || !Shell.wanted(a)) return
    val launch = remember { Shell.launchIntent(a) } ?: return
    if (st == Shell.State.NO_PERMISSION)
        WarningStrip(tr("TBF Companion no tiene permiso de Shizuku.", "TBF Companion has no Shizuku permission."),
            tr("Dar permiso", "Grant"), { Shell.requestPermission() }, onDismiss = { on = false; AutoRules.setWarnShizuku(a, false) })
    else
        WarningStrip(tr("Shizuku no está iniciado.",
            "Shizuku isn't started."),
            tr("Abrir Shizuku", "Open Shizuku"), { runCatching { a.startActivity(launch) } }, onDismiss = { on = false; AutoRules.setWarnShizuku(a, false) })
}

/** Franja de aviso de Inicio: el problema, el botón que lo arregla y "No avisar" (se vuelve a activar en Ajustes). */
@Composable
private fun WarningStrip(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // Discreto: una línea con el ícono en rojo, no un bloque rojo que tapa la página.
    Surface(color = c.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, Modifier.size(20.dp), tint = c.error)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onDismiss) { Text(tr("No avisar", "Don't warn"), color = c.onSurfaceVariant) }
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun ColorDot(l: Light) {
    val off = l.effect == 255 || l.lum == 0
    Box(
        Modifier.size(16.dp).clip(CircleShape)
            .then(
                when {
                    off -> Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    l.effect == 4 -> Modifier.background(androidx.compose.ui.graphics.Brush.sweepGradient(RAINBOW))
                    else -> Modifier.background(lightColor(l))
                }
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}

/** Combinaciones que el control hace por sí mismo (según el manual del G9). */
/** Combinación del control: botones (cada grupo separado por "+"), si hay que mantener, y qué hace. */
private data class Combo(val keys: List<String>, val hold: String?, val what: String)

private fun shortcuts() = listOf(
    Combo(listOf(tr("Vista", "View"), tr("Menú", "Menu")), "2 s", tr("Cambiar de modo: Android → X-Input → DS5", "Switch mode: Android → X-Input → DS5")),
    Combo(listOf(tr("Vista", "View"), tr("Menú", "Menu"), "Home"), null, tr("Calibrar sticks y gatillos (A para salir)", "Calibrate sticks and triggers (A to exit)")),
    Combo(listOf("M", "L4/R4/L5/R5"), null, tr("Asignar un trasero desde el control", "Assign a back button from the controller")),
    Combo(listOf("M", "LT/RT"), "2 s", tr("Gatillo rápido", "Hair trigger")),
    Combo(listOf("M", "A"), "2 s", tr("Intercambiar A↔B y X↔Y", "Swap A↔B and X↔Y")),
    Combo(listOf("M", tr("botón", "button")), null, tr("Turbo en ese botón (M + Home 2 s borra todos)", "Turbo on that button (M + Home 2 s clears all)")),
    Combo(listOf("M", tr("Vista/Menú", "View/Menu")), null, tr("Bajar o subir la vibración", "Lower or raise vibration")),
    Combo(listOf("M", tr("Stick izq.", "Left stick")), null, tr("Efecto, brillo y color de las tiras", "Light strip effect, brightness and color")),
    Combo(listOf("M ×2"), null, tr("Captura de pantalla (en DS5, clic del touchpad)", "Screenshot (in DS5, touchpad click)")),
    Combo(listOf("Home ×2"), null, tr("Ir a la pantalla de inicio", "Go to the home screen")),
    Combo(listOf("Home", "↑/↓"), null, tr("Volumen (solo en Android)", "Volume (Android mode only)")),
)

/** Combinaciones que funcionan sin la app: botón "Ver" que abre una ventana con tarjetas. */
@Composable
private fun ShortcutsPanel() {
    var open by rememberSaveable { mutableStateOf(false) }
    Panel(
        tr("Combinaciones del control", "Controller shortcuts"), tr("Funcionan sin la app.", "They work without the app."),
        trailing = { FilledTonalButton(onClick = { open = true }) { Text(tr("Ver", "View")) } }
    ) {}
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Combinaciones del control", "Controller shortcuts"), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { open = false }) { Text(tr("Cerrar", "Close")) }
                }
                shortcuts().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { ComboCard(it, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComboCard(c: Combo, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                c.keys.forEachIndexed { i, group ->
                    if (i > 0) Text("  +  ", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    // Varias opciones en un grupo ("LT/RT"): se dibujan juntas.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.split("/").forEach { k -> PadBadge(k, round = k.length == 1) }
                    }
                }
                c.hold?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(tr("mantén $it", "hold $it"), fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.Medium)
                }
            }
            Text(c.what, fontSize = 14.sp)
        }
    }
}
