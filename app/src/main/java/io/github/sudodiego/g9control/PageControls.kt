package io.github.sudodiego.g9control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Controles: los atajos de la app con cualquier control, cada uno con su interruptor. El botón guía (Legion en el
 * G9, Xbox, PS, Home) con lo que hace tocarlo, mantenerlo y combinarlo; L3 + R3 para controles sin él; lo propio del
 * G9 y los botones de la tablet.
 */
@Composable
fun ShortcutsPage(s: AppState, a: MainActivity) {
    // Todos los atajos los hace el servicio de accesibilidad: sin él, oscurecidos.
    Gate(s.autoServiceOn) { ShortcutsContent(s, a) }
}

@Composable
private fun ShortcutsContent(s: AppState, a: MainActivity) {
    TwoPane(
        first = { GuidePanel(a); GuideTapPanel(a) },
        second = {
            OtherShortcutsPanel(a)
            TabletButtonsPanel(a)
        }
    )
}

@Composable
private fun GuidePanel(a: MainActivity) {
    var on by remember { mutableStateOf(AutoRules.legionKeys(a)) }
    val states = remember { AutoRules.Guide.entries.associateWith { mutableStateOf(AutoRules.guide(a, it)) } }
    var burst by remember { mutableStateOf(AutoRules.burstShots(a)) }
    var streamTip by remember { mutableStateOf(AutoRules.streamTip(a)) }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Botón guía", "Guide button"), tr("Legion en el G9 · Xbox, PS u Home en otros", "Legion on the G9 · Xbox, PS or Home on others"),
            trailing = { Switch(on, { on = it; AutoRules.setLegionKeys(a, it) }, enabled = LocalGateOk.current) }) {
            CompositionLocalProvider(LocalEditable provides (on && LocalGateOk.current)) {
                fun row(g: AutoRules.Guide) = states.getValue(g)
                ShortcutRow(listOf(tr("Guía", "Guide")), tr("Tocar", "Tap"), tr("Atrás", "Back"), row(AutoRules.Guide.TAP_BACK).value) {
                    row(AutoRules.Guide.TAP_BACK).value = it; AutoRules.setGuide(a, AutoRules.Guide.TAP_BACK, it)
                }
                ShortcutRow(listOf(tr("Guía", "Guide")), tr("Mantener", "Hold"), tr("Panel de juego", "Game panel"), row(AutoRules.Guide.HOLD_PANEL).value) {
                    row(AutoRules.Guide.HOLD_PANEL).value = it; AutoRules.setGuide(a, AutoRules.Guide.HOLD_PANEL, it)
                }
                ShortcutRow(listOf(tr("Guía", "Guide")), tr("Mantener 5 s (el G9 se apaga)", "Hold 5 s (the G9 turns off)"), tr("Bloquear la tablet", "Lock the tablet"),
                    row(AutoRules.Guide.HOLD_LOCK).value) {
                    row(AutoRules.Guide.HOLD_LOCK).value = it; AutoRules.setGuide(a, AutoRules.Guide.HOLD_LOCK, it)
                }
                ShortcutRow(listOf(tr("Guía", "Guide"), "RB"), null, tr("Captura", "Screenshot"), row(AutoRules.Guide.RB_SHOT).value) {
                    row(AutoRules.Guide.RB_SHOT).value = it; AutoRules.setGuide(a, AutoRules.Guide.RB_SHOT, it)
                }
                ShortcutRow(emptyList(), tr("Seguir manteniendo RB", "Keep holding RB"), tr("Ráfaga, una por segundo", "Burst, one per second"), burst) {
                    burst = it; AutoRules.setBurstShots(a, it)
                }
                ShortcutRow(listOf(tr("Guía", "Guide"), "LB"), tr("Sale del juego en Dolphin", "Quits the game in Dolphin"), tr("Mantener Atrás", "Hold Back"),
                    row(AutoRules.Guide.LB_BACK).value) {
                    row(AutoRules.Guide.LB_BACK).value = it; AutoRules.setGuide(a, AutoRules.Guide.LB_BACK, it)
                }
            }
            SwitchRow(tr("Aviso al transmitir", "Notice when streaming"), streamTip, tr("Panel, menú y capturas en Artemis o Moonlight", "Panel, menu and screenshots in Artemis or Moonlight")) {
                streamTip = it; AutoRules.setStreamTip(a, it)
            }
            Hint(tr("Transmitiendo (Artemis, Moonlight) la guía va al PC: el panel se abre con L3 + R3, el menú de la app con Start + Select y las capturas con M dos veces (DS5) o manteniendo L3 + R3.",
                "While streaming (Artemis, Moonlight) the guide goes to the PC: open the panel with L3 + R3, the app menu with Start + Select, and take screenshots with M twice (DS5) or by holding L3 + R3."))
        }
    }
}

/**
 * Tocar la guía en cada juego o emulador instalado: Atrás (lo normal), nada, o pasarla al juego (el emulador recibe la
 * guía real y la asigna como tecla de atajos; mantenerla sigue abriendo el panel y Guía + RB sigue siendo captura).
 */
@Composable
private fun GuideTapPanel(a: MainActivity) {
    val pm = a.packageManager
    val apps = remember {
        GAME_APPS.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { pkg to pm.getApplicationLabel(it).toString() }
        }
    }
    if (apps.isEmpty()) return
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Tocar la guía en…", "Tapping guide in…"), tr("Mantener sigue abriendo el panel", "Holding still opens the panel")) {
            apps.forEach { (pkg, label) ->
                var tap by remember(pkg) { mutableStateOf(AutoRules.guideTap(a, pkg)) }
                Text(label, fontSize = 15.sp)
                ChoiceChips(AutoRules.GuideTap.entries.map { it to it.label }, tap) { tap = it; AutoRules.setGuideTap(a, pkg, it) }
                if (tap == AutoRules.GuideTap.PASS) {
                    // Capturas nítidas con shaders como BFI o CRT beam simulator: que las saque el emulador.
                    var byGame by remember(pkg) { mutableStateOf(AutoRules.shotByGame(a, pkg)) }
                    SwitchRow(tr("Guía + RB: captura del emulador", "Guide + RB: emulator screenshot"), byGame,
                        tr("Sin filtros (BFI, CRT). En RetroArch asigna «Captura de pantalla» a RB", "Without filters (BFI, CRT). In RetroArch bind “Screenshot” to RB")) {
                        byGame = it; AutoRules.setShotByGame(a, pkg, it)
                    }
                }
            }
            Hint(tr("«Pasar al juego»: el emulador recibe la guía. En RetroArch, en Teclas rápidas: «Tecla para activar teclas rápidas» = la guía y «Mostrar menú» = otro botón (queda Guía + ese botón).",
                "\u201cPass to the game\u201d: the emulator gets the guide. In RetroArch Hotkeys: \u201cHotkey enable\u201d = the guide and \u201cMenu toggle\u201d = another button (it becomes Guide + that button)."))
        }
    }
}

/** Juegos y emuladores conocidos: los que estén instalados salen en "Tocar la guía en…". */
private val GAME_APPS = listOf(
    "com.retroarch", "com.retroarch.aarch64", "dev.eden.eden_emulator", "org.azahar_emu.azahar", "org.dolphinemu.dolphinemu",
    "xyz.aethersx2.android", "info.cemu.cemu", "me.magnum.melondualds", "me.magnum.melonds", "org.vita3k.emulator",
    "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "com.github.stenzek.duckstation", "app.gamenative", "com.winlator",
    "com.dishii.soh", "com.dishii.mm", "dev.twilitrealm.dusk", "org.citra.emu", "io.github.lime3ds.android",
)

@Composable
private fun OtherShortcutsPanel(a: MainActivity) {
    var chord by remember { mutableStateOf(AutoRules.gamePanel(a)) }
    var onlyStream by remember { mutableStateOf(AutoRules.chordOnlyStreaming(a)) }
    var streamBack by remember { mutableStateOf(AutoRules.streamBack(a)) }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Sin botón guía", "Without a guide button")) {
            ShortcutRow(listOf("L3", "R3"), tr("Tocar · mantener: captura", "Tap · hold: screenshot"), tr("Panel de juego", "Game panel"), chord) {
                chord = it; AutoRules.setGamePanel(a, it)
            }
            // Con la guía, L3 + R3 sobra (y el primer stick llega al juego): se puede dejar solo para transmitir.
            if (chord) ChoiceChips(listOf(false to tr("En todas partes", "Everywhere"), true to tr("Solo transmitiendo", "Only when streaming")), onlyStream) {
                onlyStream = it; AutoRules.setChordOnlyStreaming(a, it)
            }
            ShortcutRow(listOf("Start", "Select"), tr("Transmitiendo (Artemis, Moonlight)", "While streaming (Artemis, Moonlight)"), tr("Menú de la app (Atrás)", "App menu (Back)"), streamBack) {
                streamBack = it; AutoRules.setStreamBack(a, it)
            }
        }
    }
}

@Composable
private fun TabletButtonsPanel(a: MainActivity) {
    var swap by remember { mutableStateOf(AutoRules.swapVolume(a)) }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Botones de la tablet", "Tablet buttons")) {
            SwitchRow(tr("Invertir el volumen", "Swap volume buttons"), swap) {
                swap = it; AutoRules.setSwapVolume(a, it)
            }
        }
    }
}

/**
 * Un atajo: los botones dibujados (Guía + RB…), cómo se usan (tocar, mantener) y qué hacen, con su interruptor. Sin
 * botones: variante del atajo de arriba (↳).
 */
@Composable
private fun ShortcutRow(keys: List<String>, how: String?, action: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val editable = LocalEditable.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = editable) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.widthIn(min = 132.dp), verticalAlignment = Alignment.CenterVertically) {
            if (keys.isEmpty()) Text("↳", Modifier.padding(start = 14.dp), fontSize = 20.sp, color = c.onSurfaceVariant)
            keys.forEachIndexed { i, k ->
                if (i > 0) Text(" + ", fontSize = 13.sp, color = c.onSurfaceVariant)
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.secondaryContainer).padding(horizontal = 9.dp, vertical = 4.dp)) {
                    Text(k, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSecondaryContainer)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(action, fontSize = 15.sp, color = if (editable) c.onSurface else c.onSurface.copy(alpha = 0.5f))
            if (how != null) Text(how, fontSize = 12.sp, color = c.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = editable)
    }
}
