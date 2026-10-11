package io.github.sudodiego.g9control

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Diálogo abierto en la página: nombre nuevo, renombrar o eliminar. */
private sealed interface ProfileDialog {
    data object New : ProfileDialog
    data class Rename(val p: Profile) : ProfileDialog
    data class Delete(val p: Profile) : ProfileDialog
}

@Composable
fun ProfilesPage(s: AppState, a: MainActivity) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    val known = s.configKnown && s.connected

    Panel(
        tr("Perfiles", "Profiles"),
        trailing = {
            Button(onClick = { dialog = ProfileDialog.New }, enabled = known) {
                Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Guardar como perfil", "Save as profile"))
            }
        }
    ) {
        if (!known) Hint(tr("Conecta el control en modo Android.", "Connect the controller in Android mode."))
        // Respaldo en un archivo: sirve para no perder los perfiles al desinstalar o cambiar de tablet.
        CompositionLocalProvider(LocalEditable provides LocalGateOk.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { a.exportProfiles() }, enabled = s.profiles.isNotEmpty()) { Text(tr("Exportar respaldo", "Export backup")) }
                OutlinedButton(onClick = { a.importProfiles() }) { Text(tr("Importar respaldo", "Import backup")) }
            }
        }
    }

    if (s.profiles.isNotEmpty()) {
        s.profiles.forEach { p ->
            ProfileCard(
                p, active = known && p.matches(s), canApply = known && !s.busy,
                onApply = { a.applyProfile(p) },
                onOverwrite = { a.saveProfiles(s.profiles.map { if (it.id == p.id) Profile.fromState(p.id, p.name, s) else it }) },
                onRename = { dialog = ProfileDialog.Rename(p) },
                onDelete = { dialog = ProfileDialog.Delete(p) }
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        ProfileDialog.New -> NameDialog(tr("Nuevo perfil", "New profile"), tr("Perfil ${s.profiles.size + 1}", "Profile ${s.profiles.size + 1}"), onDismiss = { dialog = null }) { name ->
            a.saveProfiles(s.profiles + Profile.fromState(System.currentTimeMillis(), name, s))
            dialog = null
        }
        is ProfileDialog.Rename -> NameDialog(tr("Renombrar perfil", "Rename profile"), d.p.name, onDismiss = { dialog = null }) { name ->
            a.saveProfiles(s.profiles.map { if (it.id == d.p.id) it.copy(name = name) else it })
            dialog = null
        }
        is ProfileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("¿Eliminar «${d.p.name}»?", "Delete “${d.p.name}”?")) },
            text = { Text(tr("Se borra solo de la tablet; el control no cambia.", "It's only deleted from the tablet; the controller doesn't change.")) },
            confirmButton = {
                TextButton(onClick = {
                    a.saveProfiles(s.profiles.filter { it.id != d.p.id })
                    dialog = null
                }) { Text(tr("Eliminar", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancelar", "Cancel")) } }
        )
    }
}

@Composable
private fun ProfileCard(
    p: Profile, active: Boolean, canApply: Boolean,
    onApply: () -> Unit, onOverwrite: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (active) {
                        Spacer(Modifier.width(10.dp))
                        Tag(tr("En uso", "In use"), Color(0xFF3DDC84))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorDot(p.left)
                    ColorDot(p.right)
                    ColorDot(p.home.copy(effect = 1, lum = if (p.homeOff) 0 else p.home.lum))
                    Spacer(Modifier.width(4.dp))
                    Text(summary(p), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            if (!active) FilledTonalButton(onClick = onApply, enabled = canApply) { Text(tr("Aplicar", "Apply")) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, tr("Más opciones", "More options")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Reemplazar con la configuración actual", "Replace with current settings")) },
                        leadingIcon = { Icon(Icons.Outlined.Save, null) },
                        enabled = canApply,
                        onClick = { menu = false; onOverwrite() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Renombrar", "Rename")) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Eliminar", "Delete")) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

private fun summary(p: Profile): String {
    val v = p.vibration
    val vib = tr("Vibración", "Vibration") + if (gripLevel(v.leftGrip) == gripLevel(v.rightGrip)) " ${gripLevel(v.leftGrip)}"
    else " ${gripLevel(v.leftGrip)}/${gripLevel(v.rightGrip)}"
    val dl = p.deadZones[Gcm.DZ_L_INNER]
    val dr = p.deadZones[Gcm.DZ_R_INNER]
    val dz = tr("Zona muerta", "Dead zone") + if (dl == dr) " $dl%" else " $dl% / $dr%"
    val quick = when {
        p.quickL && p.quickR -> tr(" · Gatillo rápido", " · Hair trigger")
        p.quickL -> tr(" · Gatillo rápido LT", " · LT hair trigger")
        p.quickR -> tr(" · Gatillo rápido RT", " · RT hair trigger")
        else -> ""
    }
    val dpad = (if (p.dpadLock) tr(" · Sin diagonales", " · No diagonals") else "") + (if (p.swapDpad) tr(" · Stick ↔ cruceta", " · Stick ↔ D-pad") else "")
    val back = BackKey.entries.mapNotNull { k ->
        when (val v = p.backKeys[k.id]) { null, 0 -> null; BACK_MACRO -> "${k.label} macro"; else -> "${k.label} ${backTargetName(v)}" }
    }.joinToString(", ").let { if (it.isEmpty()) "" else " · $it" }
    return "$vib · $dz$quick$dpad$back"
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                singleLine = true, label = { Text(tr("Nombre", "Name")) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Guardar", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar", "Cancel")) } }
    )
}
