package io.github.sudodiego.g9control

import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fondo de la app, detrás de todas las páginas. Degradado: azul marino a índigo con dos brillos (celeste y lila) que
 * se mueven muy lento, como los fondos de la Y700. Imagen o GIF: el que se eligió, animado y desenfocado, con un velo
 * para que el texto se lea. Liso: el color del tema.
 */
@Composable
fun Backdrop() {
    val dark = isDarkTheme()
    val base = MaterialTheme.colorScheme.background
    when (ThemeState.background) {
        AppBackground.PLAIN -> Box(Modifier.fillMaxSize().background(base))
        AppBackground.GRADIENT -> GradientBackdrop(dark)
        AppBackground.IMAGE -> {
            val uri = ThemeState.backgroundUri
            if (uri == null) GradientBackdrop(dark) else ImageBackdrop(uri, ThemeState.backgroundBlur, ThemeState.backgroundLoop, dark)
        }
        // El fondo de pantalla lo dibuja Android detrás de la ventana; el velo lo pinta el fondo de la ventana
        // (MainActivity), que cubre también la barra de estado. Aquí nada.
        AppBackground.WALLPAPER -> Unit
    }
}

/**
 * Fondo del panel de juego (siempre oscuro, va sobre el juego): el suyo propio, no el de la app. El degradado y la
 * imagen pueden ser translúcidos: el juego se ve a través de ellos (liquid glass).
 */
@Composable
/** [opaque]: sin la transparencia elegida (la cabecera del lateral va sobre fondo liso). */
fun PanelBackdrop(opaque: Boolean = false) {
    val see = if (!opaque && (ThemeState.panelBackground == PanelBackground.GRADIENT || ThemeState.panelBackground == PanelBackground.IMAGE))
        ThemeState.panelSee / 100f * 0.85f else 0f
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - see }) { PanelBackdropLayer() }
}

@Composable
private fun PanelBackdropLayer() {
    when (ThemeState.panelBackground) {
        PanelBackground.PLAIN -> Box(Modifier.fillMaxSize().background(Color(0xFF0E1424)))
        // Transparente: el juego se ve detrás, con el relleno elegido (un velo oscuro, de nada a casi opaco).
        PanelBackground.CLEAR -> Box(Modifier.fillMaxSize().background((if (ThemeState.panelLight) Color(0xFFF2F5FA) else Color(0xFF0A0F1F))
            .copy(alpha = ThemeState.panelFill / 100f * 0.9f)))
        PanelBackground.GRADIENT -> GradientBackdrop(true)
        PanelBackground.IMAGE -> {
            val uri = ThemeState.panelUri
            if (uri == null) GradientBackdrop(true) else ImageBackdrop(uri, ThemeState.panelBlur, ThemeState.panelLoop, true, "panel",
                ThemeState.panelFit, ThemeState.panelFocus)
        }
    }
}

@Composable
private fun GradientBackdrop(dark: Boolean) {
    val t = rememberInfiniteTransition(label = "fondo")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "brillo")
    val top = if (dark) Color(0xFF0A0F1F) else Color(0xFFEAF0FA)
    val bottom = if (dark) Color(0xFF172552) else Color(0xFFD6DFF6)
    val cyan = if (dark) Color(0x445CC8FF) else Color(0x335CC8FF)
    val lilac = if (dark) Color(0x338F7CFF) else Color(0x267A6CE0)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(size.width * 0.4f, size.height)))
        val r = size.maxDimension * 0.55f
        drawCircle(Brush.radialGradient(listOf(cyan, Color.Transparent), Offset(size.width * (0.78f + 0.08f * drift), size.height * (0.12f + 0.1f * drift)), r),
            r, Offset(size.width * (0.78f + 0.08f * drift), size.height * (0.12f + 0.1f * drift)))
        drawCircle(Brush.radialGradient(listOf(lilac, Color.Transparent), Offset(size.width * (0.15f - 0.06f * drift), size.height * (0.9f - 0.1f * drift)), r),
            r, Offset(size.width * (0.15f - 0.06f * drift), size.height * (0.9f - 0.1f * drift)))
    }
}

/**
 * Imágenes de fondo ya decodificadas, por lugar ("app" o "panel": un GIF animado solo puede estar en una vista a la
 * vez) y archivo. Así el panel abre con su imagen al instante (antes se veía un momento el degradado).
 */
object ImageCache {
    private val map = mutableMapOf<String, Drawable>()
    @Synchronized fun get(place: String, uri: String) = map["$place|$uri"]
    @Synchronized private fun put(place: String, uri: String, d: Drawable) { map.keys.removeAll { it.startsWith("$place|") }; map["$place|$uri"] = d }
    fun decode(ctx: android.content.Context, place: String, uri: String): Drawable? = get(place, uri)
        ?: runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))) }.getOrNull()
            ?.also { put(place, uri, it) }
    fun preload(ctx: android.content.Context, place: String, uri: String) {
        if (get(place, uri) == null) Thread { decode(ctx.applicationContext, place, uri) }.start()
    }
}

@Composable
/**
 * [fit] y [focus] (solo el panel): cómo se acomoda la imagen. Rellenar recorta con el encuadre elegido (arriba, centro
 * o abajo, con una matriz); Ajustar la muestra entera (con bordes); Estirar la deforma al tamaño; Centrar, tamaño real.
 */
private fun ImageBackdrop(uri: String, blur: Int, loop: Boolean, dark: Boolean, place: String = "app",
                          fit: ImageFit = ImageFit.FILL, focus: Float = 0.5f) {
    val ctx = LocalContext.current
    var drawable by remember(uri) { mutableStateOf(ImageCache.get(place, uri)) }
    LaunchedEffect(uri) {
        if (drawable == null) drawable = withContext(Dispatchers.IO) { ImageCache.decode(ctx, place, uri) }
    }
    Box(Modifier.fillMaxSize()) {
        val d = drawable
        if (d == null) GradientBackdrop(dark)
        else AndroidView(
            factory = { ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // Rellenar con encuadre: la matriz depende del tamaño, se recalcula al medir.
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> (view.tag as? Runnable)?.run() }
            } },
            update = { v ->
                v.scaleType = when (fit) {
                    ImageFit.FILL -> ImageView.ScaleType.MATRIX
                    ImageFit.FIT -> ImageView.ScaleType.FIT_CENTER
                    ImageFit.STRETCH -> ImageView.ScaleType.FIT_XY
                    ImageFit.CENTER -> ImageView.ScaleType.CENTER
                }
                val crop = Runnable {
                    val w = v.width.toFloat(); val h = v.height.toFloat()
                    val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
                    if (fit == ImageFit.FILL && w > 0 && h > 0 && dw > 0 && dh > 0) {
                        val s = maxOf(w / dw, h / dh)
                        v.imageMatrix = android.graphics.Matrix().apply {
                            setScale(s, s)
                            postTranslate((w - dw * s) / 2f, (h - dh * s) * focus.coerceIn(0f, 1f))
                        }
                    }
                }
                v.tag = crop
                crop.run()
                (d as? AnimatedImageDrawable)?.let { gif ->
                    // Repetir o quedarse en el último cuadro.
                    val want = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
                    if (gif.repeatCount != want) { gif.repeatCount = want; gif.stop(); gif.start() }
                }
                if (v.drawable !== d) { v.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() }
                if (Build.VERSION.SDK_INT >= 31)
                    v.setRenderEffect(if (blur > 0) RenderEffect.createBlurEffect(blur.toFloat(), blur.toFloat(), Shader.TileMode.CLAMP) else null)
            },
            modifier = Modifier.fillMaxSize()
        )
        // Velo: el texto se lee aunque la imagen sea clara u oscura.
        Box(Modifier.fillMaxSize().background(if (dark) Color(0x8C0A0F1F) else Color(0x80EAF0FA)))
    }
}
