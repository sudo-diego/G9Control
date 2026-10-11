package io.github.sudodiego.g9control

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap)

/** Lista de apps de la última vez: se muestra al instante mientras se vuelve a leer en segundo plano. */
private object AppCache { @Volatile var apps: List<AppEntry>? = null }

/**
 * Página "Juegos": cada app con el modo en que se abre. Arriba las opciones y la búsqueda; abajo una
 * cuadrícula de apps. Tocar una app pasa por Android → X-Input → DS5.
 */
@Composable
fun AutoPage(s: AppState, a: MainActivity) {
    if (!s.autoServiceOn) Panel { ServiceSetup(a) }
    AppPicker(s, a)
    // Cómo avisa al abrir y salir de un juego (antes en Ajustes).
    NoticesPanel(s, a)
}

/** Sin el servicio de accesibilidad el cambio por juego no funciona. Los pasos completos están en Ajustes → Permisos. */
@Composable
private fun ServiceSetup(a: MainActivity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(8.dp))
        Text(tr("Necesita el servicio de accesibilidad (Ajustes → Permisos).", "Automatic switching needs the accessibility service (Settings → Permissions)."),
            Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Button(onClick = { AutoRules.openServiceSettings(a) }) { Text(tr("Activar", "Turn on")) }
    }
}

@Composable
private fun AppPicker(s: AppState, a: MainActivity) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(AppCache.apps) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { a.requestAppListPermission() }
    LaunchedEffect(s.appsReload) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != ctx.packageName }
                .map { info ->
                    AppEntry(
                        info.packageName,
                        pm.getApplicationLabel(info).toString(),
                        pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.also { AppCache.apps = it }
    }
    val chosen = s.autoRules
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchBox(query, { query = it }, Modifier.weight(1f))
        InlineSwitch(tr("Cambio automático", "Automatic switching"), s.autoEnabled) { s.autoEnabled = it; AutoRules.setEnabled(a, it) }
        InlineSwitch(tr("Recordar volver a Android", "Remind to return to Android"), s.autoRemindReturn) {
            s.autoRemindReturn = it; AutoRules.setRemindReturn(a, it)
        }
    }
    val list = apps
    if (list == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(tr("Cargando apps…", "Loading apps…"), fontSize = 13.sp)
        }
        return
    }
    val shown = list
        .filter { query.isBlank() || it.label.contains(query, true) }
        .sortedByDescending { it.pkg in chosen.keys }   // las que tienen modo primero
    val cols = if (LocalWide.current) 6 else 3
    Column(
        Modifier.alpha(if (s.autoEnabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        shown.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { app ->
                    val current = chosen[app.pkg]
                    AppCard(app, current, Modifier.weight(1f)) { t ->
                        // Android = sin regla.
                        val next = if (t == null) chosen - app.pkg else chosen + (app.pkg to t)
                        s.autoRules = next
                        AutoRules.setRules(a, next)
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun InlineSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Switch(checked, onChange)
    }
}

/**
 * Búsqueda: un botón que se convierte en campo de texto al tocarlo. Así, al moverse con la cruceta,
 * el foco no entra al campo (que atrapa la cruceta y los botones). Abajo, arriba o B lo sueltan.
 */
@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val manager = LocalFocusManager.current
    if (!open && query.isEmpty()) {
        Box(modifier) {
            OutlinedButton(onClick = { open = true }, shape = CircleShape) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Buscar", "Search"))
            }
        }
        return
    }
    LaunchedEffect(open) { if (open) focus.requestFocus() }
    OutlinedTextField(
        query, onQuery,
        modifier.focusRequester(focus)
            // Se cierra al perder el foco (no en el primer aviso, cuando todavía no lo tenía).
            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) { open = false; hadFocus = false } }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown, Key.DirectionUp, Key.ButtonB, Key.Back -> {
                        manager.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            },
        placeholder = { Text(tr("Buscar", "Search")) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery(""); open = false }) { Icon(Icons.Outlined.Close, tr("Borrar", "Clear")) } },
        singleLine = true, shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { manager.moveFocus(FocusDirection.Down) })
    )
}

private val MODES = listOf(
    Triple<AutoTarget?, String, Color>(null, "Android", Color(0xFF1E3A8A)),
    Triple(AutoTarget.XINPUT, "X-Input", Color(0xFF22C55E)),
    Triple(AutoTarget.DS5, "DS5", Color.White),
)

@Composable
private fun ModeDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))

/** Tarjeta de una app: ícono, nombre y modo. Al tocarla se elige el modo en un menú. */
@Composable
private fun AppCard(app: AppEntry, mode: AutoTarget?, modifier: Modifier, onPick: (AutoTarget?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val (_, name, led) = MODES.first { it.first == mode }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { menu = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = if (mode != null) c.primaryContainer else c.surfaceContainerLow,
            border = if (mode != null) androidx.compose.foundation.BorderStroke(1.5.dp, c.primary) else null
        ) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(app.icon, null, Modifier.size(52.dp))
                Spacer(Modifier.height(8.dp))
                Text(app.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (mode != null) c.onPrimaryContainer else c.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeDot(led)
                    Spacer(Modifier.width(6.dp))
                    Text(name, fontSize = 12.sp, fontWeight = if (mode != null) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (mode != null) c.onPrimaryContainer else c.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            Text(app.label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
            MODES.forEach { (t, label, color) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { ModeDot(color, 12.dp) },
                    trailingIcon = { if (t == mode) Icon(Icons.Outlined.Check, null) },
                    onClick = { menu = false; if (t != mode) onPick(t) }
                )
            }
        }
    }
}
