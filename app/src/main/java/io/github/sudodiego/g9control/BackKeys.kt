package io.github.sudodiego.g9control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Botones traseros (comando 31, GcmKeyUtil en la app oficial). Paquete sin byte de largo:
 * `1F acción origen destino extra chk`. Acción 0 leer, 1 escribir, 2 restaurar.
 * Origen: L4 1, R4 2, L5 3, R5 4. Destino: código de botón de KeyCodeUtil menos 256 (A = B4…);
 * 0 = sin asignar (se vuelve a eso con la acción 2; escribir 0 deja A). Home también sirve aunque
 * la app oficial no lo ofrece. Las teclas de teclado se guardan pero no envían nada. Confirmado en el G9.
 */
enum class BackKey(val id: Int, val label: String) { L4(1, "L4"), R4(2, "R4"), L5(3, "L5"), R5(4, "R5") }

/** Botones que se pueden asignar a un trasero (los de la lista de la app oficial para el G9). */
val BACK_TARGETS: List<Pair<Int, String>> get() = listOf(
    0 to tr("Sin asignar", "Unassigned"),
    0xB4 to "A", 0xB5 to "B", 0xB7 to "X", 0xB8 to "Y",
    0xBA to "LB", 0xBB to "RB", 0xBE to "LT", 0xBF to "RT",
    0xC0 to "L3", 0xC1 to "R3", 0xBC to tr("Vista", "View"), 0xBD to tr("Menú", "Menu"), 0xC2 to "Home",
    0xB0 to tr("Cruceta ↑", "D-pad ↑"), 0xB1 to tr("Cruceta ↓", "D-pad ↓"), 0xB2 to tr("Cruceta ←", "D-pad ←"), 0xB3 to tr("Cruceta →", "D-pad →"),
) + stickTargets(tr("Stick izq.", "Left stick"), 0xEF) + stickTargets(tr("Stick der.", "Right stick"), 0xF7)

/** Las 8 direcciones de un stick, en el orden del firmware: ←, ↖, ↑, ↗, →, ↘, ↓, ↙. */
private fun stickTargets(name: String, first: Int) =
    listOf("←", "↖", "↑", "↗", "→", "↘", "↓", "↙").mapIndexed { i, d -> (first + i) to "$name $d" }

fun backTargetName(b: Int) = if (b == BACK_MACRO) "Macro"
    else BACK_TARGETS.firstOrNull { it.first == b }?.second ?: tr("Otro (0x%02X)", "Other (0x%02X)").format(b)

/** Botón del dibujo → byte que entiende el control (para grabar macros). */
val MACRO_KEY_BYTE = mapOf(
    PadKey.A to 0xB4, PadKey.B to 0xB5, PadKey.X to 0xB7, PadKey.Y to 0xB8,
    PadKey.LB to 0xBA, PadKey.RB to 0xBB, PadKey.LT to 0xBE, PadKey.RT to 0xBF,
    PadKey.L3 to 0xC0, PadKey.R3 to 0xC1, PadKey.VIEW to 0xBC, PadKey.MENU to 0xBD, PadKey.HOME to 0xC2,
    PadKey.UP to 0xB0, PadKey.DOWN to 0xB1, PadKey.LEFT to 0xB2, PadKey.RIGHT to 0xB3,
)

private fun gridLabel(b: Int) = when (b) {
    0xB0, 0xB1, 0xB2, 0xB3 -> ""
    else -> backTargetName(b)
}

/** Ícono del botón en la grilla: Vista, Home y Menú como en el control, flechas en la cruceta (las letras quedan). */
private fun gridIcon(b: Int): androidx.compose.ui.graphics.vector.ImageVector? = when (b) {
    0xBC -> Icons.Outlined.FilterNone; 0xBD -> Icons.Filled.Menu; 0xC2 -> Icons.Outlined.Home
    0xB0 -> Icons.Filled.KeyboardArrowUp; 0xB1 -> Icons.Filled.KeyboardArrowDown
    0xB2 -> Icons.Filled.KeyboardArrowLeft; 0xB3 -> Icons.Filled.KeyboardArrowRight
    else -> null
}

/** Las 8 direcciones de un stick, en el orden de los bytes (desde la izquierda, en el sentido del reloj). */
private val STICK_DIRS = listOf(Icons.Filled.West, Icons.Filled.NorthWest, Icons.Filled.North, Icons.Filled.NorthEast,
    Icons.Filled.East, Icons.Filled.SouthEast, Icons.Filled.South, Icons.Filled.SouthWest)

/**
 * Elegir un botón con botones grandes, ordenados como en el control (en vez de tocarlo en el dibujo, donde quedaban
 * chicos): arriba LT LB · RB RT, al medio Vista Home Menú, a la izquierda el stick izquierdo (L3 al centro) y la
 * cruceta en cruz, a la derecha A B X Y en rombo y el stick derecho (R3 al centro). [selected] se marca.
 */
@Composable
internal fun ButtonGrid(selected: Int?, enabled: Boolean, onPick: (Int) -> Unit) {
    val cur = selected ?: -1
    @Composable
    fun tile(b: Int, w: Modifier) = PickTile(gridLabel(b), cur == b, enabled, w, gridIcon(b)) { onPick(b) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tile(0xBE, Modifier.weight(1f)); tile(0xBA, Modifier.weight(1f))
            Spacer(Modifier.weight(0.6f))
            tile(0xBB, Modifier.weight(1f)); tile(0xBF, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            tile(0xBC, Modifier.width(130.dp)); tile(0xC2, Modifier.width(130.dp)); tile(0xBD, Modifier.width(130.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StickGrid(tr("Stick izquierdo", "Left stick"), 0xEF, 0xC0, cur, enabled, onPick)
            Cross3(tr("Botones", "Buttons"), listOf(-1, 0xB8, -1, 0xB7, -2, 0xB5, -1, 0xB4, -1), cur, enabled, onPick)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cross3(tr("Cruceta", "D-pad"), listOf(-1, 0xB0, -1, 0xB2, -2, 0xB3, -1, 0xB1, -1), cur, enabled, onPick)
            StickGrid(tr("Stick derecho", "Right stick"), 0xF7, 0xC1, cur, enabled, onPick)
        }
    }
}

/** Nueve lugares en 3 × 3: un botón (byte), −1 vacío, −2 el centro (un círculo, como el de la cruceta). */
@Composable
private fun Cross3(name: String, cells: List<Int>, cur: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, fontSize = 13.sp, color = c.onSurfaceVariant)
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { b ->
                    when (b) {
                        -1 -> Spacer(Modifier.size(52.dp))
                        -2 -> Box(Modifier.size(52.dp).background(c.surfaceContainerHigh, androidx.compose.foundation.shape.CircleShape))
                        else -> PickTile(gridLabel(b), cur == b, enabled, Modifier.size(52.dp), gridIcon(b)) { onPick(b) }
                    }
                }
            }
        }
    }
}
/** Un botón de la grilla: grande, fácil de tocar; el elegido en celeste. */
@Composable
internal fun PickTile(label: String, on: Boolean, enabled: Boolean, modifier: Modifier,
                      icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = MaterialTheme.shapes.medium,
        color = if (on) c.secondary else c.surfaceContainerHigh) {
        val fg = if (on) c.onSecondary else c.onSurface
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, label.ifEmpty { null }, Modifier.size(22.dp), tint = fg) }
            if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(8.dp))
            if (label.isNotEmpty()) Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = fg)
        }
    }
}

/**
 * Página "Traseros": a la izquierda los cuatro traseros grandes, como están atrás del control (L4 R4 arriba, L5 R5
 * abajo); a la derecha qué copia el elegido: un botón de la grilla, una dirección de un stick, una macro o nada. Con
 * una macro abierta, la página es el editor.
 */
@Composable
fun BackPage(s: AppState, a: MainActivity) {
    if (s.editingMacro != null) { MacroEditor(s, a); return }
    val editable = s.connected && s.appliedBackKeys != null
    var sel by rememberSaveable { mutableStateOf(BackKey.L4) }
    val c = MaterialTheme.colorScheme
    // Lee el valor actual: la grilla puede llamar a una versión guardada de esta función.
    fun pick(byte: Int) { if (byte != (s.backKeys[sel.id] ?: 0)) a.setBackKey(sel, byte) }

    TwoPane(
        first = {
            CompositionLocalProvider(LocalEditable provides editable) {
                Panel(tr("Traseros", "Back buttons"), tr("Elige uno", "Pick one")) {
                    listOf(listOf(BackKey.L4, BackKey.R4), listOf(BackKey.L5, BackKey.R5)).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { k ->
                                val on = k == sel
                                val v = s.backKeys[k.id] ?: 0
                                Surface(onClick = { sel = k }, modifier = Modifier.weight(1f).height(104.dp), shape = MaterialTheme.shapes.large,
                                    color = if (on) c.secondary else c.surfaceContainerHigh) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
                                        Text(k.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (on) c.onSecondary else c.onSurface)
                                        Text(backTargetName(v), fontSize = 15.sp, maxLines = 1, color = if (on) c.onSecondary.copy(alpha = 0.85f) else c.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    if (!s.connected) Hint(tr("Conecta el control en modo Android para cambiarlos.", "Connect the controller in Android mode to change them."))
                }
            }
        },
        second = {
            CompositionLocalProvider(LocalEditable provides editable) {
                // Al cambiar de trasero el contenido se funde: se nota que cambió.
                androidx.compose.animation.Crossfade(sel, label = "trasero") { k ->
                    val cur = s.backKeys[k.id] ?: 0
                    Panel(k.label, trailing = {
                        Text(backTargetName(cur), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.primary)
                    }) {
                        // Arriba lo que no es un botón (macro, nada), para verlo sin bajar.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PickTile(if (cur == BACK_MACRO) tr("Editar la macro", "Edit the macro") else "Macro", cur == BACK_MACRO, editable,
                                Modifier.weight(1f)) { a.openMacroEditor(k) }
                            PickTile(tr("Sin asignar", "Unassigned"), cur == 0, editable, Modifier.weight(1f)) { pick(0) }
                        }
                        ButtonGrid(cur, editable) { pick(it) }
                    }
                }
            }
        }
    )
}

/** Las 8 direcciones de un stick en 3 × 3, como se ven en él, con su clic (L3 o R3) al centro; la elegida, resaltada. */
@Composable
private fun StickGrid(name: String, first: Int, click: Int, cur: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, fontSize = 13.sp, color = c.onSurfaceVariant)
        listOf(listOf(1, 2, 3), listOf(0, -1, 4), listOf(7, 6, 5)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d ->
                    if (d < 0) PickTile(gridLabel(click), cur == click, enabled, Modifier.size(52.dp)) { onPick(click) }
                    else PickTile("", cur == first + d, enabled, Modifier.size(52.dp), STICK_DIRS[d]) { onPick(first + d) }
                }
            }
        }
    }
}/** Opciones del control que no son de un botón en particular. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlOptionsPanel(s: AppState, a: MainActivity) {
    CompositionLocalProvider(LocalEditable provides s.connected) {
        Panel(tr("Opciones", "Options")) {
            s.abSwapped?.let { sw -> SwitchRow(tr("Intercambiar A↔B y X↔Y", "Swap A↔B and X↔Y"), sw) { a.setAbSwap(it) } }
            SwitchRow(tr("Bloquear diagonales de la cruceta", "Lock D-pad diagonals"), s.dpadLock) { s.dpadLock = it }
            SwitchRow(tr("Intercambiar stick izquierdo y cruceta", "Swap left stick and D-pad"), s.swapDpad) { s.swapDpad = it }
        }
    }
}

