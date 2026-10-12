package io.github.sudodiego.g9control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Configuración inicial: se muestra la primera vez que se abre la app y después desde Ajustes. Cada
 * paso se marca solo cuando ya está hecho (control detectado, conectado, servicio activo).
 */
@Composable
fun SetupDialog(s: AppState, a: MainActivity, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.92f)) {
            Column(Modifier.padding(28.dp)) {
                Text(tr("Configuración inicial", "Quick setup"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    tr("Lo que está en verde ya está listo. Solo el primer paso es necesario.",
                        "Green steps are already done. Only the first one is required."),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Lo esencial: el panel, las capturas, los avisos y todo lo automático vienen del servicio.
                    Step(
                        1, s.autoServiceOn, tr("Activa TBF Companion en Accesibilidad", "Turn on TBF Companion in Accessibility"),
                        tr("Con esto, mantener el botón guía (o L3 + R3) abre el panel de juego en cualquier app, y funcionan las capturas y los avisos. " +
                            "Android avisará que puede leer la pantalla: TBF solo mira qué app está al frente y los botones del control; no lee textos ni se conecta a internet.",
                            "With this, holding the guide button (or L3 + R3) opens the game panel in any app, and screenshots and notices work. " +
                                "Android will warn that it can read the screen: TBF only looks at which app is in front and the controller's buttons; it doesn't read text or connect to the internet.")
                    ) {
                        if (!s.autoServiceOn) OutlinedButton(onClick = { AutoRules.openServiceSettings(a) }) {
                            Text(tr("Abrir accesibilidad", "Open accessibility"))
                        }
                    }
                    Step(
                        2, Shell.state == Shell.State.READY, tr("Shizuku (opcional)", "Shizuku (optional)"),
                        tr("Agrega el bypass y el límite de carga, los modos de rendimiento, ver y cerrar apps, y que Ahorro no baje la pantalla a 30 Hz. " +
                            "Se instala aparte; en Ajustes → Permisos está cómo activarlo.",
                            "Adds bypass and charge limit, performance modes, seeing and closing apps, and keeping Saver from dropping the screen to 30 Hz. " +
                                "It's installed separately; Settings → Permissions explains how to turn it on.")
                    )
                    Step(
                        3, s.connected, tr("Si tienes el control G9", "If you have the G9 controller"),
                        tr("Coloca la tablet en el G9 en modo Android (luz azul oscuro; desde X-Input o DS5, mantén Vista + Menú unos 2 s). " +
                            "Cuando Android pregunte si TBF Companion puede usar el control, marca «Usar siempre».",
                            "Dock the tablet in the G9 in Android mode (dark blue light; from X-Input or DS5, hold View + Menu about 2 s). " +
                                "When Android asks whether TBF Companion can use the controller, check “Always”.")
                    ) {
                        if (!s.connected && s.padMode == PadMode.ANDROID) Button(onClick = { a.readAll() }) { Text(tr("Conectar", "Connect")) }
                    }
                    Step(
                        4, null, tr("Íconos de modo del G9 (opcional)", "G9 mode tiles (optional)"),
                        tr("En los ajustes rápidos (desliza dos veces hacia abajo), toca el lápiz y agrega «Android», «X-Input» y «DS5» (con el logo G9): cambian el modo con un toque.",
                            "In quick settings (swipe down twice), tap the pencil and add “Android”, “X-Input” and “DS5” (with the G9 logo): they switch mode with one tap.")
                    )
                    Hint(
                        tr("El firmware del G9 no se actualiza desde esta app. Si alguna vez lo necesitas, usa la app oficial de Legion una vez.",
                            "This app doesn't update the G9 firmware. If you ever need to, use the official Legion app once.")
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onClose) { Text(tr("Listo", "Done")) }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, done: Boolean?, title: String, text: String, action: @Composable () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val ok = Color(0xFF2EB872)
    Surface(color = c.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (done == true) ok else c.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (done == true) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = Color.White)
                else Text("$n", fontWeight = FontWeight.Bold, color = c.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurfaceVariant)
                action()
            }
        }
    }
}
