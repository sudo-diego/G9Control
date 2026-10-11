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
            if (uri == null) GradientBackdrop(dark) else ImageBackdrop(uri, ThemeState.backgroundBlur, dark)
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

@Composable
private fun ImageBackdrop(uri: String, blur: Int, dark: Boolean) {
    val ctx = LocalContext.current
    var drawable by remember(uri) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(uri) {
        drawable = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))) }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize()) {
        val d = drawable
        if (d == null) GradientBackdrop(dark)
        else AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            update = { v ->
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
