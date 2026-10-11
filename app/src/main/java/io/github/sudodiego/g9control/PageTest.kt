package io.github.sudodiego.g9control

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Botones que llegan a Android. El M no: el control manda encendido + volumen abajo por su
 * dispositivo "Consumer Control" (captura de pantalla), y Android lo procesa antes que las apps.
 */
val TESTABLE = PadKey.entries - PadKey.M

/** Tecla de Android → botón del G9 (en X-Input y DS5 Android usa los mismos códigos). */
fun padKeyOf(keyCode: Int): PadKey? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> PadKey.A
    KeyEvent.KEYCODE_BUTTON_B -> PadKey.B
    KeyEvent.KEYCODE_BUTTON_X -> PadKey.X
    KeyEvent.KEYCODE_BUTTON_Y -> PadKey.Y
    KeyEvent.KEYCODE_BUTTON_L1 -> PadKey.LB
    KeyEvent.KEYCODE_BUTTON_R1 -> PadKey.RB
    KeyEvent.KEYCODE_BUTTON_L2 -> PadKey.LT
    KeyEvent.KEYCODE_BUTTON_R2 -> PadKey.RT
    KeyEvent.KEYCODE_BUTTON_THUMBL -> PadKey.L3
    KeyEvent.KEYCODE_BUTTON_THUMBR -> PadKey.R3
    KeyEvent.KEYCODE_BUTTON_SELECT -> PadKey.VIEW
    KeyEvent.KEYCODE_BUTTON_START -> PadKey.MENU
    KeyEvent.KEYCODE_BUTTON_MODE -> PadKey.HOME
    KeyEvent.KEYCODE_DPAD_UP -> PadKey.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> PadKey.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> PadKey.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> PadKey.RIGHT
    else -> null
}

/** Lo que está apretado ahora: teclas, cruceta como eje (HAT), gatillos y sticks. */
fun pressedNow(s: AppState): Set<PadKey> = buildSet {
    s.pressedKeys.keys.forEach { k -> padKeyOf(k)?.let(::add) }
    if (s.hatY < -0.5f) add(PadKey.UP)
    if (s.hatY > 0.5f) add(PadKey.DOWN)
    if (s.hatX < -0.5f) add(PadKey.LEFT)
    if (s.hatX > 0.5f) add(PadKey.RIGHT)
    if (s.lt > 0.5f) add(PadKey.LT)
    if (s.rt > 0.5f) add(PadKey.RT)
    if (hypot(s.lx, s.ly) > 0.5f) add(PadKey.STICK_L)
    if (hypot(s.rx, s.ry) > 0.5f) add(PadKey.STICK_R)
}

/**
 * Página "Controles": a la izquierda la prueba de botones; a la derecha traseros y opciones (o el
 * editor de macro). Pensada para verse entera sin desplazarse.
 */
@Composable
fun ControlsPage(s: AppState, a: MainActivity) {
    val right: @Composable ColumnScope.() -> Unit = {
        ControlOptionsPanel(s, a)
        TouchpadShotPanel(a)
        if (s.otherKeys.isNotEmpty()) Panel(tr("Otros botones", "Other buttons")) { Text(s.otherKeys.joinToString("\n"), fontSize = 13.sp) }
    }
    DrawingPane(drawing = { TestPanel(s) }, side = right)
}

/** M dos veces en DS5 (el clic del touchpad del G9): captura. Es del G9: otros controles no lo tienen así. */
@Composable
private fun TouchpadShotPanel(a: MainActivity) {
    var on by remember { mutableStateOf(AutoRules.touchpadShot(a)) }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel {
            SwitchRow(tr("M dos veces: captura", "M twice: screenshot"), on, tr("En DS5", "In DS5")) { on = it; AutoRules.setTouchpadShot(a, it) }
        }
    }
}

@Composable
private fun TestPanel(s: AppState) {
    // Fuera de la prueba el dibujo queda quieto (el stick izquierdo además mueve el foco como cruceta).
    val on = s.testMode
    val pressed = if (on) pressedNow(s) else emptySet()
    Panel {
        fun v(x: Float) = if (on) x else 0f
        ControllerTestView(pressed, v(s.lx), v(s.ly), v(s.rx), v(s.ry), v(s.lt), v(s.rt), Modifier.fillMaxWidth(), lights = s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Prueba de botones", "Button test"), fontWeight = FontWeight.SemiBold)
                Text(tr("${s.testedKeys.keys.count { it in TESTABLE }} de ${TESTABLE.size} probados", "${s.testedKeys.keys.count { it in TESTABLE }} of ${TESTABLE.size} tested"), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.testMode) {
                TextButton(onClick = { s.testedKeys.clear(); s.otherKeys.clear() }) { Text(tr("Reiniciar", "Reset")) }
                FilledTonalButton(onClick = { s.testMode = false }) { Text(tr("Terminar", "Finish")) }
            } else Button(onClick = { s.testedKeys.clear(); s.otherKeys.clear(); s.testMode = true }) { Text(tr("Probar botones", "Test buttons")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) { TriggerRow("LT", v(s.lt)) }
            Box(Modifier.weight(1f)) { TriggerRow("RT", v(s.rt)) }
        }
        KeyGrid(s, pressed)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGrid(s: AppState, pressed: Set<PadKey>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TESTABLE.forEach { k ->
            val now = k in pressed
            val done = k in s.testedKeys
            val c = MaterialTheme.colorScheme
            Surface(
                shape = MaterialTheme.shapes.small,
                color = when { now -> c.primary; done -> Color(0xFF3DDC84).copy(alpha = 0.18f); else -> c.surfaceVariant }
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (done && !now) {
                        Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Color(0xFF2EB872))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        k.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (now) c.onPrimary else c.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TriggerRow(label: String, v: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(40.dp), fontSize = 14.sp)
        LinearProgressIndicator(progress = { v.coerceIn(0f, 1f) }, Modifier.weight(1f).height(8.dp))
        Text("%3d%%".format((v * 100).toInt()), Modifier.width(52.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
