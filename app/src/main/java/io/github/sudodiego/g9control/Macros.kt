package io.github.sudodiego.g9control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Macros de los botones traseros (comando 83 = 0x53, GcmBtnMacroUtil en la app oficial).
 * Confirmado en el G9 (8 oct 2026):
 * - Ajustes: 53 FD 0B btn 01 modo bucle intHi intLo sensibilidad chk (escribir PRIMERO).
 * - Pasos:   53 00 len btn [esperaHi esperaLo botón mantenerHi mantenerLo]×n chk, len = 5n + 5,
 *   hasta 11 pasos por paquete. Tiempos en ms (pasos de 5). La espera cuenta desde que se soltó
 *   el paso anterior. Al releer (53 FE 06 btn 00) los tiempos vuelven multiplicados por 16.
 * - Borrar:  53 FC 05 btn chk (el trasero queda sin asignar). Con macro, el mapeo lee FF FF.
 * Escribir los ajustes después de los pasos borra los pasos.
 */

data class MacroStep(val key: Int, val holdMs: Int = 100, val waitMs: Int = 100)

data class Macro(
    val steps: List<MacroStep> = emptyList(),
    /** 0 = un toque la ejecuta entera; 1 = solo mientras se mantiene (se corta al soltar; confirmado). */
    val mode: Int = 0,
    val loop: Boolean = false,
    val loopMs: Int = 100,
)

const val MACRO_MAX_STEPS = 11
const val MACRO_MAX_MS = 10_000
/** Valor de mapeo que el control devuelve cuando el trasero tiene una macro. */
const val BACK_MACRO = 0xFF

object MacroGcm {
    fun read(src: Int) = Gcm.pkt(0x53, 0xFE, 6, src, 0, 0)
    fun readSettings(src: Int) = Gcm.pkt(0x53, 0xFD, 6, src, 0, 0)
    fun clear(src: Int) = Gcm.pkt(0x53, 0xFC, 5, src, 0)

    fun writeSettings(src: Int, m: Macro): ByteArray {
        val li = m.loopMs.coerceIn(5, 1000)
        return Gcm.pkt(0x53, 0xFD, 0x0B, src, 1, m.mode, if (m.loop) 1 else 0, li shr 8, li and 0xFF, 1, 0)
    }

    fun writeSteps(src: Int, steps: List<MacroStep>): ByteArray {
        val v = mutableListOf(0x53, 0, 5 * steps.size + 5, src)
        steps.forEachIndexed { i, st ->
            val wait = if (i == 0) 0 else round5(st.waitMs)
            val hold = round5(st.holdMs).coerceAtLeast(5)
            v += listOf(wait shr 8, wait and 0xFF, st.key, hold shr 8, hold and 0xFF)
        }
        v += 0
        return Gcm.pkt(*v.toIntArray())
    }

    fun parseSteps(r: ByteArray?): List<MacroStep>? {
        if (r == null || r.size < 5 || r[0].u() != 0x53) return null
        val body = r.copyOfRange(4, r.size - 1)
        return (0 until body.size / 5).map { i ->
            val b = body.copyOfRange(i * 5, i * 5 + 5)
            MacroStep(
                key = b[2].u(),
                waitMs = ((b[0].u() shl 8) or b[1].u()) shr 4,
                holdMs = ((b[3].u() shl 8) or b[4].u()) shr 4,
            )
        }
    }

    /** 53 FD 0B btn acción modo bucle intHi intLo sensibilidad chk. */
    fun parseSettings(r: ByteArray?, steps: List<MacroStep>): Macro? {
        if (r == null || r.size < 10) return null
        return Macro(steps, mode = r[5].u(), loop = r[6].u() == 1, loopMs = ((r[7].u() shl 8) or r[8].u()).coerceAtLeast(5))
    }

    private fun round5(ms: Int) = (((ms + 2) / 5) * 5).coerceIn(0, MACRO_MAX_MS)
}

/**
 * Macros listas para cargar en el editor. Solo secuencias: en el G9 cada paso empieza cuando se soltó el anterior,
 * así que una macro no puede mantener dos botones a la vez (los atajos de emulador como Vista + Menú no se pueden;
 * esos los hace la app, ver "Tocar la guía en…"). Quedan las que sirven en cualquier juego: repetir un botón.
 */
data class SuggestedMacro(val name: String, val desc: String, val macro: Macro)

private const val K_A = 0xB4; private const val K_B = 0xB5

val SUGGESTED_MACROS get() = listOf(
    SuggestedMacro(tr("A repetido", "Repeat A"), tr("Pasar diálogos: mientras lo mantienes", "Skip dialogue: while held"),
        Macro(listOf(MacroStep(K_A, 40, 0)), mode = 1, loop = true, loopMs = 60)),
    SuggestedMacro(tr("B repetido", "Repeat B"), tr("Turbo de B mientras lo mantienes", "B turbo while held"),
        Macro(listOf(MacroStep(K_B, 40, 0)), mode = 1, loop = true, loopMs = 60)),
)

/** Destinos que puede pulsar una macro: los mismos de los traseros, sin "Sin asignar". */
val MACRO_KEYS get() = BACK_TARGETS.filter { it.first != 0 }

/**
 * Editor de la macro de un trasero, a página completa. Se muestra en la misma página (no en un
 * diálogo) para que los botones del control lleguen a la app mientras se graba.
 * Izquierda: el dibujo del control para agregar pasos y las opciones. Derecha: la línea de tiempo y
 * el paso elegido.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MacroEditor(s: AppState, a: MainActivity) {
    val k = s.editingMacro ?: return
    val m = s.macroDraft
    val c = MaterialTheme.colorScheme
    var sel by remember(k) { mutableIntStateOf(-1) }
    // Siempre sobre el borrador actual: estas funciones pueden llegar guardadas desde una composición
    // anterior (el dibujo no se vuelve a componer en cada cambio) y con "m" agregarían sobre una copia vieja.
    fun setSteps(l: List<MacroStep>) { s.macroDraft = s.macroDraft.copy(steps = l) }
    fun add(key: Int) {
        val now = s.macroDraft.steps
        if (now.size >= MACRO_MAX_STEPS) return
        setSteps(now + MacroStep(key)); sel = now.size
    }
    val canAdd = !s.macroRecording && m.steps.size < MACRO_MAX_STEPS
    val padOf = MACRO_KEY_BYTE.entries.associate { (kk, v) -> v to kk }

    val left: @Composable ColumnScope.() -> Unit = {
        Panel(tr("Macro de ${k.label}", "${k.label} macro"), trailing = { TextButton(onClick = { a.closeMacroEditor() }) { Text(tr("Cerrar", "Close")) } }) {
            // Grabar: se aprietan los botones en el control y quedan los pasos con sus tiempos.
            if (s.macroRecording) {
                Surface(color = c.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FiberManualRecord, null, tint = c.error)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Grabando · ${s.recordedSteps.size} pasos", "Recording · ${s.recordedSteps.size} steps"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = c.onErrorContainer)
                        Button(onClick = { a.stopMacroRecording() }) { Text(tr("Detener", "Stop")) }
                    }
                }
            } else Button(onClick = { a.startMacroRecording(); sel = -1 }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FiberManualRecord, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Grabar con el control", "Record with the controller"))
            }
            Text(tr("O agrega pasos uno por uno:", "Or add steps one by one:"), fontSize = 14.sp, color = c.onSurfaceVariant)
            ButtonGrid(null, canAdd, ::add)
        }
        // Plantillas: cargan pasos, modo y bucle en el borrador (no se guarda en el control hasta "Guardar").
        Panel(tr("Sugeridas", "Suggested"), tr("Cargar una y ajustarla", "Load one and tweak it")) {
            SUGGESTED_MACROS.forEach { sm ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !s.macroRecording) { s.macroDraft = sm.macro; sel = -1 }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sm.name, fontSize = 15.sp)
                        Text(sm.desc, fontSize = 12.sp, color = c.onSurfaceVariant)
                    }
                    Text(tr("Cargar", "Load"), color = c.primary, fontSize = 14.sp)
                }
            }
            Hint(tr("Una macro aprieta un botón después de otro, nunca dos a la vez: los atajos de emulador (Vista + Menú…) se hacen con «Tocar la guía» en Controles.",
                "A macro presses one button after another, never two at once: emulator shortcuts (View + Menu…) go through “Tapping guide” in Controls."))
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        val steps = if (s.macroRecording) s.recordedSteps.toList() else m.steps
        val total = steps.mapIndexed { i, st -> (if (i == 0) 0 else st.waitMs) + st.holdMs }.sum()
        Panel(tr("Pasos", "Steps"), tr("${steps.size} de $MACRO_MAX_STEPS · dura ${"%.2f".format(total / 1000f)} s", "${steps.size} of $MACRO_MAX_STEPS · lasts ${"%.2f".format(total / 1000f)} s")) {
            if (steps.isEmpty()) Hint(tr("Todavía no hay pasos.", "No steps yet."))
            else Timeline(steps, sel) { sel = if (sel == it) -1 else it }
            val st = m.steps.getOrNull(sel)
            if (st != null && !s.macroRecording) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Paso ${sel + 1} · ${backTargetName(st.key)}", "Step ${sel + 1} · ${backTargetName(st.key)}"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = {
                        setSteps(m.steps.toMutableList().also { java.util.Collections.swap(it, sel, sel - 1) }); sel -= 1
                    }, enabled = sel > 0) { Icon(Icons.Outlined.ChevronLeft, tr("Mover antes", "Move earlier")) }
                    IconButton(onClick = {
                        setSteps(m.steps.toMutableList().also { java.util.Collections.swap(it, sel, sel + 1) }); sel += 1
                    }, enabled = sel < m.steps.size - 1) { Icon(Icons.Outlined.ChevronRight, tr("Mover después", "Move later")) }
                    IconButton(onClick = { setSteps(m.steps.toMutableList().also { it.removeAt(sel) }); sel = -1 }) {
                        Icon(Icons.Outlined.Delete, tr("Quitar paso", "Remove step"))
                    }
                }
                fun change(n: MacroStep) = setSteps(m.steps.toMutableList().also { it[sel] = n })
                if (sel > 0) MsRow(tr("Pausa antes", "Pause before"), st.waitMs, 0..MACRO_MAX_MS) { change(st.copy(waitMs = it)) }
                MsRow(tr("Mantener", "Hold"), st.holdMs, 5..MACRO_MAX_MS) { change(st.copy(holdMs = it)) }
            } else if (steps.isNotEmpty() && !s.macroRecording) Hint(tr("Toca un paso para cambiar sus tiempos.", "Tap a step to change its timing."))
        }
        Panel {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceChips(listOf(0 to tr("Completa al tocar", "Full on tap"), 1 to tr("Mientras lo mantienes", "While held")), m.mode) {
                    s.macroDraft = m.copy(mode = it)
                }
                FilterChip(
                    selected = m.loop, onClick = { s.macroDraft = m.copy(loop = !m.loop) }, label = { Text(tr("En bucle", "Loop")) },
                    leadingIcon = if (m.loop) { { Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp)) } } else null
                )
            }
            if (m.loop) MsRow(tr("Pausa entre vueltas", "Pause between loops"), m.loopMs, 5..1000) { s.macroDraft = m.copy(loopMs = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { a.saveMacro() }, enabled = s.connected && m.steps.isNotEmpty() && !s.macroRecording) {
                    Text(tr("Guardar en el control", "Save to the controller"))
                }
                if (s.backKeys[k.id] == BACK_MACRO) ConfirmButton(tr("Quitar macro", "Remove macro"), enabled = s.connected) { a.clearMacro(k) }
            }
        }
    }

    TwoPane(first = left, second = right)
}

/**
 * Línea de tiempo: cada paso es una barra tan larga como lo que se mantiene, y el espacio antes es la
 * pausa. Abajo, el número del paso. Tocar una barra la elige.
 */
@Composable
private fun Timeline(steps: List<MacroStep>, sel: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    // Mínimo visible para que las barras muy cortas se puedan tocar.
    val total = steps.mapIndexed { i, st -> (if (i == 0) 0 else st.waitMs) + st.holdMs }.sum().coerceAtLeast(1)
    val minW = total * 0.05f
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, st ->
            if (i > 0 && st.waitMs > 0) Box(
                Modifier.weight(st.waitMs.toFloat().coerceAtLeast(minW / 3)).height(2.dp)
                    .background(c.outlineVariant)
            )
            val on = i == sel
            Surface(
                onClick = { onSelect(i) }, modifier = Modifier.weight(st.holdMs.toFloat().coerceAtLeast(minW)).fillMaxHeight(),
                shape = MaterialTheme.shapes.small,
                color = if (on) c.primary else c.primaryContainer,
                border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, c.primary.copy(alpha = 0.4f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        shortKey(st.key), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (on) c.onPrimary else c.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/** Nombre corto para la barra: "A", "LB", "↑", "L←"… */
private fun shortKey(b: Int): String = when (b) {
    0xB0 -> "↑"; 0xB1 -> "↓"; 0xB2 -> "←"; 0xB3 -> "→"
    in 0xEF..0xF6 -> "L" + listOf("←", "↖", "↑", "↗", "→", "↘", "↓", "↙")[b - 0xEF]
    in 0xF7..0xFE -> "R" + listOf("←", "↖", "↑", "↗", "→", "↘", "↓", "↙")[b - 0xF7]
    else -> backTargetName(b)
}

/** Tiempo en ms con − / + de a 5 ms (pasos del firmware) y valores rápidos. */
@Composable
private fun MsRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(150.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { onChange((value - 5).coerceIn(range)) }, Modifier.size(36.dp)) { Icon(Icons.Outlined.Remove, tr("Menos", "Less")) }
        Text("$value ms", Modifier.width(80.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        IconButton(onClick = { onChange((value + 5).coerceIn(range)) }, Modifier.size(36.dp)) { Icon(Icons.Outlined.Add, tr("Más", "More")) }
        for (q in listOf(50, 100, 250, 500)) if (q in range) TextButton(onClick = { onChange(q) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("$q", fontSize = 12.sp, color = if (q == value) MaterialTheme.colorScheme.primary else Color.Unspecified)
        }
    }
}
