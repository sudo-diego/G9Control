package io.github.sudodiego.g9control

import android.view.InputDevice
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inicio de la sección Tablet: para cualquiera con la Y700, tenga o no el G9. Arriba los avisos y cómo está la tablet
 * en mosaicos grandes; abajo lo que más se cambia, también en mosaicos (como el panel de juego). A la derecha los
 * controles conectados y los atajos.
 */
@Composable
fun TabletHomePage(s: AppState, a: MainActivity) {
    TwoPane(
        first = {
            ServiceWarning(s, a)
            ShizukuWarning(a)
            TabletNowTiles(a)
        },
        second = {
            QuickTiles(s, a)
            MemoryPanel(s, a)
            ControllersPanel(s)
            ShortcutsHelp(s, a)
        }
    )
}

/** La tablet ahora, en mosaicos: batería (con el tiempo de carga), temperatura y Hz. Se actualiza cada pocos segundos. */
@Composable
private fun TabletNowTiles(a: MainActivity) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val b = remember(tick) { batteryNow(a) }
    val hz = remember(tick) { RefreshRate.current(a) }
    val monitor = remember(tick) { Monitor.current(a) }
    val left = remember(tick) { if (b?.charging == true) ZuiBattery.chargeTimeLeft(a) else null }
    // Grilla de 2 × 2: batería y temperatura arriba, Hz y CPU abajo (en una fila no cabían).
    val cpu = remember(tick) { cpuMhz() }
    val tile = Modifier.fillMaxHeight()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            b?.let {
                val state = when {
                    it.plugged && !it.charging -> if (ZuiBattery.bypassNow == true && Shell.state == Shell.State.READY) "Bypass" else tr("Sin cargar", "Not charging")
                    it.charging -> left?.let { ms -> "⚡ " + ZuiBattery.formatTime(ms) } ?: tr("Cargando", "Charging")
                    else -> tr("Batería", "Battery")
                }
                // Debajo: lo que consume y cuánto dura, o cargando cuánto falta (hasta el límite, si hay) y lo que entra.
                val w = remember(tick) { ZuiBattery.watts(a) }
                val extra = when {
                    it.plugged && !it.charging -> null
                    it.charging -> listOfNotNull(left?.let { ms -> ZuiBattery.formatTime(ms) + ZuiBattery.chargeTarget(a).let { t -> if (t < 100) tr(" a $t %", " to $t %") else "" } },
                        w?.let { x -> "+%.1f W".format(x) }).joinToString(" · ")
                    else -> listOfNotNull(w?.let { x -> "%.1f W".format(x) }, remember(tick) { ZuiBattery.dischargeTimeLeft(a) }?.let { ms -> "~" + ZuiBattery.formatTime(ms) }).joinToString(" · ")
                }?.takeIf { s -> s.isNotEmpty() }
                StatTile("${it.level} %", if (it.charging) tr("Cargando", "Charging") else state, tile.weight(1f), big = true, extra = extra)
                StatTile("${"%.0f".format(it.tempC)} °C", tr("Temperatura", "Temperature"), tile.weight(1f), big = true)
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(hz?.let { "$it Hz" } ?: "—", monitor?.name ?: tr("Pantalla", "Screen"), tile.weight(1f))
            StatTile(cpu?.let { if (it >= 1000) "%.1f GHz".format(it / 1000f) else "$it MHz" } ?: "—", "CPU", tile.weight(1f))
        }
    }
}
/** La frecuencia más alta de la CPU ahora (MHz), de los núcleos que se pueden leer; null si ninguno. */
private fun cpuMhz(): Int? = (0..9).mapNotNull { i ->
    runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 }.getOrNull()
}.maxOrNull()

/** Un dato grande con su etiqueta debajo, en una tarjeta de vidrio. */
@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, big: Boolean = false, extra: String? = null) {
    Panel(modifier = modifier) {
        // Una sola línea, sin cortar: el valor (y su unidad) no se parten aunque el mosaico sea angosto.
        Text(value, fontSize = if (big) 38.sp else 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        extra?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1) }
    }
}

/** Lo que más se cambia, a un toque y en mosaicos: bypass, monitor de rendimiento y pantalla 16:9. */
@Composable
private fun QuickTiles(s: AppState, a: MainActivity) {
    val service = s.autoServiceOn
    val scope = rememberCoroutineScope()
    val ready = Shell.state == Shell.State.READY
    var bypass by remember { mutableStateOf(ZuiBattery.bypassNow == true) }
    var hud by remember { mutableStateOf(AutoRules.perfHud(a)) }
    var wide by remember { mutableStateOf(Aspect.is169(a)) }
    LaunchedEffect(ready) { if (ready) ZuiBattery.readBypass()?.let { bypass = it } }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Sin Shizuku el bypass no se puede cambiar: no sale (el aviso de arriba lo dice).
        if (ready) QuickTile(Icons.Outlined.Power, "Bypass", if (bypass) tr("Encendido", "On") else tr("Apagado", "Off"), bypass,
            Modifier.weight(1f).fillMaxHeight()) { scope.launch { if (ZuiBattery.setBypass(!bypass)) bypass = !bypass } }
        // El monitor lo dibuja el servicio de accesibilidad: sin él no se puede encender.
        QuickTile(Icons.Outlined.QueryStats, tr("Monitor", "Monitor"), if (!service) tr("Necesita accesibilidad", "Needs accessibility") else if (hud) tr("Encendido", "On") else tr("Apagado", "Off"),
            hud && service, Modifier.weight(1f).fillMaxHeight(), enabled = service) { hud = !hud; AutoRules.setPerfHud(a, hud) }
        if (Aspect.available(a)) QuickTile(Icons.Outlined.AspectRatio, "16:9", if (wide) tr("Encendido", "On") else tr("Apagado", "Off"), wide,
            Modifier.weight(1f).fillMaxHeight()) {
            scope.launch {
                Aspect.toggle(a)
                delay(500)
                wide = Aspect.is169(a)
            }
        }
    }
}

/** Mosaico que se enciende y apaga, como los del panel de juego: encendido en celeste. */
@Composable
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, state: String, on: Boolean, modifier: Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.alpha(if (enabled) 1f else 0.5f), shape = MaterialTheme.shapes.large,
        color = if (on) c.secondary else c.surfaceContainer.copy(alpha = if (ThemeState.glass) 0.58f else 1f),
        border = if (ThemeState.glass && !on) androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f)) else null
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Ícono y, a la derecha, un interruptor: se nota que se toca para encender o apagar.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(28.dp), tint = if (on) c.onSecondary else c.onSurface)
                Spacer(Modifier.weight(1f))
                Switch(on, null, enabled = enabled, modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(checkedTrackColor = c.onSecondary, checkedThumbColor = c.secondary))
            }
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.onSecondary else c.onSurface)
                Text(state, fontSize = 13.sp, color = if (on) c.onSecondary.copy(alpha = 0.8f) else c.onSurfaceVariant)
            }
        }
    }
}

/**
 * Controles conectados ahora, de cualquier marca. El G9 con su modo (y el acceso a su sección); los demás solo el
 * nombre: el panel de juego, las capturas con L3 + R3 y el monitor sirven con cualquiera.
 */
@Composable
private fun ControllersPanel(s: AppState) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val pads = remember(tick) {
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.isExternal && (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD }
            .map { it.name }.distinct()
    }
    Panel(tr("Controles", "Controllers")) {
        if (pads.isEmpty()) Text(tr("No hay controles conectados.", "No controllers connected."), fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        pads.forEach { name ->
            val g9 = name.contains("G9", true) || name.contains("x-box 360", true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (g9) "Legion G9" else name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (g9) Text(s.padMode?.label?.let { tr("Modo $it", "$it mode") } ?: "", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (g9) TextButton(onClick = { s.goTo(Section.G9) }) { Text(tr("Configurar", "Set up")) }
            }
        }
    }
}

/** Los atajos que están activados ahora, en corto; todos (y sus interruptores) en Controles. */
@Composable
private fun ShortcutsHelp(s: AppState, a: MainActivity) {
    val guide = AutoRules.legionKeys(a)
    fun on(g: AutoRules.Guide) = guide && AutoRules.guide(a, g)
    val chord = AutoRules.gamePanel(a)
    val panel = listOfNotNull(if (on(AutoRules.Guide.HOLD_PANEL)) tr("Mantener guía", "Hold guide") else null, if (chord) "L3 + R3" else null)
    val shot = listOfNotNull(if (on(AutoRules.Guide.RB_SHOT)) tr("Guía + RB", "Guide + RB") else null, if (chord) tr("mantener L3 + R3", "hold L3 + R3") else null)
    val back = listOfNotNull(if (on(AutoRules.Guide.TAP_BACK)) tr("Tocar guía", "Tap guide") else null)
    Panel(tr("Atajos", "Shortcuts"), trailing = { TextButton(onClick = { s.page = Page.CONTROLS }) { Text(tr("Controles", "Controls")) } }) {
        if (!s.autoServiceOn) Text(tr("Apagados: falta el servicio de accesibilidad.", "Off: the accessibility service is missing."),
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (panel.isEmpty() && shot.isEmpty() && back.isEmpty()) Text(tr("Ninguno activado.", "None turned on."),
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            if (panel.isNotEmpty()) HelpRow(tr("Panel de juego", "Game panel"), panel.joinToString(" · "))
            if (shot.isNotEmpty()) HelpRow(tr("Captura", "Screenshot"), shot.joinToString(" · "))
            if (back.isNotEmpty()) HelpRow(tr("Atrás", "Back"), back.joinToString(" · "))
        }
    }
}

@Composable
private fun HelpRow(title: String, desc: String) {
    Column {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Memoria: cuánta RAM queda, liberar (cierra lo que quedó en segundo plano) y, con Shizuku, las apps activas (con un
 * video, música o una descarga corriendo) para detenerlas, como "Apps activas" de los ajustes rápidos.
 */
@Composable
private fun MemoryPanel(s: AppState, a: MainActivity) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val info = remember(tick) { Memory.info(a) }
    val ready = Shell.state == Shell.State.READY
    var tasks by remember { mutableStateOf(listOf<Memory.Task>()) }
    LaunchedEffect(ready, tick / 2) { if (ready) tasks = Memory.tasks(a) }
    var busy by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Panel(tr("Memoria", "Memory"), trailing = {
        Text(tr("${Memory.gb(info.freeBytes)} libres de ${Memory.gb(info.totalBytes)}", "${Memory.gb(info.freeBytes)} free of ${Memory.gb(info.totalBytes)}"),
            fontSize = 14.sp, color = c.onSurfaceVariant)
    }) {
        val used = 1f - info.freeBytes.toFloat() / info.totalBytes.coerceAtLeast(1)
        LinearProgressIndicator({ used }, Modifier.fillMaxWidth().height(8.dp), color = c.secondary, trackColor = c.surfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Cierra las apps en segundo plano (no las activas, como un video sonando)", "Closes background apps (not active ones, like a playing video)"),
                Modifier.weight(1f), fontSize = 14.sp, color = c.onSurfaceVariant)
            Button(onClick = {
                busy = true
                scope.launch {
                    val freed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Memory.freeAll(a) }
                    busy = false; tick++
                    s.status = tr("Memoria liberada: ${Memory.gb(freed)}.", "Memory freed: ${Memory.gb(freed)}.")
                }
            }, enabled = !busy) { Text(if (busy) tr("Liberando…", "Freeing…") else tr("Liberar memoria", "Free memory")) }
        }
        // Administrador de tareas (con Shizuku): RAM y CPU de cada app, las activas marcadas, y detenerlas.
        Text(tr("Apps corriendo", "Running apps"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (!ready) Hint(tr("Ver cuánta RAM y CPU usa cada app y detenerlas necesita Shizuku. Sin él, las activas se detienen en Ajustes rápidos → Apps activas.",
            "Seeing each app's RAM and CPU and stopping them needs Shizuku. Without it, active apps are stopped in Quick settings → Active apps."))
        else if (tasks.isEmpty()) Text(tr("Leyendo…", "Reading…"), fontSize = 14.sp, color = c.onSurfaceVariant)
        else tasks.take(8).forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                task.app.icon?.let { d ->
                    androidx.compose.foundation.Image(remember(task.app.pkg) { androidx.compose.ui.graphics.painter.BitmapPainter(d.toBitmapSafe().asImageBitmap()) },
                        null, Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(task.app.label, fontSize = 15.sp, maxLines = 1)
                    val tags = listOfNotNull(
                        if (task.active) tr("Activa", "Active") else null,
                        if (Memory.restartsByItself(task.app.pkg)) tr("se reinicia sola", "restarts by itself") else null
                    )
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), fontSize = 12.sp, color = c.secondary)
                }
                Text("${task.ramMb} MB", Modifier.width(84.dp), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("%.0f %%".format(task.cpu), Modifier.width(64.dp), fontSize = 14.sp, color = c.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    tasks = tasks - task
                    scope.launch { Memory.stop(task.app.pkg); tick++ }
                }) { Text(tr("Detener", "Stop")) }
            }
        }
    }
}
/** Ícono de app como bitmap (los adaptativos no son BitmapDrawable). */
private fun android.graphics.drawable.Drawable.toBitmapSafe(): android.graphics.Bitmap {
    (this as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { return it }
    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    setBounds(0, 0, 96, 96); draw(android.graphics.Canvas(b))
    return b
}