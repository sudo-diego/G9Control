package io.github.sudodiego.g9control

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
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
 * Página "Tablet": lo que es de la tablet y no del control. A la izquierda la batería (bypass manual y automático) y
 * la pantalla y los botones; a la derecha Game Assistant (rendimiento, ocultarlo, Shizuku).
 */
/** Batería: bypass, automático y límite de carga; a la derecha cómo está ahora. */
@Composable
fun BatteryPage(s: AppState, a: MainActivity) {
    TwoPane(
        first = {
            BatteryPanel(s, a)
            Gate(s.autoServiceOn) { LowBatteryPanel(a) }
            ShizukuHint()
        },
        second = { BatteryNowPanel(a); Gate(s.autoServiceOn) { PowerByAppPanel(a); SessionsPanel(a) } }
    )
}

/**
 * Sesiones de juego: por modo de rendimiento (W, CPU y temperatura) y los últimos tramos. Para comparar: el mismo
 * juego un rato en cada modo, desenchufada.
 */
@Composable
private fun SessionsPanel(a: MainActivity) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    val modes = remember(tick) { Sessions.byMode(a) }
    val list = remember(tick) { Sessions.list(a).take(8) }
    val c = MaterialTheme.colorScheme
    fun modeLabel(m: String?) = m?.let { runCatching { ZuiGame.PerfMode.valueOf(it).label }.getOrNull() } ?: tr("Modo desconocido", "Unknown mode")
    Panel(tr("Sesiones de juego", "Game sessions"), trailing = {
        if (list.isNotEmpty()) ConfirmButton(tr("Reiniciar", "Reset")) { Sessions.reset(a); tick++ }
    }) {
        if (list.isEmpty()) Hint(tr("Se anotan solas mientras juegas (modo, Hz, W, CPU y temperaturas). Para comparar modos: el mismo juego unos minutos en cada uno, desenchufada.",
            "Logged while you play (mode, Hz, W, CPU and temperatures). To compare modes: the same game a few minutes in each, unplugged."))
        if (modes.isNotEmpty()) {
            Text(tr("Por modo", "By mode"), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            modes.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(modeLabel(m.mode), Modifier.weight(1f), fontSize = 14.sp)
                    Text(listOfNotNull(m.avgWatts?.let { "%.1f W".format(it) }, m.avgCpuMhz?.let { Stats.cpuText(it) },
                        m.avgCpuMax?.let { "$it °C" }, ZuiBattery.formatTime(m.seconds * 1000L)).joinToString(" · "),
                        fontSize = 13.sp, color = c.onSurfaceVariant)
                }
            }
        }
        if (list.isNotEmpty()) {
            Text(tr("Últimas", "Latest"), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            list.forEach { s ->
                Column {
                    Text(RecentApps.label(a, s.pkg) + " · " + modeLabel(s.mode) + (s.hz?.let { " · $it Hz" } ?: ""), fontSize = 14.sp, maxLines = 1)
                    Text(listOfNotNull(ZuiBattery.formatTime(s.seconds * 1000L), s.avgWatts?.let { "%.1f W".format(it) },
                        s.avgCpuMhz?.let { "CPU " + Stats.cpuText(it) }, s.cpuMax?.let { "CPU $it °C" }, s.gpuMax?.let { "GPU $it °C" },
                        s.batMax?.let { tr("bat. $it °C", "bat. $it °C") }).joinToString(" · "), fontSize = 12.sp, color = c.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Consumo por app: lo que gastó en promedio cada una a batería con la pantalla encendida (lo mide el servicio cada
 * 5 s). Sirve para saber más o menos cuánto dura la batería con cada juego o emulador.
 */
@Composable
private fun PowerByAppPanel(a: MainActivity) {
    var apps by remember { mutableStateOf(PowerLog.apps(a)) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); apps = PowerLog.apps(a) } }
    val c = MaterialTheme.colorScheme
    Panel(tr("Consumo por app", "Draw per app"), trailing = {
        if (apps.isNotEmpty()) ConfirmButton(tr("Reiniciar", "Reset")) { PowerLog.reset(a); apps = emptyList() }
    }) {
        if (apps.isEmpty()) Hint(tr("Se mide a batería con la pantalla encendida: usa la tablet desenchufada un rato y aquí sale el promedio de cada app.",
            "Measured on battery with the screen on: use the tablet unplugged for a while and each app's average shows up here."))
        val pm = a.packageManager
        val full = remember { ZuiBattery.fullWh(a) }
        apps.take(10).forEach { u ->
            val label = remember(u.pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(u.pkg, 0)).toString() }.getOrDefault(u.pkg) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 15.sp, maxLines = 1)
                    Text(tr("medido ", "measured ") + ZuiBattery.formatTime(u.seconds * 1000), fontSize = 12.sp, color = c.onSurfaceVariant)
                }
                Text("%.1f W".format(u.avgWatts), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                // Cuánto duraría la batería llena solo con esa app.
                full?.takeIf { u.avgWatts > 0.3 }?.let { wh ->
                    Text("~" + ZuiBattery.formatTime((wh / u.avgWatts * 3_600_000).toLong()), Modifier.width(110.dp), fontSize = 14.sp,
                        color = c.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

/** Pantalla: 16:9, volumen invertido y el monitor externo. */
@Composable
fun ScreenPage(s: AppState, a: MainActivity) {
    TwoPane(
        // 16:9 a mano: permiso de pantalla o Shizuku. El monitor lo aplica el servicio de accesibilidad.
        first = { Gate(Aspect.available(a)) { ScreenButtonsPanel(s, a) } },
        second = { Gate(s.autoServiceOn) { MonitorPanel(s, a) }; ShizukuHint() }
    )
}

/** Sin Shizuku lo que lo necesita se ve atenuado; su estado y cómo iniciarlo están en Ajustes → Permisos. */
@Composable
private fun ShizukuHint() {
    if (Shell.state != Shell.State.READY) Hint(tr("Lo atenuado necesita Shizuku (Ajustes → Permisos).", "What's dimmed needs Shizuku (Settings → Permissions)."))
}

/**
 * La batería ahora: nivel, estado, temperatura, potencia y tiempo, y ciclos. Se actualiza cada pocos segundos (sin
 * permisos: `BatteryManager`).
 */
@Composable
fun BatteryNowPanel(a: MainActivity) {
    var now by remember { mutableStateOf(batteryNow(a)) }
    var watts by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = batteryNow(a)
            watts = ZuiBattery.watts(a)
            delay(3000)
        }
    }
    val b = now ?: return
    Panel(tr("Ahora", "Now")) {
        Text("${b.level} %", fontSize = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        val state = when {
            b.plugged && !b.charging -> if (ZuiBattery.bypassNow == true && Shell.state == Shell.State.READY) "Bypass" else tr("Enchufada, sin cargar", "Plugged in, not charging") + " · " + ZuiBattery.heldLabel(a, b.level)
            b.charging -> tr("Cargando", "Charging")
            else -> tr("Con batería", "On battery")
        }
        InfoRow(tr("Estado", "State"), state)
        InfoRow(tr("Temperatura", "Temperature"), "%.1f °C".format(b.tempC))
        if (!(b.plugged && !b.charging)) watts?.let { InfoRow(if (b.charging) tr("Entra", "Charging at") else tr("Consumo", "Draw"), "%.1f W".format(it)) }
        if (!b.plugged) PowerLog.recentAvg?.let { InfoRow(tr("Promedio del último minuto", "Last minute average"), "%.1f W".format(it)) }
        if (b.cycles >= 0) InfoRow(tr("Ciclos de carga", "Charge cycles"), "${b.cycles}")
    }
}

/** Aviso de batería baja como la Switch (lo muestra el servicio): cuándo, con sonido, encima del toast de ZUI. */
@Composable
private fun LowBatteryPanel(a: MainActivity) {
    Panel(tr("Aviso de batería baja", "Low battery notice")) {
        var low by remember { mutableStateOf(AutoRules.lowBattery(a)) }
        SwitchRow(tr("Mostrar el aviso", "Show the notice"), low, tr("Tarjeta a 15, 10 y 5 % sin cargador", "Card at 15, 10 and 5 % unplugged")) {
            low = it; AutoRules.setLowBattery(a, it)
        }
        var lowSound by remember { mutableStateOf(AutoRules.lowBatterySound(a)) }
        SwitchRow(tr("Con sonido", "With sound"), lowSound) { lowSound = it; AutoRules.setLowBatterySound(a, it) }
        var overToast by remember { mutableStateOf(AutoRules.lowBatteryOverToast(a)) }
        SwitchRow(tr("Tapar el aviso del sistema", "Cover the system notice"), overToast, tr("Abajo al centro, encima del toast de ZUI", "Bottom center, over ZUI's toast")) {
            overToast = it; AutoRules.setLowBatteryOverToast(a, it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { AutoRules.preview(a, "battery") }) { Text(tr("Ver el aviso", "Show the notice")) }
            // El aviso del sistema (notificación de SystemUI, canal BAT): una app no lo puede apagar, pero sí abrir su ajuste.
            TextButton(onClick = {
                runCatching {
                    a.startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, "com.android.systemui")
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "BAT"))
                }
            }) { Text(tr("Quitar el aviso del sistema", "Turn off the system notice")) }
        }
    }
}

@Composable
internal fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 14.sp)
    }
}

/** Ajuste de Game Assistant: 1 = apaga el brillo automático en los juegos (y lo devuelve al salir). */
private const val GA_NO_AUTO_BRIGHTNESS = "key_game_assistant_disable_auto_brightness"

/** Estado de la batería ahora (de ACTION_BATTERY_CHANGED, sin permisos). */
data class BatteryNow(val level: Int, val tempC: Float, val plugged: Boolean, val charging: Boolean, val cycles: Int)

fun batteryNow(a: MainActivity): BatteryNow? {
    val i = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
    return BatteryNow(
        level, i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        if (android.os.Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else -1
    )
}

/**
 * Batería: estado, bypass manual (el cargador alimenta la tablet y la batería no carga) y bypass automático con los
 * rangos que se elijan. El bypass va con Shizuku (el servicio de batería de ZUI solo acepta apps del sistema).
 */
@Composable
private fun BatteryPanel(s: AppState, a: MainActivity) {
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(batteryNow(a)) }
    var bypass by remember { mutableStateOf(ZuiBattery.bypassNow) }
    var smart by remember { mutableStateOf(AutoRules.smartBypass(a)) }
    var level by remember { mutableIntStateOf(AutoRules.smartBypassLevel(a)) }
    var temp by remember { mutableIntStateOf(AutoRules.smartBypassTemp(a)) }
    var games by remember { mutableStateOf(AutoRules.smartBypassGames(a)) }
    var limit by remember { mutableStateOf<Int?>(null) }
    var own by remember { mutableIntStateOf(ZuiBattery.ownLimit(a) ?: 0) }
    val ready = Shell.state == Shell.State.READY
    // Se refresca cada pocos segundos mientras la página está abierta.
    LaunchedEffect(ready) {
        while (true) {
            now = batteryNow(a)
            if (ready) {
                ZuiBattery.readBypass()?.let { bypass = it }
                limit = ZuiBattery.readChargeLimit()
            }
            delay(3000)
        }
    }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Batería", "Battery"), trailing = {
            now?.let { b ->
                // Enchufada y sin cargar: el bypass, el límite de carga de ZUI o algo más (la batería decide).
                val state = when {
                    bypass == true && b.plugged -> "Bypass"
                    b.charging -> tr("Cargando", "Charging")
                    b.plugged && limit != null -> tr("Límite $limit %", "Limit $limit %")
                    b.plugged -> tr("No carga", "Not charging")
                    else -> tr("Sin cargador", "Unplugged")
                }
                Text("${b.level} % · ${"%.0f".format(b.tempC)} °C · $state", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) {
            // Lo que no se puede usar se ve oscurecido: el bypass a mano necesita Shizuku; el límite y el automático,
            // además, el servicio de accesibilidad (que los vigila).
            Gate(ready && s.autoServiceOn) {
                // Límite propio: al llegar, bypass (la batería deja de cargar). El de ZUI corta siempre en 80 %.
                Text(tr("Límite de carga", "Charge limit"), fontSize = 14.sp)
                ChoiceChips(listOf(0 to tr("Apagado", "Off")) + ZuiBattery.LIMITS.map { it to "$it %" }, own) { lv ->
                    own = lv; ZuiBattery.setOwnLimit(a, lv.takeIf { it > 0 })
                }
                Text(
                    if (own == 0) tr("Apagado: el de ZUI corta en 80 %.", "Off: ZUI's stops at 80 %.")
                    else tr("Deja de cargar en $own %.", "Stops charging at $own %."),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (own > 80 && limit != null) Hint(tr("Apaga el límite de ZUI para llegar a $own %.", "Turn ZUI's limit off to reach $own %."))
            }
            Gate(ready) {
                SwitchRow(
                    tr("Bypass", "Bypass"), bypass == true,
                    tr("El cargador alimenta la tablet y la batería no carga.", "The charger powers the tablet; the battery doesn't charge.")
                ) { want ->
                    if (ready) scope.launch { if (ZuiBattery.setBypass(want)) bypass = want }
                }
            }
            Gate(ready && s.autoServiceOn) {
                // Automático: un resumen de cuándo se activa, que cambia con lo que se elija.
                val summary = listOfNotNull(
                    tr("Enchufada", "Plugged in"),
                    if (games) tr("en un juego", "in a game") else null,
                    tr("desde $level %", "from $level %"),
                    if (temp > 0) tr("desde $temp °C", "from $temp °C") else null
                ).joinToString(" · ")
                SwitchRow(tr("Bypass automático", "Automatic bypass"), smart, if (smart) summary else tr("Se activa solo cuando conviene.", "Turns on by itself when it makes sense.")) {
                    smart = it; AutoRules.setSmartBypass(a, it)
                    // Que no peleen: el inteligente de Game Assistant queda en Manual (se puede con WRITE_SECURE_SETTINGS).
                    if (it && Aspect.canWrite(a)) runCatching { Settings.Global.putInt(a.contentResolver, "intelligent_bypass_charging_value", 0) }
                }
                if (smart) {
                    Text(tr("Cuándo", "When"), fontSize = 14.sp)
                    ChoiceChips(listOf(true to tr("En juegos", "In games"), false to tr("Siempre", "Always")), games) { games = it; AutoRules.setSmartBypassGames(a, it) }
                    // Libre, pero se pega en 20, 40, 60 y 80 (los de antes). Por qué esos: ver el texto de abajo.
                    SliderRow(tr("Batería desde", "Battery from"), level, 10..90, "$level %", snaps = listOf(20, 40, 60, 80)) {
                        level = it; AutoRules.setSmartBypassLevel(a, it)
                    }
                    Hint(tr("Desde ese nivel deja de cargar y la batería se queda ahí. Entre 40 y 60 % es lo que más la cuida (el litio envejece menos a media carga); 80 % si quieres salir con más batería.",
                        "From that level it stops charging and the battery stays there. 40–60 % is easiest on it (lithium ages less at half charge); 80 % if you want to leave with more battery."))
                    limit?.takeIf { level >= it - 5 }?.let { Hint(tr("El límite de ZUI ($it %) casi no deja llegar.", "ZUI's limit ($it %) barely lets it get there.")) }
                    // 30 en la barra = "Cualquiera" (se guarda 0). Se pega en 35, 38 y 41 °C.
                    SliderRow(tr("Temperatura de la batería desde", "Battery temperature from"), if (temp == 0) 30 else temp, 30..45,
                        if (temp == 0) tr("Cualquiera", "Any") else "$temp °C", snaps = listOf(35, 38, 41), snapPull = 1) {
                        temp = if (it <= 30) 0 else it; AutoRules.setSmartBypassTemp(a, temp)
                    }
                    Hint(tr("Cargar con calor es lo que más gasta la batería. Bajo 35 °C cargar no la daña; desde 38 °C conviene no cargar mientras juegas; sobre 41 °C envejece bastante más rápido (y cerca de 45 °C Android corta la carga).",
                        "Charging while hot wears the battery the most. Below 35 °C charging is fine; from 38 °C it's better not to charge while playing; above 41 °C it ages much faster (and near 45 °C Android stops charging)."))
                }
            }
            OutlinedButton(onClick = { runCatching { a.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) } }) {
                Text(tr("Optimización de carga (Ajustes)", "Charge optimization (Settings)"))
            }
        }
    }
}

/**
 * Monitor externo en modo espejo: 16:9 al conectarlo (para todos, y cada monitor puede tener lo suyo), qué hace la
 * pantalla de la tablet mientras tanto, y la lista de los que se han conectado. Lo aplica el servicio de accesibilidad.
 */
@Composable
private fun MonitorPanel(s: AppState, a: MainActivity) {
    var monitor by remember { mutableStateOf(Monitor.current(a)) }
    var mirror by remember { mutableStateOf(Monitor.mirror(a)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { monitor = Monitor.current(a); mirror = Monitor.mirror(a); tick++; delay(2000) } }
    var all169 by remember { mutableStateOf(Monitor.all169(a)) }
    var screen by remember { mutableStateOf(Monitor.screen(a)) }
    val known = remember(tick) { Monitor.known(a) }
    val ready = Shell.state == Shell.State.READY
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Monitor", "Monitor"), trailing = {
            val m = monitor
            Text(
                when {
                    m == null -> tr("Sin monitor", "No monitor")
                    mirror -> "${m.name} · " + tr("espejo", "mirror")
                    else -> "${m.name} · " + tr("extendido", "extended")
                }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }) {
            if (Aspect.available(a)) {
                SwitchRow(
                    tr("16:9 en cualquier monitor", "16:9 on any monitor"), all169,
                    tr("Al conectar en espejo", "When connected in mirror mode. Back to 16:10 when unplugged.")
                ) { all169 = it; Monitor.setAll169(a, it) }
            }
            Text(tr("Pantalla de la tablet", "Tablet screen"), fontSize = 14.sp)
            ChoiceChips(
                listOf(Monitor.Screen.NORMAL to tr("Normal", "Normal"), Monitor.Screen.DIM to tr("Oscura", "Dim"),
                    Monitor.Screen.NO_LIGHT to tr("Sin luz", "No light"), Monitor.Screen.OFF to tr("Apagada", "Off")), screen
            ) { screen = it; Monitor.setScreen(a, it) }
            Text(
                when (screen) {
                    Monitor.Screen.NORMAL -> tr("La tablet sigue como siempre.", "The tablet stays as usual.")
                    Monitor.Screen.DIM -> tr("Brillo al mínimo. Sin Shizuku.", "Minimum brightness. No Shizuku needed.")
                    Monitor.Screen.NO_LIGHT -> tr("Sin luz, con Shizuku (como Extinguish).", "Backlight off, with Shizuku (like Extinguish).")
                    Monitor.Screen.OFF -> tr("Panel apagado, con Shizuku.", "Panel off, with Shizuku.")
                }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Encendida con el botón de encendido: volver a oscurecerla sin control.
            if (screen != Monitor.Screen.NORMAL && monitor != null && mirror && remember(tick) { Monitor.woken }) {
                OutlinedButton(onClick = { AutoRules.darkenTablet(a); tick++ }) { Text(tr("Oscurecer ahora", "Darken now")) }
            }
            if (screen >= Monitor.Screen.NO_LIGHT && !ready) Hint(tr("Sin Shizuku queda oscura.", "Without Shizuku it's just dimmed."))
            if (screen != Monitor.Screen.NORMAL) Hint(tr("Con el monitor en espejo. Si algo falla, el botón de encendido dos veces la enciende.",
                "With the monitor in mirror mode. If anything goes wrong, pressing power twice turns it on."))
            if (known.isNotEmpty() && Aspect.available(a)) {
                Text(tr("Monitores", "Monitors"), fontSize = 14.sp)
                known.forEach { k ->
                    var fit by remember(k.key, tick) { mutableStateOf(Monitor.fit(a, k.key)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            k.name + if (k.key == monitor?.key) tr(" (conectado)", " (connected)") else "",
                            Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ConfirmButton(tr("Olvidar", "Forget")) { Monitor.forget(a, k.key); tick++ }
                    }
                    ChoiceChips(
                        listOf(Monitor.Fit.ALL to (if (all169) tr("Como todos (16:9)", "Like all (16:9)") else tr("Como todos (16:10)", "Like all (16:10)")),
                            Monitor.Fit.WIDE to "16:9", Monitor.Fit.NATIVE to "16:10"), fit
                    ) { fit = it; Monitor.setFit(a, k.key, it) }
                }
            }
        }
    }
}

/** Pantalla 16:9 y volumen invertido. */
@Composable
private fun ScreenButtonsPanel(s: AppState, a: MainActivity) {
    // Invertir el volumen pasó a Controles (botones de la tablet).
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel(tr("Pantalla", "Screen")) {
            ScreenRow(a)
        }
    }
}

/**
 * Game Assistant, el de la ROM de la tablet: si hay un juego ahora (se actualiza solo), ocultarlo y un acceso a sus
 * ajustes. Ocultarlo es quitarle "Mostrar sobre otras apps": con Shizuku G9 Control lo hace solo (y lo muestra un
 * momento para cambiar el rendimiento); sin Shizuku se puede a mano en Ajustes. Solo si la tablet lo tiene.
 */
@Composable
private fun ModeRow(name: String, desc: String) {
    Row {
        Text(name, Modifier.width(150.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GameAssistantSettings(s: AppState, a: MainActivity) {
    val zui = remember { ZuiGame.installed(a) }
    if (!zui) return
    var game by remember { mutableStateOf(ZuiGame.inGame(a)) }
    DisposableEffect(Unit) {
        val o = ZuiGame.observe(a, android.os.Handler(a.mainLooper)) { game = ZuiGame.inGame(a) }
        onDispose { ZuiGame.unobserve(a, o) }
    }
    val ready = Shell.state == Shell.State.READY
    val gaScope = rememberCoroutineScope()
    var gaHidden by remember { mutableStateOf(AutoRules.gaHidden(a)) }
    // Lo que el usuario eligió, no cómo está ahora: puede estar visible un rato porque G9 Control lo está usando
    // (copiar el estado actual a la preferencia hacía que no se volviera a ocultar). Solo la primera vez, sin
    // preferencia guardada, se toma de cómo está.
    LaunchedEffect(ready) { if (ready && gaHidden == null) ZuiGame.hidden()?.let { gaHidden = it; AutoRules.setGaHidden(a, it) } }
    CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
        Panel("Game Assistant", trailing = {
            Text(if (game) tr("En un juego", "In a game") else tr("Sin juego", "No game"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) {
            CompositionLocalProvider(LocalEditable provides (ready && LocalGateOk.current)) {
                SwitchRow(
                    tr("Ocultar Game Assistant", "Hide Game Assistant"), gaHidden == true
                ) { want ->
                    if (ready) gaScope.launch { if (ZuiGame.setHidden(want)) { AutoRules.setGaHidden(a, want); gaHidden = want } }
                }
            }
            if (!ready) OutlinedButton(onClick = { runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }) {
                Text(tr("Ocultarlo a mano (Mostrar sobre otras apps)", "Hide it manually (Display over other apps)"))
            }
            // Game Assistant apaga el brillo automático al entrar a un juego (y lo devuelve al salir). Se cambia con el
            // permiso de pantalla de adb (es un ajuste global).
            val canWrite = remember { Aspect.canWrite(a) }
            var autoInGames by remember {
                mutableStateOf(runCatching { Settings.Global.getInt(a.contentResolver, GA_NO_AUTO_BRIGHTNESS) == 0 }.getOrDefault(false))
            }
            CompositionLocalProvider(LocalEditable provides (canWrite && LocalGateOk.current)) {
                SwitchRow(
                    tr("Brillo automático en juegos", "Auto brightness in games"), autoInGames,
                    tr("Que Game Assistant no lo apague", "Keep Game Assistant from turning it off")
                ) { want ->
                    if (runCatching { Settings.Global.putInt(a.contentResolver, GA_NO_AUTO_BRIGHTNESS, if (want) 0 else 1) }.isSuccess) autoInGames = want
                }
            }
            if (!canWrite) Hint(tr("Necesita el permiso de pantalla (Ajustes → Permisos).", "Needs the screen permission (Settings → Permissions)."))
            // Medido en RetroArch: ninguno cambia el techo de la CPU; cambian cuánto la dejan subir.
            Text(tr("Modos de rendimiento", "Performance modes"), fontSize = 14.sp)
            ModeRow(tr("Ahorro de energía", "Power saving"), tr("CPU baja: gasta menos, más lag",
                "Low CPU: uses less, more lag"))
            ModeRow(tr("Equilibrado", "Balanced"), tr("Sube cuando el juego lo pide", "Ramps up when the game asks"))
            ModeRow(tr("Alto rendimiento", "High performance"), tr("CPU alta: menos tirones, más calor", "High CPU: fewer stutters, more heat"))
            OutlinedButton(onClick = { if (!ZuiGame.openSettings(a)) s.fail(tr("No se pudieron abrir los ajustes de Game Assistant.", "Couldn't open Game Assistant settings.")) }) {
                Text(tr("Ajustes de Game Assistant", "Game Assistant settings"))
            }
        }
    }
}

/**
 * Pantalla 16:9 para un monitor externo. Sin Shizuku si la app tiene WRITE_SECURE_SETTINGS (se da una vez con
 * adb); si no, con Shizuku; si no hay ninguno, el comando para darlo.
 */
@Composable
private fun ScreenRow(a: MainActivity) {
    val scope = rememberCoroutineScope()
    var wide by remember { mutableStateOf(Aspect.is169(a)) }
    if (!Aspect.available(a)) {
        Text(tr("Pantalla 16:9", "16:9 screen"), fontSize = 14.sp)
        Hint(tr("Monitor sin bordes. Se activa una vez desde un computador: ${Aspect.GRANT_COMMAND}",
            "For an external monitor without borders. Enable it once from a computer: ${Aspect.GRANT_COMMAND}"))
        return
    }
    SwitchRow(
        tr("Pantalla 16:9", "16:9 screen"), wide,
        tr("Monitor sin bordes · cierra Dolphin si está abierto", "External monitor without borders. Switching closes Dolphin if it's open.")
    ) { want ->
        scope.launch {
            if (want != Aspect.is169(a)) Aspect.toggle(a)
            kotlinx.coroutines.delay(500)
            wide = Aspect.is169(a)
        }
    }
}
