package io.github.sudodiego.g9control

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp

/**
 * Página "Ajustes": la app y sus permisos en las dos secciones; lo del G9 (dibujo, datos, valores de fábrica, consola)
 * solo dentro de la sección G9, que en la de Tablet no hace falta.
 */
@Composable
fun DiagnosticsPage(s: AppState, a: MainActivity) {
    // Detalles técnicos ocultos: como el modo desarrollador de Android, aparecen al tocar la versión 5 veces.
    var dev by remember { mutableStateOf(Prefs.devTools(a)) }
    var taps by remember { mutableIntStateOf(0) }
    TwoPane(
        first = {
            Panel(tr("Aplicación", "App")) {
                Text(tr("Idioma", "Language"), fontSize = 14.sp)
                ChoiceChips(AppLang.entries.map { it to it.label }, Lang.choice) { Lang.set(a, it) }
                Text(tr("Tema", "Theme"), fontSize = 14.sp)
                ChoiceChips(AppTheme.entries.map { it to it.label }, ThemeState.theme) {
                    ThemeState.theme = it; Prefs.setTheme(a, it)
                }
                OutlinedButton(onClick = { s.showSetup = true }) { Text(tr("Ver la configuración inicial", "Show quick setup")) }
                val version = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
                Row(
                    Modifier.fillMaxWidth().clickable {
                        taps++
                        if (taps >= 5 && !dev) { dev = true; Prefs.setDevTools(a, true); s.status = tr("Herramientas de desarrollo activadas.", "Developer tools enabled.") }
                    }.padding(vertical = 6.dp)
                ) {
                    Text(tr("Versión", "Version"), Modifier.weight(1f), fontSize = 14.sp)
                    Text(version, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Izquierda: la app; derecha: lo del G9 y las herramientas de desarrollo. En la sección Tablet no sale
            // nada del G9: los permisos pasan a la derecha.
            if (s.section == Section.G9) PermissionsPanel(s, a)
        },
        second = {
            if (s.section == Section.TABLET) PermissionsPanel(s, a)
            if (s.section == Section.G9) CompositionLocalProvider(LocalEditable provides true) {
                Panel(tr("Control G9", "G9 controller")) {
                    Text(tr("Estilo del dibujo", "Drawing style"), fontSize = 14.sp)
                    ChoiceChips(ControllerSkin.entries.map { it to it.label }, SkinState.skin) {
                        SkinState.skin = it; Prefs.setSkin(a, it)
                    }
                    Text(tr("Dibujo del control", "Controller drawing"), fontSize = 14.sp)
                    ChoiceChips(listOf(false to tr("A la izquierda", "On the left"), true to tr("Al centro", "Centered")), s.centered) {
                        s.centered = it; Prefs.setCentered(a, it)
                    }
                    SwitchRow(tr("Abrir la app al conectar el G9", "Open the app when the G9 is connected"), s.openOnConnect) {
                        s.openOnConnect = it; Prefs.setOpenOnConnect(a, it)
                    }
                    // El identificador solo con las herramientas de desarrollo.
                    s.devInfo.filter { dev || it.first !in setOf("Identificador", "Identifier") }.forEach { (k, v) ->
                        Row {
                            Text(k, Modifier.width(140.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (s.section == Section.G9) ResetPanel(s, a)
            if (dev && s.section == Section.G9) {
                Panel(tr("Detalles técnicos", "Technical details"), trailing = {
                    TextButton(onClick = { dev = false; taps = 0; Prefs.setDevTools(a, false) }) { Text(tr("Ocultar", "Hide")) }
                }) {
                    Mono(s.deviceText)
                    s.mode?.let { Mono(tr("Modo leído: ${it.main} (submodo ${it.sub})", "Mode read: ${it.main} (submode ${it.sub})")) }
                    s.inputDevice?.let { Mono(tr("Entrada: $it", "Input: $it")) }
                    // Solo si hay algo más que el G9 (si no, repite lo de arriba).
                    if (s.allDevicesText.trim() != s.deviceText.trim()) Mono(s.allDevicesText)
                }
                ConsolePanel(s, a)
            }
            if (dev) {
                Panel(tr("Registro", "Log"), trailing = {
                    IconButton(onClick = { s.refreshLog() }) { Icon(Icons.Outlined.Refresh, tr("Actualizar", "Refresh")) }
                }) {
                    Mono(s.logText.ifEmpty { "—" })
                }
            }
        }
    )
}

@Composable
private fun ResetPanel(s: AppState, a: MainActivity) {
    var ask by remember { mutableStateOf(false) }
    Panel(tr("Valores de fábrica del G9", "G9 factory settings")) {
        OutlinedButton(onClick = { ask = true }, enabled = s.connected && !s.busy) {
            Text(tr("Restaurar valores de fábrica", "Restore factory settings"), color = MaterialTheme.colorScheme.error)
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(tr("¿Restaurar valores de fábrica?", "Restore factory settings?")) },
        text = {
            Text(
                tr(
                    "Se restauran las zonas muertas (sticks 10 %, gatillos 5–95 %), la vibración (75 %) y las luces, se desactivan el gatillo rápido y las " +
                        "opciones de la cruceta, A/B y X/Y vuelven a su lugar, y los botones traseros quedan sin asignar (se borran sus macros).\n\n" +
                        "Los perfiles guardados en la tablet no se tocan: puedes volver a aplicar uno después.",
                    "Dead zones (sticks 10%, triggers 5–95%), vibration (75%) and lights are restored, hair trigger and D-pad options are " +
                        "turned off, A/B and X/Y go back to normal, and the back buttons are unassigned (their macros are deleted).\n\n" +
                        "Profiles saved on the tablet aren't touched: you can apply one again afterwards."
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { ask = false; a.factoryReset() }) {
                Text(tr("Restaurar", "Restore"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text(tr("Cancelar", "Cancel")) } }
    )
}

@Composable
private fun Mono(text: String) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
private fun ConsolePanel(s: AppState, a: MainActivity) {
    var hex by remember { mutableStateOf("08 02 05 00") }
    var sum by remember { mutableStateOf(true) }
    Panel(tr("Consola", "Console"), tr("Envía bytes al control tal cual. No hace falta para el uso normal.", "Sends raw bytes to the controller. Not needed for normal use.")) {
        OutlinedTextField(
            hex, { hex = it }, Modifier.fillMaxWidth(), label = { Text(tr("Bytes en hex", "Hex bytes")) },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            singleLine = true
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = sum, onCheckedChange = { sum = it })
            Text(tr("Agregar checksum", "Add checksum"), Modifier.weight(1f))
            Button(onClick = { a.console(hex, sum) }) { Text(tr("Enviar", "Send")) }
        }
        if (s.consoleOut.isNotEmpty()) Mono(s.consoleOut)
    }
}

/**
 * Cómo se ven los avisos de cambio de modo, ordenados por cuándo aparecen: al abrir un juego, al
 * salir de él y cuando la app cambia el modo sola. Cada uno con su bloqueo en una sola elección.
 */
/**
 * "Juego": lo que se usa dentro de los juegos, con cualquier control: el panel de juego, las capturas, el monitor de
 * rendimiento y Game Assistant.
 */
@Composable
fun GamePanelPage(s: AppState, a: MainActivity) {
    TwoPane(
        first = { GamePanelSettings(s, a) },
        second = { GamesHzPanel(a); GameAssistantSettings(s, a) }
    )
}

/** Frecuencia para todos los juegos: sin esto el sistema la mueve entre 30 y 120 (en Ahorro, a 30). */
@Composable
private fun GamesHzPanel(a: MainActivity) {
    var hz by remember { mutableIntStateOf(RefreshRate.forGames(a) ?: 0) }
    // Hasta 120 Hz sin Shizuku; 144 y 165 solo con él (hay que subir el máximo del sistema).
    val (ok, locked) = RefreshRate.supported(a).partition { RefreshRate.available(a, it) || it == hz }
    CompositionLocalProvider(LocalEditable provides true) {
        Panel(tr("Frecuencia en juegos", "Refresh rate in games"),
            tr("Fija la pantalla en todos los juegos. Cada juego puede tener la suya desde el panel.", "Locks the screen in every game. Each game can have its own from the panel.")) {
            ChoiceChips(listOf(0 to tr("Sistema", "System")) + ok.map { it to "$it Hz" }, hz) {
                hz = it; RefreshRate.setForGames(a, it.takeIf { v -> v > 0 })
            }
            Text(if (hz == 0) tr("Sistema: entre 30 y 120 Hz según el juego y el modo (en Ahorro baja a 30).", "System: 30 to 120 Hz depending on the game and mode (Saver drops to 30).")
                else tr("Siempre $hz Hz, en cualquier modo.", "Always $hz Hz, in any mode."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (locked.isNotEmpty()) Hint(tr("${locked.joinToString(" y ")} Hz necesitan Shizuku (pasan del máximo del sistema).",
                "${locked.joinToString(" and ")} Hz need Shizuku (above the system maximum)."))
        }
    }
}

@Composable
fun NoticesPanel(s: AppState, a: MainActivity) {
    var small by remember { mutableStateOf(AutoRules.guideSmall(a)) }
    var exitBig by remember { mutableStateOf(AutoRules.exitBig(a)) }
    var big by remember { mutableStateOf(lockOf(AutoRules.guideBlock(a), AutoRules.blockSticks(a))) }
    var mini by remember { mutableStateOf(lockOf(AutoRules.miniBlock(a), AutoRules.miniBlockSticks(a))) }
    var pos by remember { mutableStateOf(AutoRules.noticePos(a)) }
    var label by remember { mutableStateOf(AutoRules.showLabel(a)) }
    var guidePos by remember { mutableStateOf(AutoRules.guidePos(a)) }
    var anim by remember { mutableStateOf(AutoRules.noticeAnim(a)) }
    val on = s.autoServiceOn
    // Estas opciones son de la app, no del control: siempre se pueden cambiar.
    CompositionLocalProvider(LocalEditable provides true) {
        Panel(tr("Avisos de cambio de modo", "Mode change notices")) {
            NoticeBox(tr("Al abrir un juego", "When opening a game"), tr("Pide mantener Vista + Menú si el juego usa otro modo.", "Asks you to hold View + Menu if the game uses another mode."),
                if (on) { { AutoRules.preview(a, if (small) "mini" else "big") } } else null) {
                ChoiceChips(listOf(false to tr("Tarjeta en el centro", "Card in the center"), true to tr("Aviso pequeño", "Small notice")), small) {
                    small = it; AutoRules.setGuideSmall(a, it)
                }
                if (small) Hint(tr("Usa el mismo bloqueo y posición que el aviso al salir de un juego.", "Uses the same blocking and position as the notice when leaving a game."))
                else LockChips(big) { big = it; AutoRules.setGuideBlock(a, it > 0); AutoRules.setBlockSticks(a, it > 1) }
            }
            NoticeBox(tr("Al salir de un juego", "When leaving a game"), tr("Recuerda volver a Android.", "Reminds you to return to Android."),
                if (on) { { AutoRules.preview(a, if (exitBig) "big" else "mini") } } else null,
                side = { PosGrid(guidePos) { guidePos = it; AutoRules.setGuidePos(a, it) } }) {
                ChoiceChips(listOf(true to tr("Tarjeta en el centro", "Card in the center"), false to tr("Aviso pequeño", "Small notice")), exitBig) {
                    exitBig = it; AutoRules.setExitBig(a, it)
                }
                if (exitBig) Hint(tr("Usa el mismo bloqueo que la tarjeta al abrir un juego.", "Uses the same blocking as the card when opening a game."))
                else LockChips(mini) { mini = it; AutoRules.setMiniBlock(a, it > 0); AutoRules.setMiniBlockSticks(a, it > 1) }
                // Por qué: en X-Input y DS5 el G9 no tiene canal de configuración (solo vuelve con Vista + Menú).
                Hint(tr("¿Por qué volver? Solo en Android la app puede cambiar el modo sola en el próximo juego y ajustar luces, sticks o traseros. Si no lo necesitas, puedes quedarte en DS5.",
                    "Why go back? Only in Android can the app switch the mode by itself for the next game and adjust lights, sticks or back buttons. If you don't need that, you can stay in DS5."))
            }
            NoticeBox(tr("Al cambiar de modo", "When switching mode"), tr("Etiqueta con el modo nuevo cuando la app lo cambia. No bloquea nada.", "Label with the new mode when the app switches it. Doesn't block anything."),
                if (on && label) { { AutoRules.preview(a, "banner") } } else null,
                side = if (label) { { PosGrid(pos) { pos = it; AutoRules.setNoticePos(a, it) } } } else null) {
                SwitchRow(tr("Mostrar la etiqueta", "Show the label"), label) { label = it; AutoRules.setShowLabel(a, it) }
            }
            if ((!small && big > 0) || mini > 0)
                Hint(tr("Para quitar un aviso que bloquea sin cambiar de modo: mantén presionada la pantalla 2 s.", "To dismiss a blocking notice without switching mode: press and hold the screen for 2 s."))
            Text(tr("Animación", "Animation"), fontSize = 14.sp)
            ChoiceChips(NoticeAnim.entries.map { it to it.label }, anim) { anim = it; AutoRules.setNoticeAnim(a, it) }
            if (!on) Hint(tr("Para ver los avisos hay que activar el servicio de accesibilidad (Ajustes → Permisos).", "To see the notices, turn on the accessibility service (Settings → Permissions)."))
        }
    }
}

/**
 * Panel de juego: el botón guía (cualquier control: Legion en el G9, Xbox, PS, Home…) y L3 + R3, cómo se ve el aviso
 * de captura y el monitor de rendimiento. Sirve con cualquier control; lo propio del G9 (M dos veces) está en su sección.
 */
@Composable
private fun GamePanelSettings(s: AppState, a: MainActivity) {
    var on by remember { mutableStateOf(AutoRules.gamePanel(a)) }
    var guide by remember { mutableStateOf(AutoRules.legionKeys(a)) }
    var burst by remember { mutableStateOf(AutoRules.burstShots(a)) }
    var shizukuTile by remember { mutableStateOf(AutoRules.shizukuTile(a)) }
    var shotPos by remember { mutableStateOf(AutoRules.shotPos(a)) }
    var shotSound by remember { mutableStateOf(AutoRules.shotSound(a)) }
    var shotAnim by remember { mutableStateOf(AutoRules.shotAnim(a)) }
    var perfHud by remember { mutableStateOf(AutoRules.perfHud(a)) }
    var perfPos by remember { mutableStateOf(AutoRules.perfPos(a)) }
    var perfFull by remember { mutableStateOf(AutoRules.perfFull(a)) }
    CompositionLocalProvider(LocalEditable provides true) {
        Panel(tr("Panel de juego", "Game panel"), tr("Un menú para usar en cualquier app sin soltar el control, con cualquier control.",
            "A menu to use in any app without letting go of the controller, with any controller.")) {
            SwitchRow(tr("Botón guía", "Guide button"), guide,
                tr("El del centro del control (Legion en el G9, Xbox, PS u Home). Tocar: Atrás. Mantener: panel de juego. Con RB: captura. Con LB: mantener Atrás (con Shizuku). Transmitiendo desde el PC (Artemis, Moonlight…) pasa al PC.",
                    "The one in the middle of the controller (Legion on the G9, Xbox, PS or Home). Tap: Back. Hold: game panel. With RB: screenshot. With LB: hold Back (with Shizuku). While streaming from a PC (Artemis, Moonlight…) it goes to the PC.")) {
                guide = it; AutoRules.setLegionKeys(a, it)
            }
            SwitchRow(tr("L3 + R3", "L3 + R3"), on, tr("Para controles sin botón guía. Tocar: abre el panel. Mantener: captura.", "For controllers without a guide button. Tap: opens the panel. Hold: screenshot.")) {
                on = it; AutoRules.setGamePanel(a, it)
            }
            SwitchRow(tr("Ráfaga de capturas", "Screenshot burst"), burst,
                tr("Guía + RB y seguir manteniendo RB: una captura por segundo hasta soltarlo.", "Guide + RB and keep holding RB: one screenshot per second until you let go.")) {
                burst = it; AutoRules.setBurstShots(a, it)
            }
            SwitchRow(tr("Recordar activar Shizuku", "Remind to start Shizuku"), shizukuTile,
                tr("Si Shizuku está instalado pero apagado, el panel muestra un mosaico para activarlo.", "If Shizuku is installed but off, the panel shows a tile to start it.")) {
                shizukuTile = it; AutoRules.setShizukuTile(a, it)
            }
            NoticeBox(tr("Aviso de captura", "Screenshot notice"), tr("Miniatura que entra desde el borde.", "Thumbnail that slides in from the edge."),
                if (s.autoServiceOn) { { AutoRules.preview(a, "shot") } } else null,
                side = { PosGrid(shotPos) { shotPos = it; AutoRules.setShotPos(a, it) } }) {
                Text(tr("Sonido", "Sound"), fontSize = 14.sp)
                ChoiceChips(ShotSound.entries.map { it to it.label }, shotSound) { shotSound = it; AutoRules.setShotSound(a, it) }
                Text(tr("Animación", "Animation"), fontSize = 14.sp)
                ChoiceChips(NoticeAnim.entries.map { it to it.label }, shotAnim) { shotAnim = it; AutoRules.setShotAnim(a, it) }
            }
            // Monitor de rendimiento: se enciende desde el panel; aquí también, y su posición.
            NoticeBox(tr("Monitor de rendimiento", "Performance monitor"),
                tr("Barra con FPS, CPU, GPU y batería sobre el juego. Los FPS necesitan Shizuku.", "Bar with FPS, CPU, GPU and battery over the game. FPS needs Shizuku."),
                null, side = { PosGrid(perfPos) { perfPos = it; AutoRules.setPerfPos(a, it) } }) {
                SwitchRow(tr("Mostrar", "Show"), perfHud) { perfHud = it; AutoRules.setPerfHud(a, it) }
                SwitchRow(tr("Más datos", "More data"), perfFull,
                    tr("Segunda línea: watts, tiempo de batería, Hz y aviso de calor.", "Second line: watts, battery time, Hz and heat warning.")) {
                    perfFull = it; AutoRules.setPerfFull(a, it)
                }
            }
            if (!s.autoServiceOn) Hint(tr("Necesita el servicio de accesibilidad (Ajustes → Permisos).", "Needs the accessibility service (Settings → Permissions)."))
        }
    }
}

/**
 * Permisos de la app en un solo lugar: el servicio de accesibilidad (lo usan muchas cosas, no solo el cambio por app),
 * Shizuku y el permiso de adb para la pantalla 16:9. Cada uno con su estado, para qué sirve y cómo activarlo.
 */
@Composable
private fun PermissionsPanel(s: AppState, a: MainActivity) {
    LaunchedEffect(Unit) { Shell.init(a); Shell.refresh() }
    val shizuku = Shell.state
    Panel(tr("Permisos", "Permissions")) {
        PermRow(
            tr("Servicio de accesibilidad", "Accessibility service"), s.autoServiceOn,
            tr("Cambio automático por juego, panel de juego, capturas, botón guía, volumen invertido, bypass automático y el monitor.",
                "Automatic switching per game, game panel, screenshots, guide button, swapped volume, automatic bypass and the monitor.")
        ) {
            Hint(tr("Busca «TBF Companion» en la lista y actívalo. Si sale «Ajuste restringido»: Ajustes → Apps → TBF Companion → ⋮ → Permitir ajustes restringidos.",
                "Find “TBF Companion” in the list and turn it on. If “Restricted setting” appears: Settings → Apps → TBF Companion → ⋮ → Allow restricted settings."))
            Button(onClick = { AutoRules.openServiceSettings(a) }) { Text(tr("Abrir accesibilidad", "Open accessibility")) }
        }
        PermRow(
            tr("Shizuku (opcional)", "Shizuku (optional)"), shizuku == Shell.State.READY,
            tr("Lo demás funciona sin él. Solo para: bypass y límite de carga, cambiar el modo desde el panel, ocultar Game Assistant, FPS del monitor, 144 y 165 Hz, apagar la tablet con un monitor y arrastrar con el mouse. Hay que iniciarlo tras cada reinicio.",
                "Everything else works without it. Only for: bypass and charge limit, changing the mode from the panel, hiding Game Assistant, monitor FPS, 144 and 165 Hz, turning the tablet off with a monitor and dragging with the mouse. Start it after every reboot."),
            off = if (shizuku == Shell.State.NO_PERMISSION) tr("Falta el permiso", "Permission missing") else tr("No está iniciado", "Not started")
        ) {
            val launch = remember { Shell.launchIntent(a) }
            when {
                shizuku == Shell.State.NO_PERMISSION -> Button(onClick = { Shell.requestPermission() }) { Text(tr("Dar permiso a TBF Companion", "Grant permission to TBF Companion")) }
                launch != null -> Button(onClick = { runCatching { a.startActivity(launch) } }) { Text(tr("Abrir Shizuku", "Open Shizuku")) }
                else -> Hint(tr("Shizuku no está instalado (shizuku.rikka.app).", "Shizuku isn't installed (shizuku.rikka.app)."))
            }
        }
        PermRow(
            tr("Permiso de pantalla", "Screen permission"), Aspect.canWrite(a),
            tr("Pantalla 16:9, bloquear gestos y el brillo automático en juegos. Se da una vez desde un computador.",
                "16:9 screen, gesture lock and auto brightness in games. Granted once from a computer."),
            off = tr("Falta", "Missing")
        ) {
            Hint(Aspect.GRANT_COMMAND)
        }
        // Avisos de Inicio si falta algo (también se apagan con "No avisar" en el propio aviso).
        var warnService by remember { mutableStateOf(AutoRules.warnService(a)) }
        var warnShizuku by remember { mutableStateOf(AutoRules.warnShizuku(a)) }
        CompositionLocalProvider(LocalEditable provides true) {
            SwitchRow(tr("Avisar en Inicio si el servicio está apagado", "Warn on Home if the service is off"), warnService) {
                warnService = it; AutoRules.setWarnService(a, it)
            }
            SwitchRow(tr("Avisar en Inicio si Shizuku no está iniciado", "Warn on Home if Shizuku isn't started"), warnShizuku) {
                warnShizuku = it; AutoRules.setWarnShizuku(a, it)
            }
        }
    }
}

/** Un permiso: nombre y estado en una fila, para qué sirve debajo y, si falta, cómo darlo. */
@Composable
private fun PermRow(title: String, ok: Boolean, desc: String, off: String = tr("Apagado", "Off"), fix: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(if (ok) tr("Activo", "On") else off, fontSize = 14.sp, color = if (ok) c.primary else c.error)
        }
        Text(desc, fontSize = 13.sp, color = c.onSurfaceVariant)
        if (!ok) fix()
    }
}

/** Bloqueo en un número: 0 nada, 1 botones y pantalla, 2 también sticks y gatillos. */
private fun lockOf(block: Boolean, sticks: Boolean) = if (!block) 0 else if (!sticks) 1 else 2

/** Qué bloquea un aviso mientras está en pantalla, en una sola elección. */
@Composable
private fun LockChips(level: Int, onPick: (Int) -> Unit) {
    Text(tr("Mientras está en pantalla, bloquear", "While on screen, block"), fontSize = 14.sp)
    ChoiceChips(listOf(0 to tr("Nada", "Nothing"), 1 to tr("Botones", "Buttons"), 2 to tr("Todo", "Everything")), level, onPick)
    when (level) {
        1 -> Hint(tr("Botones y pantalla táctil. Sticks y gatillos siguen llegando al juego.", "Buttons and touchscreen. Sticks and triggers still reach the game."))
        2 -> Hint(tr("Botones, pantalla, sticks y gatillos. El juego pierde el foco y puede pausarse.", "Buttons, screen, sticks and triggers. The game loses focus and may pause."))
    }
}

/**
 * Recuadro de un aviso: arriba el título, qué es, el botón "Ver" y, a la derecha, su posición; debajo, a todo el
 * ancho, sus opciones (al lado de la posición quedaban angostas y se partían en varias líneas).
 */
@Composable
private fun NoticeBox(
    title: String, desc: String, onPreview: (() -> Unit)?, side: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (onPreview != null) TextButton(onClick = onPreview) { Text(tr("Ver", "Preview")) }
                    }
                    Text(desc, fontSize = 13.sp, color = c.onSurfaceVariant)
                }
                if (side != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Posición", "Position"), fontSize = 13.sp, color = c.onSurfaceVariant)
                    side()
                }
            }
            content()
        }
    }
}

/** Posición en la pantalla: una pantalla en miniatura con 9 lugares; el elegido se marca. */
@Composable
private fun PosGrid(sel: NoticePos, onPick: (NoticePos) -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = c.surface, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.width(170.dp).aspectRatio(16f / 10f)
    ) {
        Column(Modifier.padding(8.dp)) {
            for (v in -1..1) Row(Modifier.weight(1f)) {
                for (h in -1..1) {
                    val p = NoticePos.entries.first { it.h == h && it.v == v }
                    val on = p == sel
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(MaterialTheme.shapes.small)
                            .background(if (on) c.primary else c.surfaceContainerHighest)
                            .clickable(onClickLabel = p.label) { onPick(p) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) Box(Modifier.size(width = 28.dp, height = 8.dp).clip(CircleShape).background(c.onPrimary))
                    }
                }
            }
        }
    }
}
