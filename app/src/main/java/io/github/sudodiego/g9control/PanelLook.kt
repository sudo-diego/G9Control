package io.github.sudodiego.g9control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import kotlin.coroutines.resume
import java.util.Date
import java.util.Locale

/**
 * Cómo se ve el panel de juego: colores según el tema (claro u oscuro), medidas según la forma y el tamaño elegidos,
 * la tarjeta y sus piezas (mosaicos, barras, mini monitor, ícono de batería). La lógica está en [GamePanel].
 */

/** Dónde va un ítem del panel: las barras arriba, después el juego, la pantalla y conexiones, y abajo Android. */
internal enum class PanelRow { SLIDER, GAME, RECENT, SCREEN, SYSTEM }

/**
 * Una acción del panel de juego, como un mosaico de los ajustes rápidos: [sub] es su estado o un detalle y
 * [active] lo pinta como encendido. Las barras ([PanelRow.SLIDER], brillo y volumen) tienen [level] (0..1): A entra
 * a ajustarlas (← → llaman a [adjust] con −1 o +1, Y llama a [alt], A o B salen), y con el dedo [set] fija el nivel;
 * [hint] dice qué hace cada botón mientras se ajusta.
 */
internal class PanelItem(
    val label: String, val icon: ImageVector, val section: PanelRow = PanelRow.GAME,
    val sub: String? = null, val active: Boolean = false,
    /** Atenuado: no funciona ahora (falta Shizuku); elegirlo lleva a arreglarlo. */
    val enabled: Boolean = true,
    val adjust: ((Int) -> Unit)? = null, val level: Float? = null, val alt: (() -> Unit)? = null,
    val set: ((Float) -> Unit)? = null, val hint: String? = null,
    /** Ícono de una app (recientes) en vez del de [icon]. */
    val appIcon: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** En rojo: lo que va a pasar no se deshace (cerrar una reciente, a la espera de confirmar). */
    val danger: Boolean = false,
    val run: () -> Unit = {}
)

/** Columnas de mosaicos: las de la forma elegida. */
internal val COLUMNS get() = if (ThemeState.panelSide) 2 else ThemeState.panelShape.cols

/**
 * Las filas del panel (índices de [items]): las barras juntas y los mosaicos de cada sección repartidos parejo, de a
 * [COLUMNS] como mucho (6 quedan en 3 + 3, no en 4 + 2). Cada fila llena el ancho: sin huecos.
 */
internal fun panelRows(items: List<PanelItem>): List<List<Int>> {
    val by = items.indices.groupBy { items[it].section }
    fun even(list: List<Int>): List<List<Int>> {
        val rows = (list.size + COLUMNS - 1) / COLUMNS
        return list.chunked((list.size + rows - 1) / rows)
    }
    return buildList {
        // Lateral (2 columnas): brillo y volumen uno debajo del otro, cada uno con su botón.
        by[PanelRow.SLIDER]?.let { if (ThemeState.panelSide) addAll(it.chunked(2)) else add(it) }
        by[PanelRow.GAME]?.let { addAll(even(it)) }
        // Recientes en una sola fila (las que quepan).
        by[PanelRow.RECENT]?.let { if (ThemeState.panelSide) addAll(it.take(4).chunked(2)) else add(it.take(COLUMNS)) }
        by[PanelRow.SCREEN]?.let { addAll(even(it)) }
        by[PanelRow.SYSTEM]?.let { addAll(even(it)) }
    }
}

/** Estado que dibuja el panel. */
internal class PanelUi {
    var items by mutableStateOf(listOf<PanelItem>())
    var sel by mutableIntStateOf(0)
    /** Ajustando el ítem elegido (brillo, volumen). */
    var editing by mutableStateOf(false)
    /** Encabezado: la app al frente (o el submenú), la batería (−1 = no mostrar) y el estado y la hora. */
    var title by mutableStateOf("")
    var battery by mutableIntStateOf(-1)
    var charging by mutableStateOf(false)
    var bypass by mutableStateOf(false)
    /** Texto de abajo en un submenú (en vez de "A elegir · B volver"). */
    var footer by mutableStateOf<String?>(null)
    /** Enchufada sin cargar (límite de carga, llena): el ícono lleva enchufe en vez de rayo. */
    var held by mutableStateOf(false)
    /** Cargando: lo que falta para la carga completa ("1 h 20 min"), a la izquierda de la batería. */
    var chargeLeft by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    /** Modo del G9 ahora (Android, X-Input, DS5), como recordatorio; null si no está conectado. */
    var mode by mutableStateOf<String?>(null)
    /** Con la forma automática, el alto de la ventana queda fijo al entrar a un submenú (si se achicara, ZUI la desliza). */
    var lockedHeight by mutableStateOf(false)
    /** Opacidad del contenido (0..1): baja y sube al pasar a un submenú o volver (fundido). */
    var fade by androidx.compose.runtime.mutableFloatStateOf(1f)
    /** Mini monitor (RAM, W, CPU, temperatura) debajo del encabezado. */
    var stats by mutableStateOf(true)
    /** En un submenú (rendimiento): encabezado sin batería ni hora, y B vuelve. */
    var sub by mutableStateOf(false)
}


// Colores del panel, como los ajustes rápidos de Android: en oscuro, azul marino con lo encendido en celeste (la
// paleta de la app, los fondos de la Y700); en claro, blanco azulado con lo encendido en azul y el texto marino.
// Dependen del tema del panel ([ThemeState.panelLight]), así sirven para todos los fondos.
internal val light get() = ThemeState.panelLight
internal val PANEL_BG get() = if (light) Color(0xF2F3F6FB) else Color(0xF00E1424)
internal val TILE get() = if (light) Color(0xFFE1E7F1) else Color(0xFF1F2A42)
internal val TILE_ON get() = if (light) Color(0xFF2F8FD8) else Color(0xFF6EC9FF)
internal val ON_TILE_ON get() = if (light) Color.White else Color(0xFF052238)
internal val PANEL_ACCENT get() = if (light) Color(0xFF1E7FC8) else Color(0xFF5CC8FF)
internal val PANEL_MUTED get() = if (light) Color(0xFF56607A) else Color(0xFF9AA6BF)
/** Texto e íconos sobre los mosaicos apagados y el fondo. */
internal val PANEL_FG get() = if (light) Color(0xFF14203A) else Color.White
internal val SELECTED get() = if (light) Color(0xFF14203A) else Color.White
// Con fondo (degradado o imagen) los mosaicos son de vidrio: translúcidos con un borde claro, como en la app.
internal val TILE_GLASS get() = if (light) Color(0x99FFFFFF) else Color(0x2EFFFFFF)
internal val GLASS_EDGE get() = if (light) Color(0x80FFFFFF) else Color(0x24FFFFFF)
// Lateral: fondo liso del tema (la imagen va solo de cabecera), así que mosaicos sólidos, sin vidrio.
internal val glassPanel get() = ThemeState.panelBackground != PanelBackground.PLAIN && !ThemeState.panelSide
// Transparente: los mosaicos llevan más cuerpo para leerse sobre cualquier juego.
internal val TILE_CLEAR get() = if (light) Color(0xD0F4F7FC) else Color(0xB8141C30)
/** Halo de los textos sueltos (secciones, hora, la línea de abajo): se leen sobre cualquier imagen o juego. */
internal val LABEL_STYLE get() = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
    if (light) Color(0xE6FFFFFF) else Color(0xB3000000), blurRadius = 8f))
/** Velo sobre el degradado o la imagen, para que se lea el texto. */
internal val PANEL_VEIL get() = if (light) Color(0x99EEF2F8) else Color(0x660A0F1F)
internal val tileColor get() = when {
    ThemeState.panelBackground == PanelBackground.PLAIN || ThemeState.panelSide -> TILE
    ThemeState.panelSeeThrough -> TILE_CLEAR
    else -> TILE_GLASS
}

/** Medidas de la tarjeta según la forma y el tamaño elegidos, ajustadas a la pantalla (con margen). */
internal class PanelDims(val cols: Int, val tileW: Dp, val width: Dp, val height: Dp?)

/** Escala del panel (el "Tamaño" en Ajustes): se aplica a toda la tarjeta con una densidad propia. */
internal val panelScale get() = ThemeState.panelScale.coerceIn(60, 120) / 100f

/** En dp sin escalar (los de la tarjeta); la pantalla se mide en esas mismas unidades. */
internal fun panelDims(ctx: android.content.Context): PanelDims {
    val conf = ctx.resources.configuration
    val shape = ThemeState.panelShape
    val cols = shape.cols
    val screenW = conf.screenWidthDp / panelScale
    val screenH = conf.screenHeightDp / panelScale
    val maxTile = (screenW * 0.94f - CARD_PAD.value * 2 - GAP.value * (cols - 1)) / cols
    val tileW = minOf(172f, maxTile).dp
    val width = tileW * cols + GAP * (cols - 1) + CARD_PAD * 2
    // Lateral: dos mosaicos de ancho y alto completo (la ventana ocupa el alto).
    if (ThemeState.panelSide) return PanelDims(2, 172.dp, 172.dp * 2 + GAP + CARD_PAD * 2, null)
    val height = shape.ratio?.let { minOf(width.value / it, screenH * 0.92f).dp }
    return PanelDims(cols, tileW, width, height)
}

internal val TILE_H = 68.dp
internal val GAP = 10.dp
internal val CARD_PAD = 22.dp

/**
 * El panel, como los ajustes rápidos de Android: encabezado (la app al frente, batería y hora), brillo y volumen
 * como barras, y los mosaicos del juego y de Android en una grilla. El elegido lleva borde. Al centro es una tarjeta
 * (con forma y tamaño a elección); lateral ([ThemeState.panelSide]) va pegado a la derecha y de alto completo, con la
 * imagen o el GIF como cabecera y el resto desplazable (lo elegido siempre a la vista).
 */
@Composable
internal fun PanelCard(ui: PanelUi, modifier: Modifier = Modifier, onPick: (Int) -> Unit, onSet: (Int, Float) -> Unit) {
    if (ThemeState.panelSide) { SidePanelCard(ui, modifier, onPick, onSet); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dims = panelDims(ctx)
    val width = dims.width - CARD_PAD * 2
    // Con forma fija el alto es el de la proporción y las filas de mosaicos se reparten lo que queda.
    val fixed = dims.height != null
    val shape = RoundedCornerShape(28.dp)
    Surface(color = if (ThemeState.panelSeeThrough) Color.Transparent else PANEL_BG, shape = shape,
        modifier = modifier.then(dims.height?.let { Modifier.height(it) } ?: Modifier),
        border = if (glassPanel) BorderStroke(1.dp, GLASS_EDGE) else null) {
      Box {
        // Fondo propio del panel (degradado, imagen o GIF) con un velo para que se lea sobre cualquier imagen.
        if (glassPanel) Box(Modifier.matchParentSize().clip(shape)) {
            PanelBackdrop()
            if (!ThemeState.panelSeeThrough) Box(Modifier.matchParentSize().background(PANEL_VEIL))
            // En claro el fondo se aclara siempre (si no, las partes oscuras de la imagen o del juego dejaban el texto
            // marino sin contraste); con transparencia, menos mientras más se vea el juego.
            else if (light && ThemeState.panelBackground != PanelBackground.CLEAR)
                Box(Modifier.matchParentSize().background(PANEL_VEIL.copy(alpha = 0.6f * (1f - ThemeState.panelSee / 160f))))
        }
        Column(Modifier.padding(horizontal = CARD_PAD, vertical = 20.dp).width(width).then(if (fixed) Modifier.fillMaxHeight() else Modifier)
            .graphicsLayer { alpha = ui.fade; val s = 0.97f + 0.03f * ui.fade; scaleX = s; scaleY = s }) {
            PanelHeader(ui)
            if (!ui.sub && ui.stats) { Spacer(Modifier.height(12.dp)); PanelStats() }
            Spacer(Modifier.height(16.dp))
            PanelGrid(ui, fixed, if (fixed) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(), onPick, onSet)
            Spacer(Modifier.height(14.dp))
            PanelFooter(ui, Modifier.align(Alignment.CenterHorizontally))
        }
      }
    }
}

/**
 * Panel lateral (estilo Steam): pegado a la derecha, de alto completo. Arriba la imagen o el GIF elegido como cabecera
 * (con degradado o liso, una franja del color del tema); abajo, fondo liso del tema. Los mosaicos van en 2 columnas y
 * la lista se desplaza sola para que lo elegido quede a la vista.
 */
@Composable
private fun SidePanelCard(ui: PanelUi, modifier: Modifier, onPick: (Int) -> Unit, onSet: (Int, Float) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dims = panelDims(ctx)
    val shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
    val scroll = androidx.compose.foundation.rememberScrollState()
    // Dónde empieza cada fila dentro de la lista, para desplazarla hasta lo elegido.
    val rowTops = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Int, Int>>() }
    LaunchedEffect(ui.sel, ui.items) {
        val r = panelRows(ui.items).indexOfFirst { ui.sel in it }
        val (top, h) = rowTops[r] ?: return@LaunchedEffect
        val view = scroll.viewportSize
        val margin = 40
        when {
            top - margin < scroll.value -> scroll.animateScrollTo((top - margin).coerceAtLeast(0))
            top + h + margin > scroll.value + view -> scroll.animateScrollTo(top + h + margin - view)
        }
    }
    Surface(color = PANEL_BG, shape = shape, modifier = modifier.width(dims.width).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = ui.fade }) {
            // Cabecera: la imagen o el GIF del panel (con degradado, o liso: el color del tema), fundida hacia abajo.
            if (!ui.sub) Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(topStart = 28.dp))) {
                if (ThemeState.panelBackground == PanelBackground.IMAGE || ThemeState.panelBackground == PanelBackground.GRADIENT) PanelBackdrop()
                else Box(Modifier.matchParentSize().background(TILE_ON.copy(alpha = 0.35f)))
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.45f to Color.Transparent, 1f to PANEL_BG)))
            }
            Column(Modifier.padding(horizontal = CARD_PAD).padding(top = if (ui.sub) 24.dp else 0.dp)) {
                PanelHeader(ui)
                if (!ui.sub && ui.stats) { Spacer(Modifier.height(12.dp)); PanelStats() }
                Spacer(Modifier.height(14.dp))
            }
            PanelGrid(ui, false, Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = CARD_PAD).padding(bottom = 12.dp),
                onPick, onSet) { r, top, h -> rowTops[r] = top to h }
            PanelFooter(ui, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp))
        }
    }
}

/** Encabezado: la app al frente (o el submenú), el modo del G9, y batería, tiempo y hora. */
@Composable
private fun PanelHeader(ui: PanelUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // El nombre ocupa lo que necesita (hasta la mitad); el resto empuja la batería y la hora a la derecha.
        Text(ui.title, color = PANEL_FG, fontSize = 18.sp, style = LABEL_STYLE, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 340.dp).weight(1f, fill = false))
        // Modo del control, pequeño, como recordatorio.
        if (!ui.sub) ui.mode?.let { m ->
            Spacer(Modifier.width(10.dp))
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(tileColor).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SportsEsports, null, Modifier.size(14.dp), tint = PANEL_MUTED)
                Spacer(Modifier.width(5.dp))
                Text(m, color = PANEL_MUTED, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        // En un submenú solo el título: lo demás ya se vio en el panel.
        if (!ui.sub) {
            if (ui.bypass) { Icon(Icons.Outlined.Power, "Bypass", Modifier.size(18.dp), tint = TILE_ON); Spacer(Modifier.width(4.dp)) }
            // Lateral (angosto): sin el tiempo restante, que ya está en el mini monitor.
            if (!ThemeState.panelSide) ui.chargeLeft?.let { Text(it, color = PANEL_MUTED, fontSize = 14.sp, maxLines = 1, style = LABEL_STYLE); Spacer(Modifier.width(6.dp)) }
            if (ui.battery >= 0) { BatteryGlyph(ui.battery, ui.charging, ui.bypass, ui.held); Spacer(Modifier.width(8.dp)) }
            Text(ui.status, color = PANEL_MUTED, fontSize = 14.sp, maxLines = 1, style = LABEL_STYLE)
        }
    }
}

/**
 * La grilla: barras y mosaicos por sección, con su nombre chico. [fixed]: forma fija, las filas se reparten el alto.
 * [onRow] avisa dónde quedó cada fila (el panel lateral la usa para desplazarse hasta lo elegido).
 */
@Composable
private fun PanelGrid(ui: PanelUi, fixed: Boolean, modifier: Modifier, onPick: (Int) -> Unit, onSet: (Int, Float) -> Unit,
                      onRow: ((Int, Int, Int) -> Unit)? = null) {
    val rows = panelRows(ui.items)
    Column(modifier, verticalArrangement = if (fixed) Arrangement.Center else Arrangement.Top) {
        rows.forEachIndexed { r, row ->
            val section = ui.items[row[0]].section
            // Un poco más de aire entre secciones (barras, juego, Android) que entre filas, y su nombre chico (no en
            // los submenús, que tienen una sola sección).
            val newSection = r == 0 || ui.items[rows[r - 1][0]].section != section
            if (r > 0) Spacer(Modifier.height(if (newSection) 14.dp else GAP))
            if (newSection && !ui.sub && section != PanelRow.SLIDER) {
                Text(when (section) {
                    PanelRow.GAME -> tr("Juego", "Game"); PanelRow.RECENT -> tr("Recientes", "Recent")
                    PanelRow.SCREEN -> tr("Pantalla y conexión", "Screen and connection")
                    else -> "Android"
                }, color = PANEL_MUTED, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = LABEL_STYLE, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            }
            // Cada fila llena el ancho: los mosaicos se reparten el espacio (sin huecos al final). Con forma fija,
            // las filas de mosaicos se reparten también el alto.
            val stretch = fixed && !ui.sub && section != PanelRow.SLIDER
            Row(Modifier.fillMaxWidth().then(if (stretch) Modifier.weight(1f, fill = false).heightIn(min = 48.dp, max = 92.dp) else Modifier)
                .then(if (onRow != null) Modifier.onGloballyPositioned { onRow(r, it.positionInParent().y.toInt(), it.size.height) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEach { i ->
                    val item = ui.items[i]
                    when {
                        section != PanelRow.SLIDER -> PanelTile(item, i == ui.sel,
                            Modifier.weight(1f).then(if (stretch) Modifier.fillMaxHeight() else Modifier.height(TILE_H))) { onPick(i) }
                        item.level != null -> PanelSlider(item, i == ui.sel, ui.editing && i == ui.sel, Modifier.weight(1f)) { onSet(i, it) }
                        else -> PanelToggle(item, i == ui.sel) { onPick(i) }
                    }
                }
                // Recientes con menos que las columnas: los mosaicos quedan del ancho de los demás.
                if (section == PanelRow.RECENT && row.size < COLUMNS) Spacer(Modifier.weight((COLUMNS - row.size).toFloat()))
            }
        }
    }
}

/** La línea de abajo: qué hace cada botón ahora (o lo propio del submenú). */
@Composable
private fun PanelFooter(ui: PanelUi, modifier: Modifier) {
    Text(
        ui.items.getOrNull(ui.sel)?.takeIf { ui.editing }?.hint
            ?: ui.items.getOrNull(ui.sel)?.takeIf { it.section == PanelRow.RECENT }?.let {
                tr("A volver · Y cerrar la app · B cerrar", "A resume · Y close the app · B close") }
            ?: ui.footer ?: if (ui.sub) tr("A elegir · B volver", "A select · B back")
            else tr("Stick o cruceta mover · A elegir · B cerrar", "Stick or D-pad move · A select · B close"),
        color = PANEL_MUTED, fontSize = 12.sp, style = LABEL_STYLE, modifier = modifier,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/**
 * Mini monitor del panel: RAM libre, consumo (ahora y el promedio del último minuto), la CPU y la temperatura de la
 * batería. Se actualiza cada 2 s mientras el panel está abierto (el resto del panel, cada 1,5 s: ver `live`).
 */
@Composable
internal fun PanelStats() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val ram = remember(tick) { Memory.info(ctx) }
    val w = remember(tick) { ZuiBattery.watts(ctx) }
    val plugged = remember(tick) { ZuiBattery.plugged(ctx) }
    val cpu = remember(tick) { Stats.cpuMhz() }
    val temp = remember(tick) { Stats.batteryTemp(ctx) }
    // Lateral (angosto): en 2 × 2.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP), verticalArrangement = Arrangement.spacedBy(GAP),
        maxItemsInEachRow = if (ThemeState.panelSide) 2 else 4) {
        StatChip(Icons.Outlined.Memory, Memory.gb(ram.freeBytes), tr("RAM libre", "Free RAM"), Modifier.weight(1f))
        // En bypass la batería casi no da ni recibe nada (los watts saldrían ~0): el enchufe y "Bypass".
        val bypass = plugged && ZuiBattery.bypassNow == true && Shell.state == Shell.State.READY
        // Enchufada sin cargar (límite de ZUI o el propio, llena): lo que entra es ~0, se dice por qué.
        val status = remember(tick) { ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }
        val held = plugged && !bypass && status?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) != android.os.BatteryManager.BATTERY_STATUS_CHARGING
        if (bypass) StatChip(Icons.Outlined.Power, "Bypass", tr("Activo", "On"), Modifier.weight(1f))
        else if (held) StatChip(Icons.Outlined.Power, ZuiBattery.heldLabel(ctx, status?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0),
            tr("Sin cargar", "Not charging"), Modifier.weight(1f))
        else StatChip(Icons.Outlined.Bolt, w?.let { (if (plugged) "+" else "") + "%.1f W".format(it) } ?: "—",
            PowerLog.recentAvg?.takeIf { !plugged }?.let { tr("prom. %.1f W", "avg %.1f W").format(it) } ?: if (plugged) tr("Entra", "Charging") else tr("Consumo", "Draw"),
            Modifier.weight(1f))
        StatChip(Icons.Outlined.Speed, Stats.cpuText(cpu), "CPU", Modifier.weight(1f))
        StatChip(Icons.Outlined.Thermostat, temp?.let { "%.0f °C".format(it) } ?: "—", tr("Batería", "Battery"), Modifier.weight(1f))
    }
}

@Composable
internal fun StatChip(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(tileColor).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = PANEL_ACCENT)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, color = PANEL_FG, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, color = PANEL_MUTED, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Ícono de batería como el de la barra de Android: se llena con el nivel; rayo si carga, enchufe si está enchufada sin cargar (límite, bypass). */
@Composable
internal fun BatteryGlyph(level: Int, charging: Boolean, bypass: Boolean, held: Boolean = false) {
    val low = level <= 15 && !charging && !bypass
    androidx.compose.foundation.Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
        val stroke = 1.5.dp.toPx()
        val nub = 2.5.dp.toPx()
        val body = androidx.compose.ui.geometry.Size(size.width - nub, size.height)
        val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(PANEL_MUTED, size = body, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRoundRect(PANEL_MUTED, topLeft = androidx.compose.ui.geometry.Offset(body.width, size.height * 0.3f),
            size = androidx.compose.ui.geometry.Size(nub, size.height * 0.4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
        val pad = stroke + 1.dp.toPx()
        val inner = androidx.compose.ui.geometry.Size((body.width - 2 * pad) * level.coerceIn(0, 100) / 100f, body.height - 2 * pad)
        drawRoundRect(if (low) Color(0xFFFF6B6B) else if (charging || bypass) TILE_ON else PANEL_FG,
            topLeft = androidx.compose.ui.geometry.Offset(pad, pad), size = inner, cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
        if (charging) {
            // Rayo al centro, con borde oscuro para que se vea sobre el relleno.
            val cx = body.width / 2; val h = body.height
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + h * 0.10f, h * 0.12f); lineTo(cx - h * 0.22f, h * 0.56f); lineTo(cx - h * 0.02f, h * 0.56f)
                lineTo(cx - h * 0.10f, h * 0.88f); lineTo(cx + h * 0.22f, h * 0.44f); lineTo(cx + h * 0.02f, h * 0.44f); close()
            }
            drawPath(bolt, PANEL_BG, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawPath(bolt, Color.White)
        } else if (held || bypass) {
            // Enchufe al centro (como la barra de Android con el límite de carga): dos patas y el cuerpo.
            val cx = body.width / 2; val h = body.height
            val plug = androidx.compose.ui.graphics.Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(cx - h * 0.18f, h * 0.14f, cx - h * 0.08f, h * 0.38f))
                addRect(androidx.compose.ui.geometry.Rect(cx + h * 0.08f, h * 0.14f, cx + h * 0.18f, h * 0.38f))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - h * 0.28f, h * 0.36f, cx + h * 0.28f, h * 0.70f,
                    androidx.compose.ui.geometry.CornerRadius(h * 0.10f)))
                addRect(androidx.compose.ui.geometry.Rect(cx - h * 0.06f, h * 0.68f, cx + h * 0.06f, h * 0.88f))
            }
            drawPath(plug, PANEL_BG, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawPath(plug, Color.White)
        }
    }
}

/** Botón pequeño al lado de una barra (brillo automático, silencio): encendido = en color de acento. */
@Composable
internal fun PanelToggle(item: PanelItem, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.size(52.dp).clip(shape).background(if (item.active) TILE_ON else tileColor)
            .then(if (selected) Modifier.border(2.5.dp, SELECTED, shape) else Modifier).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(item.icon, item.label, Modifier.size(22.dp), tint = if (item.active) ON_TILE_ON else PANEL_FG)
    }
}

/** Mosaico: ícono, nombre y estado; encendido = en color de acento. Toma el ancho que le da su fila. Mantenerlo con
 *  el dedo hace lo mismo que Y (la otra acción: cerrar una reciente, encender o apagar el WiFi). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PanelTile(item: PanelItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (item.danger) Color.White else if (item.active) ON_TILE_ON else PANEL_FG
    Surface(
        color = if (item.danger) Color(0xFFE5484D) else if (item.active) TILE_ON else tileColor, shape = RoundedCornerShape(22.dp),
        border = if (selected) BorderStroke(2.5.dp, SELECTED) else if (glassPanel && !item.active) BorderStroke(1.dp, GLASS_EDGE) else null,
        modifier = modifier.clip(RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = item.alt)
            .graphicsLayer { alpha = if (item.enabled) 1f else 0.45f }
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.appIcon != null) androidx.compose.foundation.Image(item.appIcon, null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
            else Icon(item.icon, null, Modifier.size(24.dp), tint = fg)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                item.sub?.let {
                    Text(it, color = fg.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Barra de brillo o volumen, como la de los ajustes rápidos: se llena con el nivel. Con el dedo se toca o se
 * arrastra; con el control, A la activa (se ve más intensa) y ← → la mueven.
 */
@Composable
internal fun PanelSlider(item: PanelItem, selected: Boolean, editing: Boolean, modifier: Modifier, onSet: (Float) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.height(52.dp).clip(shape).background(tileColor)
            .then(if (selected) Modifier.border(2.5.dp, SELECTED, shape) else Modifier)
            .pointerInput(Unit) { detectTapGestures { onSet(it.x / size.width) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onSet(change.position.x / size.width) } }
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((item.level ?: 0f).coerceIn(0.08f, 1f)).clip(shape)
            .background(if (editing) PANEL_ACCENT else TILE_ON.copy(alpha = 0.55f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null, Modifier.size(22.dp), tint = PANEL_FG)
            Spacer(Modifier.width(10.dp))
            Text(item.label, color = PANEL_FG, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            item.sub?.let { Text(it, color = PANEL_FG.copy(alpha = 0.85f), fontSize = 13.sp) }
        }
    }
}

