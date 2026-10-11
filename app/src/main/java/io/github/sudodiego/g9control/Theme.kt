package io.github.sudodiego.g9control

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Paleta inspirada en los fondos de anime de la Y700 (solo los colores): índigo profundo (el pelo), celeste que
// brilla (el anillo y los detalles), blanco hielo y plateado (nieve, cielo) y azul marino (el traje, el fondo
// oscuro); toques de lila. Frío, azul y con brillo celeste.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB2FF),
    onPrimary = Color(0xFF0B1A4A),
    primaryContainer = Color(0xFF23397F),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFF5CC8FF),
    onSecondary = Color(0xFF002235),
    secondaryContainer = Color(0xFF0E3A55),
    onSecondaryContainer = Color(0xFFC9ECFF),
    tertiary = Color(0xFFB3A8FF),
    background = Color(0xFF0D1220),
    onBackground = Color(0xFFE6EBF5),
    surface = Color(0xFF0D1220),
    onSurface = Color(0xFFE6EBF5),
    surfaceVariant = Color(0xFF26324C),
    onSurfaceVariant = Color(0xFF9FAAC2),
    surfaceContainerLowest = Color(0xFF090D18),
    surfaceContainerLow = Color(0xFF111827),
    surfaceContainer = Color(0xFF151E31),
    surfaceContainerHigh = Color(0xFF1A2439),
    surfaceContainerHighest = Color(0xFF202B43),
    outline = Color(0xFF3A4763),
    outlineVariant = Color(0xFF2A3550),
    error = Color(0xFFFF6B7A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3A55C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0F1E5C),
    secondary = Color(0xFF1E8FD6),
    secondaryContainer = Color(0xFFD5EEFF),
    onSecondaryContainer = Color(0xFF04324D),
    tertiary = Color(0xFF7A6CE0),
    background = Color(0xFFEDF2F8),
    onBackground = Color(0xFF131A2B),
    surface = Color(0xFFEDF2F8),
    onSurface = Color(0xFF131A2B),
    surfaceVariant = Color(0xFFDCE3EE),
    onSurfaceVariant = Color(0xFF55617A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F8FC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE6ECF5),
    surfaceContainerHighest = Color(0xFFDDE5F0),
    outline = Color(0xFFAEB9CB),
    outlineVariant = Color(0xFFD2DAE6),
)

enum class AppTheme { SYSTEM, LIGHT, DARK;
    val label get() = when (this) { SYSTEM -> tr("Del sistema", "System"); LIGHT -> tr("Claro", "Light"); DARK -> tr("Oscuro", "Dark") }
}

enum class AppBackground { GRADIENT, IMAGE, WALLPAPER, PLAIN;
    val label get() = when (this) {
        GRADIENT -> tr("Degradado", "Gradient"); IMAGE -> tr("Imagen o GIF", "Image or GIF")
        WALLPAPER -> tr("Fondo de pantalla", "Wallpaper"); PLAIN -> tr("Liso", "Plain")
    }
}

/** Fondo del panel de juego (aparte del de la app): el fondo de pantalla no se puede, el juego está detrás. */
enum class PanelBackground { CLEAR, GRADIENT, IMAGE, PLAIN;
    val label get() = when (this) {
        CLEAR -> tr("Transparente", "Clear"); GRADIENT -> tr("Degradado", "Gradient")
        IMAGE -> tr("Imagen o GIF", "Image or GIF"); PLAIN -> tr("Liso", "Plain")
    }
}

/** Tema y fondo elegidos en Ajustes. */
object ThemeState {
    var theme by androidx.compose.runtime.mutableStateOf(AppTheme.SYSTEM)
    var background by androidx.compose.runtime.mutableStateOf(AppBackground.GRADIENT)
    var backgroundUri by androidx.compose.runtime.mutableStateOf<String?>(null)
    var backgroundBlur by androidx.compose.runtime.mutableIntStateOf(24)
    var backgroundLoop by androidx.compose.runtime.mutableStateOf(true)
    /** Con el fondo de pantalla: cuánto se oscurece (0..100) para que el texto se lea. No se puede desenfocar: la
     *  tablet no tiene desenfoque entre ventanas (ro.surface_flinger.supports_background_blur = 0). */
    var backgroundDim by androidx.compose.runtime.mutableIntStateOf(45)
    var panelBackground by androidx.compose.runtime.mutableStateOf(PanelBackground.GRADIENT)
    var panelUri by androidx.compose.runtime.mutableStateOf<String?>(null)
    var panelBlur by androidx.compose.runtime.mutableIntStateOf(24)
    var panelLoop by androidx.compose.runtime.mutableStateOf(true)
    /** Transparente: cuánto relleno oscuro lleva (0 = nada, se ve el juego entero detrás). */
    var panelFill by androidx.compose.runtime.mutableIntStateOf(25)
    /** Degradado o imagen: cuánto se ve el juego a través (0 = nada, el fondo tapa; 100 = casi invisible). */
    var panelSee by androidx.compose.runtime.mutableIntStateOf(0)
    /** El panel deja ver el juego detrás: transparente, o un fondo con transparencia. */
    val panelSeeThrough get() = panelBackground == PanelBackground.CLEAR ||
        (panelBackground != PanelBackground.PLAIN && panelSee > 0)

    /** Lo elegido, desde la app o desde el servicio (el panel se puede abrir sin haber abierto la app). */
    fun load(ctx: android.content.Context) {
        theme = Prefs.theme(ctx); background = Prefs.background(ctx); backgroundUri = Prefs.backgroundUri(ctx)
        backgroundBlur = Prefs.backgroundBlur(ctx); backgroundLoop = Prefs.backgroundLoop(ctx); backgroundDim = Prefs.backgroundDim(ctx)
        panelBackground = Prefs.panelBackground(ctx); panelUri = Prefs.panelUri(ctx); panelBlur = Prefs.panelBlur(ctx); panelLoop = Prefs.panelLoop(ctx); panelFill = Prefs.panelFill(ctx); panelSee = Prefs.panelSee(ctx)
    }
    /** Con fondo (degradado o imagen) las tarjetas son de vidrio: translúcidas con un borde claro. */
    val glass get() = background != AppBackground.PLAIN
}

@Composable
fun isDarkTheme() = when (ThemeState.theme) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}

@Composable
fun G9Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme()) DarkScheme else LightScheme,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content
    )
}
