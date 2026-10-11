package io.github.sudodiego.g9control

import android.view.InputDevice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inicio de la sección Tablet: para cualquiera con la Y700, tenga o no el G9. A la izquierda los avisos, cómo está
 * la tablet ahora y accesos rápidos; a la derecha los controles conectados (cualquiera) y los atajos.
 */
@Composable
fun TabletHomePage(s: AppState, a: MainActivity) {
    TwoPane(
        first = {
            ServiceWarning(s, a)
            ShizukuWarning(a)
            TabletNowPanel(a)
            QuickPanel(a)
        },
        second = {
            ControllersPanel(s)
            ShortcutsHelp(s)
        }
    )
}

/** La tablet ahora: batería, frecuencia de la pantalla y monitor externo. Se actualiza cada pocos segundos. */
@Composable
private fun TabletNowPanel(a: MainActivity) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val b = remember(tick) { batteryNow(a) }
    val hz = remember(tick) { RefreshRate.current(a) }
    val monitor = remember(tick) { Monitor.current(a) }
    Panel(tr("La tablet ahora", "The tablet now")) {
        b?.let {
            val state = when {
                it.plugged && !it.charging -> if (ZuiBattery.bypassNow == true && Shell.state == Shell.State.READY) "Bypass" else tr("enchufada, sin cargar", "plugged in, not charging")
                it.charging -> tr("cargando", "charging")
                else -> tr("con batería", "on battery")
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${it.level} %", fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text("$state · ${"%.0f".format(it.tempC)} °C", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        hz?.let { Text(tr("Pantalla a $it Hz", "Screen at $it Hz"), fontSize = 14.sp) }
        monitor?.let { Text(tr("Monitor: ${it.name}", "Monitor: ${it.name}"), fontSize = 14.sp) }
    }
}

/** Lo que más se cambia, a un toque: bypass, monitor de rendimiento y pantalla 16:9. */
@Composable
private fun QuickPanel(a: MainActivity) {
    val scope = rememberCoroutineScope()
    val ready = Shell.state == Shell.State.READY
    var bypass by remember { mutableStateOf(ZuiBattery.bypassNow == true) }
    var hud by remember { mutableStateOf(AutoRules.perfHud(a)) }
    var wide by remember { mutableStateOf(Aspect.is169(a)) }
    LaunchedEffect(ready) { if (ready) ZuiBattery.readBypass()?.let { bypass = it } }
    CompositionLocalProvider(LocalEditable provides true) {
        Panel(tr("Rápido", "Quick")) {
            CompositionLocalProvider(LocalEditable provides ready) {
                SwitchRow("Bypass", bypass, tr("Con el cargador, la batería no carga ni se calienta.", "With the charger, the battery doesn't charge or heat up.")) { want ->
                    if (ready) scope.launch { if (ZuiBattery.setBypass(want)) bypass = want }
                }
            }
            SwitchRow(tr("Monitor de rendimiento", "Performance monitor"), hud, tr("FPS, CPU, GPU y batería sobre el juego.", "FPS, CPU, GPU and battery over the game.")) {
                hud = it; AutoRules.setPerfHud(a, it)
            }
            if (Aspect.available(a)) SwitchRow(tr("Pantalla 16:9", "16:9 screen"), wide, tr("Para un monitor externo sin bordes.", "For an external monitor without borders.")) { want ->
                scope.launch {
                    if (want != Aspect.is169(a)) Aspect.toggle(a)
                    delay(500)
                    wide = Aspect.is169(a)
                }
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

/** Los atajos más usados, en corto; todos (y sus interruptores) en Controles. */
@Composable
private fun ShortcutsHelp(s: AppState) {
    Panel(tr("Atajos", "Shortcuts"), trailing = { TextButton(onClick = { s.page = Page.CONTROLS }) { Text(tr("Controles", "Controls")) } }) {
        HelpRow(tr("Panel de juego", "Game panel"), tr("Mantener guía · L3 + R3", "Hold guide · L3 + R3"))
        HelpRow(tr("Captura", "Screenshot"), tr("Guía + RB · mantener L3 + R3", "Guide + RB · hold L3 + R3"))
        HelpRow(tr("Atrás", "Back"), tr("Tocar guía", "Tap guide"))
    }
}

@Composable
private fun HelpRow(title: String, desc: String) {
    Column {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
