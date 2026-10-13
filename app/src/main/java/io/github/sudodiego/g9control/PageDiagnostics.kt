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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
 * Ajustes (su propia sección): la app (idioma, tema, fondo, avisos) y sus permisos, con lo que necesita cada uno.
 * Lo del G9 está en sus Preferencias.
 */
@Composable
fun DiagnosticsPage(s: AppState, a: MainActivity) {
    // Herramientas de desarrollo: como en Android, aparecen al tocar la versión 5 veces.
    var dev by remember { mutableStateOf(Prefs.devTools(a)) }
    var taps by remember { mutableIntStateOf(0) }
    var anim by remember { mutableStateOf(AutoRules.noticeAnim(a)) }
    var loop by remember { mutableStateOf(Prefs.backgroundLoop(a)) }
    TwoPane(
        first = {
            Panel(tr("Aplicación", "App")) {
                Text(tr("Idioma", "Language"), fontSize = 14.sp)
                ChoiceChips(AppLang.entries.map { it to it.label }, Lang.choice) { Lang.set(a, it) }
                Text(tr("Tema", "Theme"), fontSize = 14.sp)
                ChoiceChips(AppTheme.entries.map { it to it.label }, ThemeState.theme) {
                    ThemeState.theme = it; Prefs.setTheme(a, it)
                }
                Text(tr("Color de la app", "App color"), fontSize = 14.sp)
                AccentPicker(ThemeState.appAccent) { ThemeState.appAccent = it; Prefs.setAccent(a, "app", it) }
                Text(tr("Fondo", "Background"), fontSize = 14.sp)
                ChoiceChips(AppBackground.entries.map { it to it.label }, ThemeState.background) {
                    if (it == AppBackground.IMAGE && ThemeState.backgroundUri == null) a.pickBackground()
                    else { ThemeState.background = it; Prefs.setBackground(a, it) }
                }
                if (ThemeState.background == AppBackground.IMAGE) {
                    OutlinedButton(onClick = { a.pickBackground() }) { Text(tr("Cambiar imagen", "Change image")) }
                    SliderRow(tr("Desenfoque", "Blur"), ThemeState.backgroundBlur, 0..100) {
                        ThemeState.backgroundBlur = it; Prefs.setBackgroundBlur(a, it)
                    }
                    SwitchRow(tr("Repetir el GIF", "Loop the GIF"), loop) { loop = it; ThemeState.backgroundLoop = it; Prefs.setBackgroundLoop(a, it) }
                }
                if (ThemeState.background == AppBackground.WALLPAPER) {
                    SliderRow(tr("Oscurecer", "Dim"), ThemeState.backgroundDim, 0..100) {
                        ThemeState.backgroundDim = it; Prefs.setBackgroundDim(a, it)
                    }
                }
                Text(tr("Animación de los avisos", "Notice animation"), fontSize = 14.sp)
                ChoiceChips(NoticeAnim.entries.map { it to it.label }, anim) { anim = it; AutoRules.setNoticeAnim(a, it); AutoRules.setShotAnim(a, it) }
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
        },
        second = {
            PermissionsPanel(s, a)
            if (dev) {
                Panel(tr("Registro", "Log"), trailing = {
                    Row {
                        IconButton(onClick = { s.refreshLog() }) { Icon(Icons.Outlined.Refresh, tr("Actualizar", "Refresh")) }
                        TextButton(onClick = { dev = false; taps = 0; Prefs.setDevTools(a, false) }) { Text(tr("Ocultar", "Hide")) }
                    }
                }) {
                    Mono(s.logText.ifEmpty { "—" })
                }
            }
        }
    )
}

/** Preferencias del G9: cómo se dibuja, abrir la app al conectarlo, sus datos, valores de fábrica y (desarrollo) la consola. */
@Composable
fun G9PrefsPage(s: AppState, a: MainActivity) {
    val dev = Prefs.devTools(a)
    TwoPane(
        first = {
            CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
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
            ResetPanel(s, a)
        },
        second = {
            // Lo que se guarda en el G9 (intercambiar botones, cruceta) y M dos veces, antes en la página de botones.
            ControlOptionsPanel(s, a)
            TouchpadShotPanel(s, a)
            if (dev) {
                Panel(tr("Detalles técnicos", "Technical details")) {
                    Mono(s.deviceText)
                    s.mode?.let { Mono(tr("Modo leído: ${it.main} (submodo ${it.sub})", "Mode read: ${it.main} (submode ${it.sub})")) }
                    s.inputDevice?.let { Mono(tr("Entrada: $it", "Input: $it")) }
                    if (s.allDevicesText.trim() != s.deviceText.trim()) Mono(s.allDevicesText)
                }
                ConsolePanel(s, a)
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
        // Capturas, monitor y frecuencia los hace el servicio de accesibilidad; Game Assistant tiene sus propios permisos.
        first = { Gate(s.autoServiceOn) { GamePanelSettings(s, a) } },
        second = { Gate(s.autoServiceOn) { GamesHzPanel(a) }; GameAssistantSettings(s, a); RaProxyPanel(s, a) }
    )
}

/** Frecuencia para todos los juegos: sin esto el sistema la mueve entre 30 y 120 (en Ahorro, a 30). */
@Composable
private fun GamesHzPanel(a: MainActivity) {
    var hz by remember { mutableIntStateOf(RefreshRate.forGames(a) ?: 0) }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Frecuencia en juegos", "Refresh rate in games"),
            tr("Cada juego puede tener la suya desde el panel", "Each game can have its own from the panel")) {
            ChoiceChips(listOf(0 to tr("Sistema", "System")) + RefreshRate.supported(a).map { it to "$it Hz" }, hz) {
                hz = it; RefreshRate.setForGames(a, it.takeIf { v -> v > 0 })
            }
            Text(if (hz == 0) tr("Sistema: de 30 a 120 Hz según el juego.", "System: 30 to 120 Hz depending on the game.")
                else tr("Siempre $hz Hz en los juegos.", "Always $hz Hz in games."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val on = s.autoServiceOn
    // Estas opciones son de la app, no del control: siempre se pueden cambiar.
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Avisos de cambio de modo", "Mode change notices")) {
            NoticeBox(tr("Al abrir un juego", "When opening a game"), tr("Si el juego usa otro modo", "If the game uses another mode"),
                if (on) { { AutoRules.preview(a, if (small) "mini" else "big") } } else null) {
                ChoiceChips(listOf(false to tr("Tarjeta en el centro", "Card in the center"), true to tr("Aviso pequeño", "Small notice")), small) {
                    small = it; AutoRules.setGuideSmall(a, it)
                }
                if (small) Hint(tr("Mismo bloqueo y posición que al salir.", "Same blocking and position as when leaving."))
                else LockChips(big) { big = it; AutoRules.setGuideBlock(a, it > 0); AutoRules.setBlockSticks(a, it > 1) }
            }
            NoticeBox(tr("Al salir de un juego", "When leaving a game"), tr("Volver a Android", "Back to Android"),
                if (on) { { AutoRules.preview(a, if (exitBig) "big" else "mini") } } else null,
                side = { PosGrid(guidePos) { guidePos = it; AutoRules.setGuidePos(a, it) } }) {
                ChoiceChips(listOf(true to tr("Tarjeta en el centro", "Card in the center"), false to tr("Aviso pequeño", "Small notice")), exitBig) {
                    exitBig = it; AutoRules.setExitBig(a, it)
                }
                if (exitBig) Hint(tr("Mismo bloqueo que al abrir.", "Same blocking as when opening."))
                else LockChips(mini) { mini = it; AutoRules.setMiniBlock(a, it > 0); AutoRules.setMiniBlockSticks(a, it > 1) }
                // Por qué: en X-Input y DS5 el G9 no tiene canal de configuración (solo vuelve con Vista + Menú).
                Hint(tr("Solo en Android la app cambia el modo sola y ajusta el G9. Si no lo necesitas, quédate en DS5.",
                    "Only in Android can the app switch modes by itself and adjust the G9. If you don't need that, stay in DS5."))
            }
            NoticeBox(tr("Al cambiar de modo", "When switching mode"), tr("Etiqueta con el modo nuevo", "Label with the new mode"),
                if (on && label) { { AutoRules.preview(a, "banner") } } else null,
                side = if (label) { { PosGrid(pos) { pos = it; AutoRules.setNoticePos(a, it) } } } else null) {
                SwitchRow(tr("Mostrar la etiqueta", "Show the label"), label) { label = it; AutoRules.setShowLabel(a, it) }
            }
            if ((!small && big > 0) || mini > 0)
                Hint(tr("Para quitar un aviso que bloquea: mantén la pantalla 2 s.", "To dismiss a blocking notice: hold the screen for 2 s."))
        }
    }
}

/**
 * Capturas y monitor de rendimiento: cómo se ven. Los atajos para sacarlas están en Controles; el panel de juego se
 * abre con el botón guía o L3 + R3.
 */
@Composable
private fun GamePanelSettings(s: AppState, a: MainActivity) {
    var shotPos by remember { mutableStateOf(AutoRules.shotPos(a)) }
    var shotSound by remember { mutableStateOf(AutoRules.shotSound(a)) }
    var perfHud by remember { mutableStateOf(AutoRules.perfHud(a)) }
    var perfPos by remember { mutableStateOf(AutoRules.perfPos(a)) }
    var perfFull by remember { mutableStateOf(AutoRules.perfFull(a)) }
    var perfList by remember { mutableStateOf(AutoRules.perfList(a)) }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Capturas y grabación", "Screenshots and recording")) {
            NoticeBox(tr("Aviso", "Notice"), tr("Miniatura en la esquina", "Thumbnail in the corner"),
                if (s.autoServiceOn) { { AutoRules.preview(a, "shot") } } else null,
                side = { PosGrid(shotPos) { shotPos = it; AutoRules.setShotPos(a, it) } }) {
                Text(tr("Sonido", "Sound"), fontSize = 14.sp)
                ChoiceChips(ShotSound.entries.map { it to it.label }, shotSound) { shotSound = it; AutoRules.setShotSound(a, it) }
            }
            // Grabación: dos, que no van juntas (las maneja el panel; ver la grabación en GamePanel).
            InfoRow(tr("Grabar", "Record"), tr("Guía + LT · Pictures/screenrecorder", "Guide + LT · Pictures/screenrecorder"))
            InfoRow("Shadow Rec", tr("Guía + RT guarda los últimos segundos · Movies/Highlight", "Guide + RT saves the last seconds · Movies/Highlight"))
            Hint(tr("Shadow Rec graba siempre en un juego y guarda lo último al pedirlo. Con él encendido, la grabadora de Legion no funciona. En X-Input LT y RT no sirven: usa los mosaicos del panel.",
                "Shadow Rec always records in a game and saves the latest when asked. While it's on, Legion's recorder doesn't work. In X-Input LT and RT don't work: use the panel tiles."))
            OutlinedButton(onClick = {
                runCatching { a.startActivity(android.content.Intent().setClassName(ZuiGame.PACKAGE, "com.zui.ugame.gamesetting.ui.options.OptionActivity")) }
            }) { Text(tr("Calidad y duración de Shadow Rec", "Shadow Rec quality and length")) }
        }
        Panel(tr("Monitor de rendimiento", "Performance monitor"), trailing = { Switch(perfHud, { perfHud = it; AutoRules.setPerfHud(a, it) }, enabled = LocalGateOk.current) }) {
            NoticeBox(tr("Datos", "Data"), tr("FPS, CPU, GPU y batería", "FPS, CPU, GPU and battery"), null,
                side = { PosGrid(perfPos) { perfPos = it; AutoRules.setPerfPos(a, it) } }) {
                Text(tr("Estilo", "Style"), fontSize = 14.sp)
                ChoiceChips(listOf(false to tr("Barra", "Bar"), true to tr("Lista (MangoHUD)", "List (MangoHUD)")), perfList) {
                    perfList = it; AutoRules.setPerfList(a, it)
                }
                SwitchRow(tr("Más datos", "More data"), perfFull, if (perfList) tr("RAM, watts, tiempo, Hz y calor", "RAM, watts, time, Hz and heat")
                    else tr("Watts, tiempo, Hz y calor", "Watts, time, Hz and heat")) {
                    perfFull = it; AutoRules.setPerfFull(a, it)
                }
            }
        }
    }
}

/**
 * Logros sin internet con RAOfflineProxy (ver [RaProxy]): su estado, qué es, prepararlo (cerrar los emuladores y
 * abrirlo, como pide su guía) y el aviso al abrir un emulador sin internet. Si no está instalado, dónde conseguirlo.
 */
@Composable
private fun RaProxyPanel(s: AppState, a: MainActivity) {
    val installed = remember { RaProxy.installed(a) }
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(RaProxy.activeNow) }
    var hint by remember { mutableStateOf(AutoRules.raProxyHint(a)) }
    var setups by remember { mutableStateOf<List<RaProxy.Setup>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (installed) active = RaProxy.readActive(a)
        // RetroArch y Dolphin: ¿apuntan al proxy y tienen sesión? (con Shizuku; los demás no se pueden leer).
        if (installed && Shell.state == Shell.State.READY) setups = listOf("com.retroarch", "org.dolphinemu.dolphinemu")
            .filter { runCatching { a.packageManager.getApplicationInfo(it, 0) }.isSuccess }.mapNotNull { RaProxy.setup(it) }
    }
    Panel(tr("Logros sin internet", "Offline achievements"), trailing = {
        if (installed) Text(when (active) { true -> tr("Activo", "On"); false -> tr("Apagado", "Off"); null -> "" },
            fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
    }) {
        Text(tr("RAOfflineProxy guarda los logros de RetroAchievements sin internet y los envía al volver (modo casual).",
            "RAOfflineProxy keeps RetroAchievements unlocks offline and sends them when you're back online (casual mode)."),
            fontSize = 14.sp)
        if (!installed) {
            Hint(tr("No está instalado: búscalo como RAOfflineProxy en GitHub u Obtainium. En esta tablet se usa con «Manual Emulator Setup» y Shizuku.",
                "Not installed: look for RAOfflineProxy on GitHub or Obtainium. On this tablet use \u201cManual Emulator Setup\u201d and Shizuku."))
            return@Panel
        }
        setups.forEach { st ->
            InfoRow(st.name, when {
                !st.loggedIn -> tr("Falta iniciar sesión en RetroAchievements (en el emulador; sin internet funciona con el proxy encendido)", "Sign in to RetroAchievements (in the emulator; works offline with the proxy on)")
                active == true && !st.toProxy -> tr("No apunta al proxy: apágalo y enciéndelo", "Not using the proxy: turn it off and on")
                active == true -> tr("Listo", "Ready")
                else -> tr("Sesión iniciada", "Signed in")
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Su guía pide los emuladores cerrados antes de encenderlo.
            Button(onClick = { scope.launch { RaProxy.prepare(a) } }) { Text(tr("Preparar y abrir", "Prepare and open")) }
            OutlinedButton(onClick = { RaProxy.open(a) }) { Text(tr("Abrir", "Open")) }
        }
        // Cómo funciona, en tres pasos (para quien no conoce RetroAchievements ni el proxy): se despliega al tocarlo,
        // así la tarjeta muestra primero lo esencial.
        var how by remember { mutableStateOf(false) }
        ExpandHeader(tr("Cómo funciona", "How it works"), how) { how = !how }
        if (how) {
        Hint(tr("1. Déjalo siempre encendido. Con internet pasa tus logros al instante; sin internet los guarda y los envía solo cuando vuelve la conexión (en cualquier lugar).",
            "1. Leave it always on. Online it passes your achievements right away; offline it keeps them and sends them by itself when you're back online (anywhere)."))
        Hint(tr("2. Abre cada juego una vez con internet y el proxy encendido: así guarda sus logros para jugarlo sin conexión.",
            "2. Open each game once online with the proxy on: that saves its achievements for playing offline."))
        Hint(tr("3. Cada emulador necesita la sesión iniciada en RetroAchievements (arriba dice si falta). En Dolphin, después de iniciarla sal con Atrás: si se cierra a la fuerza, la pierde. El tiempo jugado sin internet no se registra.",
            "3. Each emulator needs to be signed in to RetroAchievements (shown above). In Dolphin, press Back to exit after signing in: if it's force-closed, it loses it. Offline playtime isn't recorded."))
        Hint(tr("«Preparar y abrir» cierra los emuladores y abre RAOfflineProxy para Start (después de reiniciar, abrirlo basta: se enciende solo). Apágalo con su Stop proxy, que deja todo como estaba.",
            "“Prepare and open” closes the emulators and opens RAOfflineProxy for Start (after a reboot, opening it is enough: it turns on by itself). Turn it off with its Stop proxy, which restores everything."))
        }
        DolphinSignOutFix(a)
        SwitchRow(tr("Aviso sin internet", "Offline notice"), hint, tr("Al abrir un emulador sin internet y con el proxy apagado", "When opening an emulator offline with the proxy off")) {
            hint = it; AutoRules.setRaProxyHint(a, it)
        }
    }
}

/**
 * Workaround opcional de Dolphin (cierra la sesión de RetroAchievements solo): guardar la contraseña en su archivo de
 * configuración para que vuelva a iniciar sesión solo. Explica el riesgo; TBF no guarda la contraseña (ver
 * [RaProxy.setDolphinPassword]). Con Shizuku y Dolphin instalado.
 */
@Composable
private fun DolphinSignOutFix(a: MainActivity) {
    val hasDolphin = remember { runCatching { a.packageManager.getApplicationInfo("org.dolphinemu.dolphinemu", 0) }.isSuccess }
    if (!hasDolphin || Shell.state != Shell.State.READY) return
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<Boolean?>(null) }
    var pwd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { saved = RaProxy.dolphinHasPassword() }
    var open by remember { mutableStateOf(false) }
    ExpandHeader(tr("Dolphin cierra la sesión solo (workaround)", "Dolphin keeps signing out (workaround)"), open || saved == true) { open = !open }
    if (!open && saved != true) return
    Hint(tr("Error de Dolphin en Android. A algunos les sirve guardar la contraseña en su configuración para que vuelva a iniciar sesión solo (en Dolphin 2609a no sirvió). Queda sin cifrar en su archivo; TBF no la guarda. Si está mal escrita, Dolphin cierra la sesión cada vez.",
        "A Dolphin bug on Android. Saving the password in its config so it signs back in by itself helps some people (it didn't in Dolphin 2609a). It's stored unencrypted in its file; TBF doesn't keep it. If it's mistyped, Dolphin signs out every time."))
    if (saved == true) {
        InfoRow(tr("Contraseña en Dolphin", "Password in Dolphin"), tr("Guardada", "Saved"))
        OutlinedButton(onClick = { scope.launch { msg = if (RaProxy.setDolphinPassword(null)) tr("Quitada", "Removed") else tr("No se pudo", "Couldn't"); saved = RaProxy.dolphinHasPassword() } }) {
            Text(tr("Quitarla", "Remove it"))
        }
    } else {
        OutlinedTextField(pwd, { pwd = it }, label = { Text(tr("Contraseña de RetroAchievements", "RetroAchievements password")) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(onClick = {
            val p = pwd; pwd = ""
            scope.launch { msg = if (RaProxy.setDolphinPassword(p)) tr("Guardada (Dolphin se cerró para aplicarla)", "Saved (Dolphin was closed to apply it)") else tr("No se pudo", "Couldn't"); saved = RaProxy.dolphinHasPassword() }
        }, enabled = pwd.isNotBlank()) { Text(tr("Guardar en Dolphin", "Save to Dolphin")) }
    }
    msg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
}

/** Título que se toca para desplegar u ocultar una explicación (con flecha). */
@Composable
private fun ExpandHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Cómo se ve el panel de juego: su fondo (aparte del de la app) y el mini monitor. */
@Composable
internal fun PanelLookPanel(a: MainActivity) {
    var stats by remember { mutableStateOf(Prefs.panelStats(a)) }
    var loop by remember { mutableStateOf(ThemeState.panelLoop) }
    Panel(tr("Panel de juego", "Game panel")) {
        // Ver cómo queda sin salir de la app (también se abre manteniendo la guía, aquí y en cualquier app).
        OutlinedButton(onClick = { AutoModeService.active?.openPanel() }, enabled = AutoModeService.active != null) {
            Text(tr("Abrir el panel", "Open the panel"))
        }
        if (AutoModeService.active == null) Text(tr("Activa el servicio de accesibilidad para usar el panel.", "Turn on the accessibility service to use the panel."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        Text(tr("Estilo", "Style"), fontSize = 14.sp)
        ChoiceChips(PanelStyle.entries.map { it to it.label }, ThemeState.panelStyle) { ThemeState.panelStyle = it; Prefs.setPanelStyle(a, it) }
        if (ThemeState.panelSide) Hint(tr("Pegado a la derecha, de alto completo, como el acceso rápido del Steam Deck. La imagen va de cabecera o de fondo.",
            "Docked to the right, full height, like the Steam Deck's quick access menu. The image goes as the header or the background."))
        Text(tr("Fondo", "Background"), fontSize = 14.sp)
        ChoiceChips(PanelBackground.entries.map { it to it.label }, ThemeState.panelBackground) {
            if (it == PanelBackground.IMAGE && ThemeState.panelUri == null) a.pickPanelBackground()
            else { ThemeState.panelBackground = it; Prefs.setPanelBackground(a, it) }
        }
        if (ThemeState.panelBackground == PanelBackground.CLEAR) {
            SliderRow(tr("Relleno", "Fill"), ThemeState.panelFill, 0..100) { ThemeState.panelFill = it; Prefs.setPanelFill(a, it) }
        }
        // Lateral: la imagen de cabecera o de fondo (el degradado, siempre de fondo).
        if (ThemeState.panelSide && ThemeState.panelBackground == PanelBackground.IMAGE) {
            Text(tr("Imagen en", "Image as"), fontSize = 14.sp)
            ChoiceChips(listOf(true to tr("Cabecera", "Header"), false to tr("Fondo", "Background")), ThemeState.panelHeader) {
                ThemeState.panelHeader = it; Prefs.setPanelHeader(a, it)
            }
            if (ThemeState.panelHeader) {
                SliderRow(tr("Alto de la cabecera", "Header height"), ThemeState.panelHeaderH, 80..300) {
                    ThemeState.panelHeaderH = it; Prefs.setPanelHeaderH(a, it)
                }
                SliderRow(tr("Fundido de la cabecera", "Header fade"), ThemeState.panelFade, 0..200,
                    if (ThemeState.panelFade == 0) tr("Apagado", "Off") else "${ThemeState.panelFade}") {
                    ThemeState.panelFade = it; Prefs.setPanelFade(a, it)
                }
                SwitchRow(tr("Transparencia también en la imagen", "Transparency on the image too"), ThemeState.panelSeeImage) {
                    ThemeState.panelSeeImage = it; Prefs.setPanelSeeImage(a, it)
                }
            }
        }
        // Con cabecera, la transparencia es la de lo de abajo (la imagen queda opaca).
        if (ThemeState.panelBackground == PanelBackground.GRADIENT || ThemeState.panelBackground == PanelBackground.IMAGE) {
            // Liquid glass: el fondo elegido, pero dejando ver el juego detrás.
            SliderRow(tr("Transparencia", "Transparency"), ThemeState.panelSee, 0..100) { ThemeState.panelSee = it; Prefs.setPanelSee(a, it) }
        }
        if (ThemeState.panelBackground == PanelBackground.IMAGE) {
            OutlinedButton(onClick = { a.pickPanelBackground() }) { Text(tr("Cambiar imagen", "Change image")) }
            // Cómo se acomoda (como los fondos de Windows) y, al rellenar, qué parte queda a la vista.
            Text(tr("Ajuste", "Fit"), fontSize = 14.sp)
            ChoiceChips(ImageFit.entries.map { it to it.label }, ThemeState.panelFit) { ThemeState.panelFit = it; Prefs.setPanelFit(a, it) }
            if (ThemeState.panelFit == ImageFit.FILL) {
                Text(tr("Encuadre", "Position"), fontSize = 14.sp)
                ChoiceChips(listOf(0f to tr("Arriba", "Top"), 0.5f to tr("Centro", "Center"), 1f to tr("Abajo", "Bottom")), ThemeState.panelFocus) {
                    ThemeState.panelFocus = it; Prefs.setPanelFocus(a, it)
                }
            }
            SliderRow(tr("Desenfoque", "Blur"), ThemeState.panelBlur, 0..100) { ThemeState.panelBlur = it; Prefs.setPanelBlur(a, it) }
            SwitchRow(tr("Repetir el GIF", "Loop the GIF"), loop) { loop = it; ThemeState.panelLoop = it; Prefs.setPanelLoop(a, it) }
        }
        Text(tr("Tema (panel, avisos y guías)", "Theme (panel, notices and guides)"), fontSize = 14.sp)
        ChoiceChips(PanelTone.entries.map { it to it.label }, ThemeState.panelTone) { ThemeState.panelTone = it; Prefs.setPanelTone(a, it) }
        Text(tr("Mosaicos", "Tiles"), fontSize = 14.sp)
        ChoiceChips(listOf(false to tr("Nuevos", "New"), true to tr("Clásicos", "Classic")), ThemeState.panelClassic) {
            ThemeState.panelClassic = it; Prefs.setPanelClassic(a, it)
        }
        Text(tr("Color (panel, avisos y guías)", "Color (panel, notices and guides)"), fontSize = 14.sp)
        AccentPicker(ThemeState.panelAccent) { ThemeState.panelAccent = it; Prefs.setAccent(a, "panel", it) }
        // Las columnas son de la tarjeta al centro (la lateral tiene 2); el tamaño vale para las dos.
        if (!ThemeState.panelSide) {
            Text(tr("Columnas", "Columns"), fontSize = 14.sp)
            ChoiceChips(PanelShape.choices.map { it to it.label }, if (ThemeState.panelShape == PanelShape.R4_3) PanelShape.AUTO else ThemeState.panelShape) {
                ThemeState.panelShape = it; Prefs.setPanelShape(a, it)
            }
        }
        SliderRow(tr("Tamaño (%)", "Size (%)"), ThemeState.panelScale, 60..120) { ThemeState.panelScale = it; Prefs.setPanelScale(a, it) }
        SwitchRow(tr("Mini monitor", "Mini monitor"), stats, tr("RAM libre, watts, CPU y temperatura arriba del panel", "Free RAM, watts, CPU and temperature at the top of the panel")) {
            stats = it; Prefs.setPanelStats(a, it)
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
    // Avisos cuando falta algo: cada interruptor debajo del permiso al que se refiere.
    var warnService by remember { mutableStateOf(AutoRules.warnService(a)) }
    var warnShizuku by remember { mutableStateOf(AutoRules.warnShizuku(a)) }
    var shizukuTile by remember { mutableStateOf(AutoRules.shizukuTile(a)) }
    Panel(tr("Permisos", "Permissions")) {
        PermRow(tr("Servicio de accesibilidad", "Accessibility service"), s.autoServiceOn,
            tr("Panel de juego, atajos, capturas y lo automático", "Game panel, shortcuts, screenshots and automatic features")) {
            Hint(tr("Si sale «Ajuste restringido»: Ajustes → Apps → TBF Companion → ⋮ → Permitir ajustes restringidos.",
                "If “Restricted setting” appears: Settings → Apps → TBF Companion → ⋮ → Allow restricted settings."))
            Button(onClick = { AutoRules.openServiceSettings(a) }) { Text(tr("Abrir accesibilidad", "Open accessibility")) }
        }
        CompositionLocalProvider(LocalEditable provides true) {
            SwitchRow(tr("Avisar en el Inicio de Tablet si está apagado", "Warn on the Tablet home if it's off"), warnService) {
                warnService = it; AutoRules.setWarnService(a, it)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PermRow(tr("Shizuku (opcional)", "Shizuku (optional)"), shizuku == Shell.State.READY,
            tr("Bypass y límite de carga, modos de rendimiento, que Ahorro no baje los Hz, ocultar Game Assistant, FPS, apps corriendo, WiFi y Bluetooth, revisar los emuladores para los logros, arrastrar con el mouse y la tablet apagada con un monitor",
                "Bypass and charge limit, performance modes, keeping Saver from lowering the refresh rate, hiding Game Assistant, FPS, running apps, Wi-Fi and Bluetooth, checking emulators for achievements, dragging with the mouse and the tablet off with a monitor"),
            off = if (shizuku == Shell.State.NO_PERMISSION) tr("Falta el permiso", "Permission missing") else tr("No está iniciado", "Not started")
        ) {
            val launch = remember { Shell.launchIntent(a) }
            when {
                shizuku == Shell.State.NO_PERMISSION -> Button(onClick = { Shell.requestPermission() }) { Text(tr("Dar permiso", "Grant permission")) }
                launch != null -> Button(onClick = { runCatching { a.startActivity(launch) } }) { Text(tr("Abrir Shizuku", "Open Shizuku")) }
                else -> Hint(tr("No está instalado (shizuku.rikka.app).", "Not installed (shizuku.rikka.app)."))
            }
        }
        CompositionLocalProvider(LocalEditable provides true) {
            SwitchRow(tr("Avisar en el Inicio de Tablet si no está iniciado", "Warn on the Tablet home if it isn't started"), warnShizuku) {
                warnShizuku = it; AutoRules.setWarnShizuku(a, it)
            }
            SwitchRow(tr("Mosaico «Activar Shizuku» en el panel de juego", "“Start Shizuku” tile in the game panel"), shizukuTile) {
                shizukuTile = it; AutoRules.setShizukuTile(a, it)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PermRow(tr("Permiso de pantalla", "Screen permission"), Aspect.canWrite(a),
            tr("16:9, bloquear gestos y brillo automático en juegos · una vez desde un computador",
                "16:9, gesture lock and auto brightness in games · once from a computer"), off = tr("Falta", "Missing")) {
            Hint(Aspect.GRANT_COMMAND)
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
        1 -> Hint(tr("Sticks y gatillos siguen llegando al juego.", "Sticks and triggers still reach the game."))
        2 -> Hint(tr("También sticks y gatillos. El juego puede pausarse.", "Also sticks and triggers. The game may pause."))
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
