package io.github.sudodiego.g9control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/** Desplazamiento de la página abierta: la vista previa del panel lo usa para quedarse a la vista. */
val LocalPageScroll = compositionLocalOf<androidx.compose.foundation.ScrollState?> { null }

/**
 * Pestaña Panel: a un lado la vista previa en vivo (el panel de verdad, a escala, sobre una pantalla de juego de
 * muestra) y al otro sus opciones. Lo que se cambia se ve al instante: el panel lee [ThemeState], igual que la
 * vista previa. En pantalla ancha la vista previa queda fija arriba mientras se bajan las opciones.
 */
@Composable
fun PanelPage(s: AppState, a: MainActivity) {
    if (LocalWide.current) {
        var rowH by remember { mutableIntStateOf(0) }
        var previewH by remember { mutableIntStateOf(0) }
        val scroll = LocalPageScroll.current
        Row(Modifier.onGloballyPositioned { rowH = it.size.height }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.onGloballyPositioned { previewH = it.size.height }
                    .graphicsLayer { translationY = (scroll?.value ?: 0).coerceAtMost((rowH - previewH).coerceAtLeast(0)).toFloat() }) {
                    PanelPreviewCard()
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { Gate(s.autoServiceOn) { PanelLookPanel(a) } }
        }
    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelPreviewCard()
        Gate(s.autoServiceOn) { PanelLookPanel(a) }
    }
}

@Composable
private fun PanelPreviewCard() {
    Panel(tr("Vista previa", "Preview")) {
        PanelPreview()
        Hint(tr("Así se ve sobre un juego. Los cambios se ven al instante.", "This is how it looks over a game. Changes show right away."))
    }
}

/**
 * El panel a escala dentro de una "pantalla" con la forma de la tablet: misma proporción, mismo tamaño relativo y
 * mismo estilo (al centro o lateral). Debajo, un juego de muestra (un degradado con formas) para ver la transparencia.
 */
@Composable
fun PanelPreview() {
    val ctx = LocalContext.current
    val conf = ctx.resources.configuration
    val ui = remember { sampleUi() }
    ui.stats = Prefs.panelStats(ctx)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val f = maxWidth.value / conf.screenWidthDp
        val h = (conf.screenHeightDp * f).dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(14.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376), Color(0xFF1D976C)))
        )) {
            // "Juego" de muestra: unas formas para que se note lo que deja ver la transparencia.
            Text("GAME", color = Color.White.copy(alpha = 0.25f), fontSize = (h.value / 4).sp,
                modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = 20f })
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * panelScale * f, base.fontScale)) {
                val side = ThemeState.panelSide
                Box(Modifier.fillMaxSize(), contentAlignment = if (side) Alignment.CenterEnd else Alignment.Center) {
                    PanelCard(ui, Modifier.wrapContentWidth(unbounded = true), onPick = { ui.sel = it }, onSet = { _, _ -> })
                }
            }
        }
    }
}

/** Ítems de muestra, como en un juego: barras, juego, recientes, pantalla y Android. */
private fun sampleUi() = PanelUi().apply {
    title = "Wind Waker"
    battery = 72; status = "72 % · 21:30"; mode = "DS5"
    items = listOf(
        PanelItem(tr("Brillo", "Brightness"), Icons.Outlined.BrightnessMedium, PanelRow.SLIDER, sub = "60 %", level = 0.6f, set = {}),
        PanelItem(tr("Brillo automático", "Auto brightness"), Icons.Outlined.BrightnessAuto, PanelRow.SLIDER),
        PanelItem(tr("Volumen", "Volume"), Icons.AutoMirrored.Outlined.VolumeUp, PanelRow.SLIDER, sub = "40 %", level = 0.4f, set = {}),
        PanelItem(tr("Silenciar", "Mute"), Icons.AutoMirrored.Outlined.VolumeOff, PanelRow.SLIDER),
        PanelItem(tr("Captura", "Screenshot"), Icons.Outlined.Screenshot, sub = tr("Guía + RB", "Guide + RB")),
        PanelItem(tr("Grabar", "Record"), Icons.Outlined.Videocam, sub = tr("Guía + LT", "Guide + LT")),
        PanelItem(tr("Monitor", "Monitor"), Icons.Outlined.QueryStats, sub = tr("Apagado", "Off")),
        PanelItem(tr("Bloquear gestos", "Lock gestures"), Icons.Outlined.Lock, active = true, sub = tr("En juegos", "In games")),
        PanelItem("RetroArch", Icons.Outlined.Layers, PanelRow.RECENT, sub = tr("Volver", "Resume")),
        PanelItem("YouTube", Icons.Outlined.Layers, PanelRow.RECENT, sub = tr("Volver", "Resume")),
        PanelItem("16:9", Icons.Outlined.DisplaySettings, PanelRow.SCREEN, sub = tr("Apagada", "Off")),
        PanelItem("WiFi", Icons.Outlined.Wifi, PanelRow.SCREEN, active = true, sub = tr("Redes", "Networks")),
        PanelItem("Bluetooth", Icons.Outlined.Bluetooth, PanelRow.SCREEN, sub = tr("Dispositivos", "Devices")),
        PanelItem(tr("Atrás", "Back"), Icons.AutoMirrored.Outlined.ArrowBack, PanelRow.SYSTEM),
        PanelItem(tr("Inicio", "Home"), Icons.Outlined.Home, PanelRow.SYSTEM),
        PanelItem(tr("Ajustes", "Settings"), Icons.Outlined.Settings, PanelRow.SYSTEM),
    )
    sel = 4
}
