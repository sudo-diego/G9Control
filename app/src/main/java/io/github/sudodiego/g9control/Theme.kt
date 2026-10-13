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

/**
 * Color de acento, aparte para la app y para el panel (y sus avisos y guías): [base] es el color elegido (ARGB) o
 * null para el celeste de siempre. De él salen sus tonos: más claro y suave en oscuro, más profundo en claro, y un
 * fondo apagado ([box]) para lo que va detrás del texto.
 */
data class Accent(val base: Long?) {
    private fun hsv() = FloatArray(3).also { android.graphics.Color.colorToHSV(base!!.toInt(), it) }
    private fun hsvColor(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
    fun color(light: Boolean): Color {
        if (base == null) return Color(if (light) 0xFF2F8FD8 else 0xFF6EC9FF)
        val (h, s, v) = hsv().let { Triple(it[0], it[1], it[2]) }
        return if (light) hsvColor(h, s.coerceAtLeast(0.15f * s), minOf(v, 0.72f)) else hsvColor(h, s * 0.6f, maxOf(v, 0.95f))
    }
    fun box(light: Boolean): Color {
        if (base == null) return Color(if (light) 0xFFD5EEFF else 0xFF0E3A55)
        val (h, s) = hsv().let { it[0] to it[1] }
        return if (light) hsvColor(h, s * 0.18f, 0.98f) else hsvColor(h, s * 0.65f, 0.32f)
    }
    /** "#RRGGBB" (el celeste también, para el campo de código). */
    val hex get() = "#%06X".format(((base ?: 0xFF6EC9FF) and 0xFFFFFF))
    companion object {
        val SKY = Accent(null)
        /** Atajos: celeste (el de siempre), violeta, verde, naranja, rosa, rojo y gris. */
        val PRESETS = listOf(SKY, Accent(0xFF9C7CFF), Accent(0xFF3DBE7A), Accent(0xFFFF8A3D), Accent(0xFFFF6FA8), Accent(0xFFFF5A5A), Accent(0xFF9AA3AE))
        /** "#RRGGBB" o "RRGGBB"; null si no es un color. */
        fun parse(text: String): Accent? = text.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Accent(0xFF000000 or it) }
    }
}
enum class AppTheme { SYSTEM, LIGHT, DARK;
    val label get() = when (this) { SYSTEM -> tr("Del sistema", "System"); LIGHT -> tr("Claro", "Light"); DARK -> tr("Oscuro", "Dark") }
}

enum class AppBackground { GRADIENT, IMAGE, WALLPAPER, PLAIN;
    val label get() = when (this) {
        GRADIENT -> tr("Degradado", "Gradient"); IMAGE -> tr("Imagen o GIF", "Image or GIF")
        WALLPAPER -> tr("Fondo de pantalla", "Wallpaper"); PLAIN -> tr("Liso", "Plain")
    }
}

/**
 * Ancho del panel al centro, en columnas de mosaicos (3 a 6); el alto lo pone el contenido. Antes eran proporciones
 * fijas (16:9, 16:10, 4:3, 1:1) que estiraban o aplastaban los mosaicos para llenar el alto: se veían mal. Los nombres
 * se mantienen por las preferencias guardadas (R4_3 = 4 columnas, como AUTO). [ratio] queda siempre en null.
 */
enum class PanelShape(val cols: Int, val ratio: Float? = null) {
    AUTO(4), R16_9(6), R16_10(5), R4_3(4), R1_1(3);
    val label get() = "$cols"
    companion object {
        /** Las que se ofrecen, de angosta a ancha. */
        val choices = listOf(R1_1, AUTO, R16_10, R16_9)
    }
}


/** Fondo del panel de juego (aparte del de la app): el fondo de pantalla no se puede, el juego está detrás. */
enum class PanelBackground { CLEAR, GRADIENT, IMAGE, PLAIN;
    val label get() = when (this) {
        CLEAR -> tr("Transparente", "Clear"); GRADIENT -> tr("Degradado", "Gradient")
        IMAGE -> tr("Imagen o GIF", "Image or GIF"); PLAIN -> tr("Liso", "Plain")
    }
}

/**
 * Estilo del panel de juego: al centro (una tarjeta, con forma y tamaño a elección) o lateral, pegado a la derecha y de
 * alto completo, como el acceso rápido del Steam Deck (con la imagen o el GIF como cabecera).
 */
enum class PanelStyle { CENTER, SIDE;
    val label get() = when (this) { CENTER -> tr("Al centro", "Centered"); SIDE -> tr("Lateral", "Side") }
}

/** Cómo se acomoda la imagen o el GIF del panel, como los fondos de pantalla de Windows. */
enum class ImageFit { FILL, FIT, STRETCH, CENTER;
    val label get() = when (this) {
        FILL -> tr("Rellenar", "Fill"); FIT -> tr("Ajustar", "Fit"); STRETCH -> tr("Estirar", "Stretch"); CENTER -> tr("Centrar", "Center")
    }
}

/** Tema del panel de juego: oscuro (el de siempre), claro o el del sistema. Vale para todos sus fondos. */
enum class PanelTone { DARK, LIGHT, SYSTEM;
    val label get() = when (this) { DARK -> tr("Oscuro", "Dark"); LIGHT -> tr("Claro", "Light"); SYSTEM -> tr("Sistema", "System") }
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
    var panelShape by androidx.compose.runtime.mutableStateOf(PanelShape.AUTO)
    /** Tamaño del panel en % (todo: mosaicos y letras); 100 = el diseño original, que ocupaba casi todo el alto. */
    var panelScale by androidx.compose.runtime.mutableIntStateOf(85)
    var panelTone by androidx.compose.runtime.mutableStateOf(PanelTone.DARK)
    var panelStyle by androidx.compose.runtime.mutableStateOf(PanelStyle.CENTER)
    var panelFit by androidx.compose.runtime.mutableStateOf(ImageFit.FILL)
    /** Colores de acento: el de la app y el del panel (avisos y guías incluidos). */
    /** Mosaicos del panel: nuevos (ícono en una insignia, más sobrios) o los clásicos (píldoras llenas de color). */
    var panelClassic by androidx.compose.runtime.mutableStateOf(false)
    /** Imagen propia para el fondo de la vista previa del panel (null = el degradado). Queda solo en la tablet. */
    var previewUri by androidx.compose.runtime.mutableStateOf<String?>(null)
    var appAccent by androidx.compose.runtime.mutableStateOf(Accent.SKY)
    var panelAccent by androidx.compose.runtime.mutableStateOf(Accent.SKY)
    /** Lateral: la imagen como cabecera (true) o de fondo de todo el panel. */
    var panelHeader by androidx.compose.runtime.mutableStateOf(true)
    /** Lateral con cabecera: alto de la cabecera (dp) y si la transparencia también aclara la imagen. */
    var panelHeaderH by androidx.compose.runtime.mutableIntStateOf(150)
    var panelSeeImage by androidx.compose.runtime.mutableStateOf(false)
    /** Lateral con cabecera: la imagen arriba y el resto liso (con su transparencia). El degradado va siempre de fondo. */
    val sideBanner get() = panelSide && panelHeader && panelBackground == PanelBackground.IMAGE
    /** Rellenar: qué parte de la imagen queda a la vista (0 = arriba, 0,5 = centro, 1 = abajo). */
    var panelFocus by androidx.compose.runtime.mutableFloatStateOf(0.5f)
    /** El panel va pegado a la derecha (estilo Steam). */
    val panelSide get() = panelStyle == PanelStyle.SIDE
    /** El sistema está en modo oscuro (para el tema "Sistema"); lo actualiza el panel al abrirse. */
    var systemDark by androidx.compose.runtime.mutableStateOf(true)
    /** El panel va en claro. */
    val panelLight get() = panelTone == PanelTone.LIGHT || (panelTone == PanelTone.SYSTEM && !systemDark)
    /** El panel deja ver el juego detrás: transparente, o un fondo con transparencia. */
    val panelSeeThrough get() = panelBackground == PanelBackground.CLEAR ||
        (panelBackground != PanelBackground.PLAIN && panelSee > 0)

    /** Lo elegido, desde la app o desde el servicio (el panel se puede abrir sin haber abierto la app). */
    fun load(ctx: android.content.Context) {
        theme = Prefs.theme(ctx); background = Prefs.background(ctx); backgroundUri = Prefs.backgroundUri(ctx)
        backgroundBlur = Prefs.backgroundBlur(ctx); backgroundLoop = Prefs.backgroundLoop(ctx); backgroundDim = Prefs.backgroundDim(ctx)
        panelBackground = Prefs.panelBackground(ctx); panelUri = Prefs.panelUri(ctx); panelBlur = Prefs.panelBlur(ctx); panelLoop = Prefs.panelLoop(ctx); panelFill = Prefs.panelFill(ctx); panelSee = Prefs.panelSee(ctx)
        panelShape = Prefs.panelShape(ctx); panelScale = Prefs.panelScale(ctx); panelTone = Prefs.panelTone(ctx); panelStyle = Prefs.panelStyle(ctx); panelFit = Prefs.panelFit(ctx); appAccent = Prefs.accent(ctx, "app"); panelClassic = Prefs.panelClassic(ctx); previewUri = Prefs.previewUri(ctx); panelAccent = Prefs.accent(ctx, "panel"); panelHeader = Prefs.panelHeader(ctx); panelHeaderH = Prefs.panelHeaderH(ctx); panelSeeImage = Prefs.panelSeeImage(ctx); panelFocus = Prefs.panelFocus(ctx)
        // La imagen del panel ya decodificada: al abrirlo no aparece primero el fondo liso.
        panelUri?.takeIf { panelBackground == PanelBackground.IMAGE }?.let { ImageCache.preload(ctx, "panel", it) }
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
    val dark = isDarkTheme()
    val acc = ThemeState.appAccent
    // Celeste: los colores originales (índigo y celeste). Otro acento: el principal y el secundario de ese color.
    val scheme = (if (dark) DarkScheme else LightScheme).let { s ->
        if (acc == Accent.SKY) s else s.copy(
            primary = acc.color(!dark), onPrimary = if (dark) acc.box(false) else Color.White,
            primaryContainer = acc.box(!dark), onPrimaryContainer = if (dark) acc.box(true) else acc.box(false),
            secondary = acc.color(!dark), onSecondary = if (dark) acc.box(false) else Color.White,
            secondaryContainer = acc.box(!dark), onSecondaryContainer = if (dark) acc.box(true) else acc.box(false),
            tertiary = acc.color(!dark),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content
    )
}
