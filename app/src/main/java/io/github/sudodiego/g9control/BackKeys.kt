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

/** Qué hace un trasero: copia un botón, una dirección de un stick, una macro o nada. */
private enum class BackKind { BUTTON, STICK, MACRO, NONE;
    val label get() = when (this) { BUTTON -> tr("Botón", "Button"); STICK -> "Stick"; MACRO -> "Macro"; NONE -> tr("Nada", "Nothing") }
}

private fun kindOf(b: Int) = when {
    b == 0 -> BackKind.NONE
    b == BACK_MACRO -> BackKind.MACRO
    b in 0xEF..0xFE -> BackKind.STICK
    else -> BackKind.BUTTON
}

/**
 * Página "Traseros", en dos pasos: a la derecha se elige el trasero (L4, R4, L5, R5, con lo que copia cada uno) y a la
 * izquierda qué hace: copiar un botón (se toca en el dibujo), una dirección de un stick (las 8 a la vista), una macro
 * o nada. El dibujo solo sale cuando sirve (copiar un botón). Con una macro abierta, la página es el editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackPage(s: AppState, a: MainActivity) {
    if (s.editingMacro != null) { MacroEditor(s, a); return }
    val editable = s.connected && s.appliedBackKeys != null
    var sel by rememberSaveable { mutableStateOf(BackKey.L4) }
    val cur = s.backKeys[sel.id] ?: 0
    // Lo que se está viendo (puede ser otro tipo que el guardado: elegir "Stick" no cambia nada hasta tocar una dirección).
    var kind by remember(sel, cur) { mutableStateOf(kindOf(cur)) }
    val padOf = MACRO_KEY_BYTE.entries.associate { (k, v) -> v to k }
    // Lee el valor actual (no "cur"): el dibujo puede llamar a una versión guardada de esta función.
    fun pick(byte: Int) { if (byte != (s.backKeys[sel.id] ?: 0)) a.setBackKey(sel, byte) }
    val c = MaterialTheme.colorScheme

    val editor: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalEditable provides editable) {
            Panel(tr("${sel.label}: qué hace", "${sel.label}: what it does"), trailing = {
                Text(backTargetName(cur), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.primary)
            }) {
                ChoiceChips(BackKind.entries.map { it to it.label }, kind) { k ->
                    kind = k
                    when (k) {
                        BackKind.NONE -> pick(0)
                        BackKind.MACRO -> a.openMacroEditor(sel)
                        else -> Unit   // botón o stick: se elige abajo
                    }
                }
                when (kind) {
                    BackKind.BUTTON -> {
                        Text(tr("Toca el botón que copia", "Tap the button it copies"), fontSize = 14.sp, color = c.onSurfaceVariant)
                        val tags = BackKey.entries.mapNotNull { k -> padOf[s.backKeys[k.id] ?: 0]?.let { it to k.label } }
                            .groupBy({ it.first }, { it.second })
                        ControllerKeyPicker(
                            lit = setOfNotNull(padOf[cur]), tags = tags, enabled = editable, modifier = Modifier.fillMaxWidth(), lights = s
                        ) { k -> MACRO_KEY_BYTE[k]?.let(::pick) }
                        // Gatillos y bumpers, que en el dibujo de frente no se ven.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            for (t in listOf(0xBE to "LT", 0xBA to "LB")) TargetChip(t.second, cur == t.first, editable) { pick(t.first) }
                            Spacer(Modifier.weight(1f))
                            for (t in listOf(0xBB to "RB", 0xBF to "RT")) TargetChip(t.second, cur == t.first, editable) { pick(t.first) }
                        }
                    }
                    BackKind.STICK -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        StickGrid(tr("Stick izquierdo", "Left stick"), 0xEF, cur, editable, ::pick)
                        StickGrid(tr("Stick derecho", "Right stick"), 0xF7, cur, editable, ::pick)
                    }
                    BackKind.MACRO -> {
                        Text(tr("Una secuencia de botones con sus tiempos.", "A sequence of buttons with their timing."), fontSize = 14.sp, color = c.onSurfaceVariant)
                        Button(onClick = { a.openMacroEditor(sel) }, enabled = editable) {
                            Text(if (cur == BACK_MACRO) tr("Editar la macro", "Edit the macro") else tr("Crear la macro", "Create the macro"))
                        }
                    }
                    BackKind.NONE -> Text(tr("No hace nada.", "It does nothing."), fontSize = 14.sp, color = c.onSurfaceVariant)
                }
                if (!s.connected) Hint(tr("Conecta el control en modo Android para cambiarlos.", "Connect the controller in Android mode to change them."))
            }
        }
    }
    val keys: @Composable ColumnScope.() -> Unit = {
        Panel(tr("Elige un trasero", "Pick a back button")) {
            // L4/R4 arriba, L5/R5 abajo, como en el control.
            BackKey.entries.forEach { k ->
                val on = k == sel
                val v = s.backKeys[k.id] ?: 0
                Surface(
                    onClick = { sel = k }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    color = if (on) c.primaryContainer else c.surfaceContainerHigh,
                    border = if (on) androidx.compose.foundation.BorderStroke(1.5.dp, c.primary) else null
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k.label, Modifier.width(56.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(backTargetName(v), Modifier.weight(1f), fontSize = 16.sp, color = if (on) c.onPrimaryContainer else c.onSurfaceVariant)
                        if (v == BACK_MACRO) Icon(Icons.Outlined.Edit, null, tint = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
    TwoPane(first = editor, second = keys)
}

/** Las 8 direcciones de un stick en una cuadrícula de 3 × 3, como se ven en el stick; la elegida, resaltada. */
@Composable
private fun StickGrid(name: String, first: Int, cur: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val dirs = listOf("←", "↖", "↑", "↗", "→", "↘", "↓", "↙")
    val c = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        listOf(listOf(1, 2, 3), listOf(0, -1, 4), listOf(7, 6, 5)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d ->
                    if (d < 0) Box(Modifier.size(52.dp).background(c.surfaceContainerHigh, androidx.compose.foundation.shape.CircleShape))
                    else {
                        val on = cur == first + d
                        Surface(onClick = { onPick(first + d) }, enabled = enabled, modifier = Modifier.size(52.dp), shape = MaterialTheme.shapes.medium,
                            color = if (on) c.secondary else c.surfaceContainerHigh) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(dirs[d], fontSize = 20.sp, color = if (on) c.onSecondary else c.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun TargetChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
        modifier = Modifier.padding(horizontal = 2.dp),
        // Fondo sólido: LT/LB/RB/RT van encima del dibujo.
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

/** Dirección de un stick: chip con menú de las 8 direcciones. */
@Composable
internal fun StickDirChip(name: String, first: Int, cur: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val dirs = listOf("←", "↖", "↑", "↗", "→", "↘", "↓", "↙")
    val i = cur - first
    Box {
        FilterChip(
            selected = i in dirs.indices, onClick = { open = true }, enabled = enabled,
            label = { Text(if (i in dirs.indices) "$name ${dirs[i]}" else name) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }
        )
        DropdownMenu(open, { open = false }) {
            // Las 8 direcciones en una cuadrícula de 3×3, como se ven en el stick.
            val grid = listOf(listOf(1, 2, 3), listOf(0, -1, 4), listOf(7, 6, 5))
            grid.forEach { row ->
                Row {
                    row.forEach { d ->
                        if (d < 0) Spacer(Modifier.size(52.dp))
                        else TextButton(onClick = { open = false; onPick(first + d) }, modifier = Modifier.size(52.dp)) {
                            Text(dirs[d], fontSize = 20.sp, fontWeight = if (d == i) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

/** Opciones del control que no son de un botón en particular. */
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

