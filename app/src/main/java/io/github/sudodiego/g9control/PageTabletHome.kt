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
            QuickTiles(a)
        },
        second = {
            ControllersPanel(s)
            ShortcutsHelp(s)
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
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        b?.let {
            val state = when {
                it.plugged && !it.charging -> if (ZuiBattery.bypassNow == true && Shell.state == Shell.State.READY) "Bypass" else tr("Sin cargar", "Not charging")
                it.charging -> left?.let { ms -> "⚡ " + ZuiBattery.formatTime(ms) } ?: tr("Cargando", "Charging")
                else -> tr("Batería", "Battery")
            }
            StatTile("${it.level} %", state, Modifier.weight(1.3f).fillMaxHeight(), big = true)
            StatTile("${"%.0f".format(it.tempC)} °C", tr("Batería", "Battery"), Modifier.weight(1f).fillMaxHeight())
        }
        StatTile(hz?.let { "$it Hz" } ?: "—", monitor?.name ?: tr("Pantalla", "Screen"), Modifier.weight(1f).fillMaxHeight())
    }
}

/** Un dato grande con su etiqueta debajo, en una tarjeta de vidrio. */
@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, big: Boolean = false) {
    Panel(modifier = modifier) {
        Text(value, fontSize = if (big) 40.sp else 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Lo que más se cambia, a un toque y en mosaicos: bypass, monitor de rendimiento y pantalla 16:9. */
@Composable
private fun QuickTiles(a: MainActivity) {
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
        QuickTile(Icons.Outlined.QueryStats, tr("Monitor", "Monitor"), if (hud) tr("Encendido", "On") else tr("Apagado", "Off"), hud,
            Modifier.weight(1f).fillMaxHeight()) { hud = !hud; AutoRules.setPerfHud(a, hud) }
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
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, state: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large,
        color = if (on) c.secondary else c.surfaceContainer.copy(alpha = if (ThemeState.glass) 0.58f else 1f),
        border = if (ThemeState.glass && !on) androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f)) else null
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = if (on) c.onSecondary else c.onSurface)
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
