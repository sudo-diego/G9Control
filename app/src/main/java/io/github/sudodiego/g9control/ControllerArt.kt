package io.github.sudodiego.g9control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/*
 * Ilustración frontal y simétrica del G9, con medidas tomadas de una foto del control
 * (20261008_113826.jpg) y promediadas entre ambos lados para quitar la perspectiva.
 * Se define el lado izquierdo; el derecho es su espejo (x → 1 − x). Las luces:
 * - Tiras: borde exterior de cada empuñadura, bajan en vertical y doblan en diagonal hacia la esquina.
 * - Botón Home: anillo de luz en el lado derecho, espejo del botón M.
 * Coordenadas normalizadas: x en fracción del ancho, y en fracción del alto.
 */
private const val ASPECT = 3680f / 1460f

private fun mirror(o: Offset) = Offset(1f - o.x, o.y)

private val STRIP_L = listOf(Offset(0.0465f, 0.454f), Offset(0.0595f, 0.680f), Offset(0.109f, 0.866f))
private val STRIP_R = STRIP_L.map(::mirror)
private val M_KEY = Offset(0.1315f, 0.575f)
private val HOME = mirror(M_KEY)
private val STICK_L = Offset(0.075f, 0.185f)       // a la altura de ABXY
private val ABXY = mirror(STICK_L)
private val DPAD = Offset(0.121f, 0.40f)           // a la altura del stick derecho
private val STICK_R = mirror(DPAD)
private val SMALL_KEY = Offset(0.146f, 0.075f)     // vista (izq.) y menú (der.)
private val SLIT = Offset(0.172f, 0.12f) to Offset(0.172f, 0.255f)
private val CLAMP = listOf(Offset(0.200f, 0.38f), Offset(0.176f, 0.45f), Offset(0.176f, 0.71f), Offset(0.200f, 0.79f))
private const val HOME_R = 0.0397f       // radio del anillo, en fracción del alto
private const val STRIP_W = 0.0125f      // grosor de la tira, en fracción del alto

/** Colores de cada estilo del dibujo. */
class Palette(
    val ledOff: Color, val bodyTop: Color, val bodyBottom: Color, val edge: Color,
    val key: Color, val keyHi: Color, val label: Color,
    val frame: Color, val screen: Color, val clampFill: Color, val clampEdge: Color,
    val bumperTop: Color, val bumperBottom: Color,
    val stickRim: Color, val stickWell: Color, val stickCap: Color, val stickRidge: Color,
    val stickCapHi: Color, val stickCapLo: Color,
    /** Sin texturas (estrías de los sticks, brillo de la pantalla, sombras). */
    val flat: Boolean = false,
)

/** Estilos del dibujo del control: el gris original, uno simple y claro, y el verde (Rhein Green). */
enum class ControllerSkin(private val es: String, private val en: String, val palette: Palette) {
    CARBON("Gris carbón", "Charcoal", Palette(
        ledOff = Color(0xFF34313A), bodyTop = Color(0xFF55515E), bodyBottom = Color(0xFF3C3944), edge = Color(0xFF6E6979),
        key = Color(0xFF16161A), keyHi = Color(0xFF2C2C33), label = Color(0xFFCFCFD6),
        frame = Color(0xFF8D8A96), screen = Color(0xFF0B0B0E), clampFill = Color(0x4DB9B5C4), clampEdge = Color(0x8CD9D6E0),
        bumperTop = Color(0xFF2E2C34), bumperBottom = Color(0xFF141317),
        stickRim = Color(0xFF615C6B), stickWell = Color(0xFF0E0E11), stickCap = Color(0xFF1E1E23), stickRidge = Color(0xFF34343B),
        stickCapHi = Color(0xFF34343C), stickCapLo = Color(0xFF1B1B20),
    )),
    SIMPLE("Simple", "Simple", Palette(
        ledOff = Color(0xFFD3D3DA), bodyTop = Color(0xFFECECF1), bodyBottom = Color(0xFFECECF1), edge = Color(0xFFB4B4BE),
        key = Color(0xFFFFFFFF), keyHi = Color(0xFFC6C6CF), label = Color(0xFF6A6A75),
        frame = Color(0xFFD6D6DD), screen = Color(0xFFF8F8FB), clampFill = Color(0x80FFFFFF), clampEdge = Color(0xFFB4B4BE),
        bumperTop = Color(0xFFD6D6DD), bumperBottom = Color(0xFFC6C6CF),
        stickRim = Color(0xFFC6C6CF), stickWell = Color(0xFFF4F4F7), stickCap = Color(0xFFFFFFFF), stickRidge = Color(0xFFE2E2E8),
        stickCapHi = Color(0xFFFFFFFF), stickCapLo = Color(0xFFF1F1F5), flat = true,
    )),
    GREEN("Verde", "Green", Palette(
        ledOff = Color(0xFF2E3A1C), bodyTop = Color(0xFFC6F03E), bodyBottom = Color(0xFF9FD62C), edge = Color(0xFFE0F88A),
        // Botones, sticks y cruceta también son verdes en este modelo (un poco más oscuros que el cuerpo).
        key = Color(0xFF8CC21F), keyHi = Color(0xFFB4E640), label = Color(0xFF26330D),
        frame = Color(0xFFAFDC38), screen = Color(0xFF0B0B0E), clampFill = Color(0x4DFFFFFF), clampEdge = Color(0x99F2FFD0),
        bumperTop = Color(0xFF9ED62C), bumperBottom = Color(0xFF7AAE1A),
        stickRim = Color(0xFF6E961C), stickWell = Color(0xFF55761A), stickCap = Color(0xFF8CC21F), stickRidge = Color(0xFFA5DA34),
        stickCapHi = Color(0xFFB4E640), stickCapLo = Color(0xFF86BB1D),
    ));

    val label get() = tr(es, en)
}

/** Estilo elegido (se guarda en Prefs); los dibujos lo leen al pintar, así cambian al instante. */
object SkinState {
    var skin by mutableStateOf(ControllerSkin.CARBON)
}

private val pal get() = SkinState.skin.palette

@Composable
fun ControllerPreview(
    s: AppState, modifier: Modifier, selected: LightTarget? = null, labels: Boolean = false,
    onTap: ((LightTarget) -> Unit)? = null
) {
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { time = it - start }
    }
    val onTapNow by rememberUpdatedState(onTap)

    Box(modifier.aspectRatio(ASPECT)) {
        val text = rememberTextMeasurer()
        Canvas(Modifier.matchParentSize()) { controllerBody(text) }
        Canvas(
            Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { p ->
                    val cb = onTapNow ?: return@detectTapGestures
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val targets = listOf(
                        LightTarget.LEFT to STRIP_L[1].scale(w, h),
                        LightTarget.RIGHT to STRIP_R[1].scale(w, h),
                        LightTarget.HOME to HOME.scale(w, h),
                    )
                    val (t, o) = targets.minBy { hypot(it.second.x - p.x, it.second.y - p.y) }
                    if (hypot(o.x - p.x, o.y - p.y) < h * 0.3f) cb(t)
                }
            }
        ) {
            val w = size.width; val h = size.height
            // Sin leer del control: luces apagadas (no inventar colores por defecto).
            val off = Light(0, effect = 255, lum = 0)
            strip(STRIP_L.map { it.scale(w, h) }, if (s.appliedLeft != null) s.left else off, time, h)
            strip(STRIP_R.map { it.scale(w, h) }, if (s.appliedRight != null) s.right else off, time, h)
            homeRing(HOME.scale(w, h), HOME_R * h, if (s.appliedHome != null) s.homeToSend() else off)

            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            val sel = Color.White.copy(alpha = 0.9f)
            // Selección: halo blanco que sigue la forma de la tira (no tapa otros botones).
            val halo = Stroke(STRIP_W * h * 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (selected == LightTarget.LEFT || selected == LightTarget.BOTH)
                drawPath(stripPath(STRIP_L.map { it.scale(w, h) }), Color.White, alpha = 0.45f, style = halo)
            if (selected == LightTarget.RIGHT || selected == LightTarget.BOTH)
                drawPath(stripPath(STRIP_R.map { it.scale(w, h) }), Color.White, alpha = 0.45f, style = halo)
            if (selected == LightTarget.HOME)
                drawCircle(sel, h * 0.075f, HOME.scale(w, h), style = Stroke(2.5f, pathEffect = dash))
        }
        if (labels && onTap != null) {
            // Zonas tocables (y enfocables con el control) justo encima de cada luz.
            val stripC = Offset(0.078f, 0.66f)
            LightHit(stripC, 0.10f, 0.52f, tr("Tira izquierda", "Left strip")) { onTap(LightTarget.LEFT) }
            LightHit(mirror(stripC), 0.10f, 0.52f, tr("Tira derecha", "Right strip")) { onTap(LightTarget.RIGHT) }
            LightHit(HOME, 0.08f, 0.22f, tr("Botón Home", "Home button")) { onTap(LightTarget.HOME) }
        }
    }
}

/** Zona invisible sobre una luz, centrada en [c] (relativo al dibujo) y de tamaño [w]×[h] (fracción). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LightHit(
    c: Offset, w: Float, h: Float, desc: String, onClick: () -> Unit
) {
    // BiasAlignment ubica el borde, no el centro: se corrige con el tamaño de la zona.
    fun bias(center: Float, size: Float) = ((center - size / 2) / (1 - size)) * 2 - 1
    Box(
        Modifier.align(androidx.compose.ui.BiasAlignment(bias(c.x, w), bias(c.y, h)))
            .fillMaxWidth(w).fillMaxHeight(h)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable(onClickLabel = desc, onClick = onClick)
    )
}

private fun Offset.scale(w: Float, h: Float) = Offset(x * w, y * h)

private fun phase(l: Light, time: Long): Float {
    val period = (4500f - l.speed / 255f * 3900f).toLong().coerceAtLeast(300)
    return (time % period) / period.toFloat()
}

/** Tira de luz con el efecto del protocolo: 1 fijo, 2 respiración, 4 arcoíris, 255 apagado. */
private fun stripPath(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (p in pts.drop(1)) lineTo(p.x, p.y)
}

private fun DrawScope.strip(pts: List<Offset>, l: Light, time: Long, h: Float) {
    val path = stripPath(pts)
    val width = STRIP_W * h
    // Se tapa la tira encendida de la foto para mostrar el color elegido.
    drawPath(path, pal.ledOff, style = Stroke(width * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val ph = phase(l, time)
    val bright = 0.3f + 0.7f * l.brightness / 255f
    val brush: Brush
    val alpha: Float
    when {
        l.effect == 255 -> return
        l.effect == 4 -> {
            // Arcoíris (confirmado en el control): los colores recorren la tira de arriba hacia abajo.
            val a = pts.first(); val len = pts.last() - a
            val shift = len * (ph * 2f)
            brush = Brush.linearGradient(RAINBOW, a + shift, a + shift + len * 2f, TileMode.Repeated)
            alpha = bright
        }
        l.lum == 0 -> return
        l.effect == 2 -> {
            brush = SolidColor(lightColor(l))
            alpha = 0.08f + 0.92f * (0.5f - 0.5f * cos(2 * PI * ph).toFloat())
        }
        else -> {
            brush = SolidColor(lightColor(l))
            alpha = 1f
        }
    }
    for (i in 3 downTo 1) {
        drawPath(path, brush, alpha = 0.10f * alpha,
            style = Stroke(width + i * h * 0.022f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(path, brush, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Anillo de luz del botón Home. */
private fun DrawScope.homeRing(c: Offset, r: Float, l: Light) {
    val width = r * 0.32f
    drawCircle(pal.ledOff, r, c, style = Stroke(width * 1.6f))
    // Luminosidad 1–2 de 100 es negro en la práctica: se dibuja como apagado.
    if (l.lum <= 2) return
    val color = lightColor(l)
    for (i in 3 downTo 1) drawCircle(color, r, c, alpha = 0.10f, style = Stroke(width + i * r * 0.35f))
    drawCircle(color, r, c, style = Stroke(width))
}

// ---------------------------------------------------------------------------------------------
// Cuerpo del control (medidas tomadas sobre la foto, en fracción del ancho y del alto)
// ---------------------------------------------------------------------------------------------

private fun DrawScope.controllerBody(text: TextMeasurer) {
    val w = size.width; val h = size.height
    fun p(o: Offset) = Offset(o.x * w, o.y * h)
    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    // Cuerpo: mitad izquierda y su espejo.
    val half = listOf(
        // (punto final, control 1, control 2) de cada curva, desde arriba al centro hacia abajo al centro.
        Triple(Offset(0.131f, 0.02f), Offset(0.5f, 0.02f), Offset(0.131f, 0.02f)),
        // Esquina superior: corte diagonal largo (ahí asoman LB/RB).
        Triple(Offset(0.042f, 0.074f), Offset(0.10f, 0.033f), Offset(0.065f, 0.055f)),
        Triple(Offset(0.020f, 0.16f), Offset(0.028f, 0.084f), Offset(0.021f, 0.115f)),
        Triple(Offset(0.004f, 0.72f), Offset(0.017f, 0.35f), Offset(0.004f, 0.55f)),
        Triple(Offset(0.10f, 0.985f), Offset(0.004f, 0.93f), Offset(0.04f, 0.985f)),
    )
    val body = Path().apply {
        moveTo(0.5f * w, 0.02f * h)
        for ((end, c1, c2) in half) cubicTo(c1.x * w, c1.y * h, c2.x * w, c2.y * h, end.x * w, end.y * h)
        lineTo(0.5f * w, 0.985f * h)
        // Lado derecho, recorrido en sentido inverso.
        lineTo((1f - 0.10f) * w, 0.985f * h)
        val rev = half.reversed()
        for (i in 0 until rev.size - 1) {
            val (from, c1, c2) = rev[i]
            val to = rev[i + 1].first
            cubicTo((1f - c2.x) * w, c2.y * h, (1f - c1.x) * w, c1.y * h, (1f - to.x) * w, to.y * h)
        }
        lineTo(0.5f * w, 0.02f * h)
        close()
    }
    // LB y RB: franja negra que asoma sobre el corte diagonal de cada esquina, más gruesa hacia
    // afuera. Se dibujan antes del cuerpo para que solo se vea lo que sobresale.
    for (m in listOf(false, true)) {
        fun x(v: Float) = (if (m) 1f - v else v) * w
        val bumper = Path().apply {
            moveTo(x(0.140f), 0.035f * h)
            quadraticTo(x(0.141f), 0.012f * h, x(0.132f), 0.008f * h)
            lineTo(x(0.048f), 0.050f * h)
            quadraticTo(x(0.030f), 0.058f * h, x(0.029f), 0.085f * h)
            lineTo(x(0.045f), 0.12f * h)
            close()
        }
        drawPath(bumper, Brush.verticalGradient(listOf(pal.bumperTop, pal.bumperBottom), 0f, 0.08f * h))
        drawPath(bumper, pal.edge.copy(alpha = 0.7f), style = Stroke(1.2f))
    }
    drawPath(body, Brush.verticalGradient(listOf(pal.bodyTop, pal.bodyBottom)))
    drawPath(body, pal.edge, style = Stroke(1.5f))

    // Tablet: la carcasa la rodea también arriba y abajo.
    drawRoundRect(pal.frame, p(0.196f, 0.075f), Size(0.608f * w, 0.85f * h), CornerRadius(0.035f * h))
    val sTop = p(0.201f, 0.088f); val sSize = Size(0.598f * w, 0.824f * h)
    drawRoundRect(pal.screen, sTop, sSize, CornerRadius(0.03f * h))
    if (!pal.flat) drawRoundRect(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.07f), Color.Transparent, Color.Transparent),
            p(0.30f, 0.09f), p(0.55f, 0.90f)
        ),
        sTop, sSize, CornerRadius(0.03f * h)
    )

    // Trabas de la tablet, iguales en ambos lados.
    for (pts in listOf(CLAMP, CLAMP.map(::mirror))) {
        val clamp = Path().apply {
            moveTo(pts[0].x * w, pts[0].y * h)
            for (q in pts.drop(1)) lineTo(q.x * w, q.y * h)
            close()
        }
        drawPath(clamp, pal.clampFill)
        drawPath(clamp, pal.clampEdge, style = Stroke(1.5f))
    }

    // Ranuras y botones pequeños (espejados).
    for (m in listOf(false, true)) {
        fun f(o: Offset) = if (m) mirror(o) else o
        drawLine(if (pal.flat) pal.keyHi else pal.key, p(f(SLIT.first)), p(f(SLIT.second)), 0.022f * h, StrokeCap.Round)
        iconKey(p(f(SMALL_KEY)), 0.027f * h, if (m) KeyIcon.MENU else KeyIcon.VIEW, text)
    }
    iconKey(p(M_KEY), 0.029f * h, KeyIcon.M, text)
    drawCircle(pal.key, 0.033f * h, p(HOME))
    if (pal.flat) drawCircle(pal.keyHi, 0.033f * h, p(HOME), style = Stroke(1.5f))
    legionLogo(p(HOME), 0.033f * h)

    // Sticks, cruceta y ABXY.
    stick(p(STICK_L), h)
    stick(p(STICK_R), h)
    dpad(p(DPAD), 0.095f * h, 0.085f * h)
    val style = TextStyle(color = pal.label, fontSize = (0.052f * h / density).sp, fontWeight = FontWeight.Bold)
    val d = 0.077f * h
    val c0 = p(ABXY)
    for ((label, o) in listOf("Y" to Offset(0f, -d), "X" to Offset(-d, 0f), "B" to Offset(d, 0f), "A" to Offset(0f, d))) {
        val c = c0 + o
        val r = 0.042f * h
        if (!pal.flat) drawCircle(Color.Black.copy(alpha = 0.35f), r * 1.08f, c + Offset(0f, r * 0.12f))
        if (pal.flat) { drawCircle(pal.key, r, c); drawCircle(pal.keyHi, r, c, style = Stroke(1.5f)) }
        else drawCircle(Brush.radialGradient(listOf(pal.keyHi, pal.key), c - Offset(r * 0.3f, r * 0.4f), r * 1.2f), r, c)
        val m = text.measure(label, style)
        drawText(m, topLeft = c - Offset(m.size.width / 2f, m.size.height / 2f))
    }
}

private enum class KeyIcon { VIEW, MENU, M }

/**
 * Botón Home tal como es en el control (foto): centro negro rodeado por un anillo claro cortado en
 * tres partes iguales por rayos abajo, arriba a la izquierda y arriba a la derecha.
 */
private fun DrawScope.legionLogo(c: Offset, r: Float) {
    val ic = pal.label.copy(alpha = 0.85f)
    val rr = r * 0.775f
    val gap = 6f
    // Ángulos de los rayos (0° = derecha, sentido horario): abajo, arriba-izquierda, arriba-derecha,
    // simétricos respecto de la vertical.
    val spokes = listOf(90f, 210f, 330f, 450f)
    for (i in 0 until 3) {
        val from = spokes[i] + gap
        drawArc(
            ic, startAngle = from, sweepAngle = spokes[i + 1] - gap - from, useCenter = false,
            topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2),
            style = Stroke(r * 0.23f, cap = StrokeCap.Butt)
        )
    }
}

/** Botón redondo pequeño con su ícono (vista, menú o M). */
private fun DrawScope.iconKey(c: Offset, r: Float, icon: KeyIcon, text: TextMeasurer) {
    if (!pal.flat) drawCircle(Color.Black.copy(alpha = 0.3f), r * 1.1f, c + Offset(0f, r * 0.12f))
    drawCircle(pal.key, r, c)
    drawCircle(pal.keyHi, r, c, style = Stroke(1.5f))
    val ic = pal.label.copy(alpha = 0.75f)
    val sw = r * 0.1f
    when (icon) {
        KeyIcon.VIEW -> {
            val q = r * 0.38f
            drawRect(ic, c + Offset(-q * 0.9f, -q * 0.9f), Size(q * 1.2f, q * 1.2f), style = Stroke(sw))
            drawRect(ic, c + Offset(-q * 0.3f, -q * 0.3f), Size(q * 1.2f, q * 1.2f), style = Stroke(sw))
        }
        KeyIcon.MENU -> for (dy in listOf(-0.3f, 0f, 0.3f)) {
            drawLine(ic, c + Offset(-r * 0.4f, dy * r), c + Offset(r * 0.4f, dy * r), sw, StrokeCap.Round)
        }
        KeyIcon.M -> {
            val m = text.measure("M", TextStyle(color = ic, fontSize = (r * 1.1f / density).sp))
            drawText(m, topLeft = c - Offset(m.size.width / 2f, m.size.height / 2f))
        }
    }
}

/** Stick: hueco oscuro con borde y tapa con anillo estriado (medidas sobre la foto). */
private fun DrawScope.stick(c: Offset, h: Float) {
    val base = 0.088f * h
    val cap = 0.066f * h
    drawCircle(pal.stickRim, base * 1.04f, c)
    drawCircle(pal.stickWell, base, c)
    val cc = c
    if (!pal.flat) drawCircle(Color.Black.copy(alpha = 0.45f), cap * 1.05f, cc + Offset(0f, cap * 0.12f))
    drawCircle(pal.stickCap, cap, cc)
    if (pal.flat) drawCircle(pal.stickRim, cap, cc, style = Stroke(1.5f))
    // Estrías del anillo exterior de la tapa.
    if (!pal.flat) for (i in 0 until 40) {
        val ang = i * (2 * PI / 40)
        val dx = cos(ang).toFloat(); val dy = kotlin.math.sin(ang).toFloat()
        drawLine(pal.stickRidge, cc + Offset(dx * cap * 0.80f, dy * cap * 0.80f), cc + Offset(dx * cap * 0.98f, dy * cap * 0.98f), 1.2f)
    }
    drawCircle(Brush.radialGradient(listOf(pal.stickCapHi, pal.stickCapLo), cc - Offset(cap * 0.25f, cap * 0.25f), cap * 0.8f), cap * 0.74f, cc)
}

private fun DrawScope.dpad(c: Offset, arm: Float, t: Float) {
    val cr = CornerRadius(t * 0.28f)
    if (pal.flat) {
        // Estilo simple: borde fino en vez de sombra.
        val e = 1.5f
        drawRoundRect(pal.keyHi, Offset(c.x - arm - e, c.y - t / 2 - e), Size(arm * 2 + 2 * e, t + 2 * e), cr)
        drawRoundRect(pal.keyHi, Offset(c.x - t / 2 - e, c.y - arm - e), Size(t + 2 * e, arm * 2 + 2 * e), cr)
    } else for (o in listOf(Offset(0f, t * 0.1f))) {
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(c.x - arm, c.y - t / 2) + o, Size(arm * 2, t), cr)
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(c.x - t / 2, c.y - arm) + o, Size(t, arm * 2), cr)
    }
    val fill = if (pal.flat) SolidColor(pal.key) else Brush.radialGradient(listOf(pal.keyHi, pal.key), c - Offset(t * 0.4f, t * 0.6f), arm * 1.4f)
    drawRoundRect(fill, Offset(c.x - arm, c.y - t / 2), Size(arm * 2, t), cr)
    drawRoundRect(fill, Offset(c.x - t / 2, c.y - arm), Size(t, arm * 2), cr)
}

// ---------------------------------------------------------------------------------------------
// Prueba de botones: el mismo dibujo, con lo que se aprieta iluminado.
// ---------------------------------------------------------------------------------------------

/** Botones del G9 que se pueden ver en la prueba. */
enum class PadKey(private val es: String, private val en: String = es) {
    A("A"), B("B"), X("X"), Y("Y"), LB("LB"), RB("RB"), LT("LT"), RT("RT"), L3("L3"), R3("R3"),
    VIEW("Vista", "View"), MENU("Menú", "Menu"), HOME("Home"), M("M"), UP("Arriba", "Up"), DOWN("Abajo", "Down"),
    LEFT("Izquierda", "Left"), RIGHT("Derecha", "Right"), STICK_L("Stick izq.", "Left stick"), STICK_R("Stick der.", "Right stick");

    val label get() = tr(es, en)
}

private val LIT = Color(0xFF3DD6FF)

/** Dibujo del G9 con [pressed] iluminado y la posición de cada stick como un punto que se mueve. */
@Composable
fun ControllerTestView(
    pressed: Set<PadKey>, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, modifier: Modifier,
    lights: AppState? = null
) {
    Box(modifier.aspectRatio(ASPECT)) {
        val text = rememberTextMeasurer()
        Canvas(Modifier.matchParentSize()) { controllerBody(text) }
        // Las luces tal como están en el control, igual que en Inicio y Luces.
        if (lights != null) ControllerLights(lights, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            fun p(o: Offset) = Offset(o.x * w, o.y * h)
            fun lit(c: Offset, r: Float) {
                drawCircle(LIT.copy(alpha = 0.35f), r * 1.45f, c)
                drawCircle(LIT, r * 1.05f, c, style = Stroke(r * 0.2f))
            }
            // Gatillos (LT/RT, analógicos) y bumpers (LB/RB): franja sobre el corte de cada esquina.
            for (right in listOf(false, true)) {
                fun x(v: Float) = (if (right) 1f - v else v) * w
                val trig = if (right) rt else lt
                if (trig > 0.02f) drawLine(
                    LIT.copy(alpha = 0.25f + 0.6f * trig), Offset(x(0.135f), 0.004f * h), Offset(x(0.05f), 0.044f * h),
                    0.03f * h, StrokeCap.Round
                )
                if ((if (right) PadKey.RB else PadKey.LB) in pressed) drawLine(
                    LIT, Offset(x(0.132f), 0.03f * h), Offset(x(0.045f), 0.075f * h), 0.022f * h, StrokeCap.Round
                )
            }
            val d = 0.077f * h
            val c0 = p(ABXY)
            for ((k, o) in listOf(PadKey.Y to Offset(0f, -d), PadKey.X to Offset(-d, 0f), PadKey.B to Offset(d, 0f), PadKey.A to Offset(0f, d))) {
                if (k in pressed) lit(c0 + o, 0.042f * h)
            }
            if (PadKey.VIEW in pressed) lit(p(SMALL_KEY), 0.027f * h)
            if (PadKey.MENU in pressed) lit(p(mirror(SMALL_KEY)), 0.027f * h)
            if (PadKey.M in pressed) lit(p(M_KEY), 0.029f * h)
            if (PadKey.HOME in pressed) lit(p(HOME), 0.033f * h)
            if (PadKey.L3 in pressed) lit(p(STICK_L), 0.066f * h)
            if (PadKey.R3 in pressed) lit(p(STICK_R), 0.066f * h)

            // Cruceta: se ilumina el brazo apretado.
            val c = p(DPAD); val arm = 0.095f * h; val t = 0.085f * h
            val cr = CornerRadius(t * 0.28f)
            if (PadKey.UP in pressed) drawRoundRect(LIT, Offset(c.x - t / 2, c.y - arm), Size(t, arm - t / 2), cr)
            if (PadKey.DOWN in pressed) drawRoundRect(LIT, Offset(c.x - t / 2, c.y + t / 2), Size(t, arm - t / 2), cr)
            if (PadKey.LEFT in pressed) drawRoundRect(LIT, Offset(c.x - arm, c.y - t / 2), Size(arm - t / 2, t), cr)
            if (PadKey.RIGHT in pressed) drawRoundRect(LIT, Offset(c.x + t / 2, c.y - t / 2), Size(arm - t / 2, t), cr)

            // Sticks: punto que sigue la posición, con una línea desde el centro.
            for ((center, v) in listOf(p(STICK_L) to Offset(lx, ly), p(STICK_R) to Offset(rx, ry))) {
                if (hypot(v.x, v.y) < 0.04f) continue
                val dot = center + v * (0.07f * h)
                drawLine(LIT.copy(alpha = 0.7f), center, dot, 0.008f * h, StrokeCap.Round)
                drawCircle(LIT, 0.02f * h, dot)
            }
        }
    }
}

/** Tiras y anillo de Home con los colores y efectos leídos del control (apagados si no se leyeron). */
@Composable
private fun ControllerLights(s: AppState, modifier: Modifier) {
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { time = it - start }
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val off = Light(0, effect = 255, lum = 0)
        strip(STRIP_L.map { it.scale(w, h) }, if (s.appliedLeft != null) s.left else off, time, h)
        strip(STRIP_R.map { it.scale(w, h) }, if (s.appliedRight != null) s.right else off, time, h)
        homeRing(HOME.scale(w, h), HOME_R * h, if (s.appliedHome != null) s.homeToSend() else off)
    }
}

// ---------------------------------------------------------------------------------------------
// Control genérico (Probar control con otro control): simétrico, con las mismas piezas del G9.
// ---------------------------------------------------------------------------------------------

private const val GP_ASPECT = 1.55f
private val GP_DPAD = Offset(0.25f, 0.40f)
private val GP_ABXY = Offset(0.75f, 0.40f)
private val GP_STICK_L = Offset(0.37f, 0.64f)
private val GP_STICK_R = Offset(0.63f, 0.64f)
private val GP_VIEW = Offset(0.42f, 0.30f)
private val GP_HOME = Offset(0.5f, 0.44f)

/**
 * Un control genérico simétrico (cruceta y ABXY arriba en espejo, los dos sticks abajo, como un DualSense), dibujado
 * con las piezas y la paleta del G9 (el estilo elegido en Preferencias del G9): lo apretado brilla en celeste, los
 * gatillos se llenan y los sticks muestran su posición.
 */
@Composable
fun GenericControllerView(
    pressed: Set<PadKey>, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, modifier: Modifier
) {
    Box(modifier.aspectRatio(GP_ASPECT)) {
        val text = rememberTextMeasurer()
        Canvas(Modifier.matchParentSize()) { genericBody(text, lt, rt) }
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            fun p(o: Offset) = Offset(o.x * w, o.y * h)
            fun lit(c: Offset, r: Float) {
                drawCircle(LIT.copy(alpha = 0.35f), r * 1.45f, c)
                drawCircle(LIT, r * 1.05f, c, style = Stroke(r * 0.2f))
            }
            for (right in listOf(false, true)) {
                if ((if (right) PadKey.RB else PadKey.LB) in pressed) drawPath(gpBumper(w, h, right), LIT)
            }
            val d = 0.10f * h
            for ((k, o) in listOf(PadKey.Y to Offset(0f, -d), PadKey.X to Offset(-d, 0f), PadKey.B to Offset(d, 0f), PadKey.A to Offset(0f, d))) {
                if (k in pressed) lit(p(GP_ABXY) + o, 0.055f * h)
            }
            if (PadKey.VIEW in pressed) lit(p(GP_VIEW), 0.034f * h)
            if (PadKey.MENU in pressed) lit(p(mirror(GP_VIEW)), 0.034f * h)
            if (PadKey.HOME in pressed) lit(p(GP_HOME), 0.045f * h)
            if (PadKey.L3 in pressed) lit(p(GP_STICK_L), 0.085f * h)
            if (PadKey.R3 in pressed) lit(p(GP_STICK_R), 0.085f * h)
            val c = p(GP_DPAD); val arm = 0.12f * h; val t = 0.105f * h
            val cr = CornerRadius(t * 0.28f)
            if (PadKey.UP in pressed) drawRoundRect(LIT, Offset(c.x - t / 2, c.y - arm), Size(t, arm - t / 2), cr)
            if (PadKey.DOWN in pressed) drawRoundRect(LIT, Offset(c.x - t / 2, c.y + t / 2), Size(t, arm - t / 2), cr)
            if (PadKey.LEFT in pressed) drawRoundRect(LIT, Offset(c.x - arm, c.y - t / 2), Size(arm - t / 2, t), cr)
            if (PadKey.RIGHT in pressed) drawRoundRect(LIT, Offset(c.x + t / 2, c.y - t / 2), Size(arm - t / 2, t), cr)
            for ((center, v) in listOf(p(GP_STICK_L) to Offset(lx, ly), p(GP_STICK_R) to Offset(rx, ry))) {
                if (hypot(v.x, v.y) < 0.04f) continue
                val dot = center + v * (0.09f * h)
                drawLine(LIT.copy(alpha = 0.7f), center, dot, 0.01f * h, StrokeCap.Round)
                drawCircle(LIT, 0.025f * h, dot)
            }
        }
    }
}

/** LB o RB: una franja curva sobre el hombro. */
private fun gpBumper(w: Float, h: Float, right: Boolean): Path {
    fun x(v: Float) = (if (right) 1f - v else v) * w
    return Path().apply {
        moveTo(x(0.09f), 0.20f * h)
        cubicTo(x(0.12f), 0.10f * h, x(0.20f), 0.075f * h, x(0.33f), 0.075f * h)
        lineTo(x(0.33f), 0.13f * h)
        cubicTo(x(0.22f), 0.13f * h, x(0.15f), 0.15f * h, x(0.12f), 0.22f * h)
        close()
    }
}

private fun DrawScope.genericBody(text: TextMeasurer, lt: Float, rt: Float) {
    val w = size.width; val h = size.height
    fun p(o: Offset) = Offset(o.x * w, o.y * h)

    // Gatillos (detrás de los hombros): se llenan de celeste según cuánto se aprietan.
    for (right in listOf(false, true)) {
        fun x(v: Float) = (if (right) 1f - v else v) * w
        val trig = Path().apply {
            moveTo(x(0.15f), 0.10f * h)
            cubicTo(x(0.16f), 0.02f * h, x(0.22f), 0.0f * h, x(0.27f), 0.0f * h)
            lineTo(x(0.31f), 0.0f * h)
            lineTo(x(0.31f), 0.09f * h)
            close()
        }
        drawPath(trig, Brush.verticalGradient(listOf(pal.bumperTop, pal.bumperBottom), 0f, 0.1f * h))
        val v = if (right) rt else lt
        if (v > 0.02f) drawPath(trig, LIT.copy(alpha = 0.25f + 0.65f * v))
        drawPath(gpBumper(w, h, right), Brush.verticalGradient(listOf(pal.bumperTop, pal.bumperBottom), 0.07f * h, 0.2f * h))
    }

    // Cuerpo: la mitad izquierda y su espejo (asas abajo, hombros arriba).
    val half = listOf(
        Triple(Offset(0.26f, 0.11f), Offset(0.42f, 0.11f), Offset(0.32f, 0.11f)),
        Triple(Offset(0.07f, 0.24f), Offset(0.17f, 0.11f), Offset(0.10f, 0.15f)),
        Triple(Offset(0.02f, 0.70f), Offset(0.03f, 0.36f), Offset(0.0f, 0.55f)),
        Triple(Offset(0.15f, 0.99f), Offset(0.04f, 0.90f), Offset(0.08f, 0.99f)),
        Triple(Offset(0.30f, 0.86f), Offset(0.22f, 0.99f), Offset(0.25f, 0.90f)),
        Triple(Offset(0.5f, 0.80f), Offset(0.36f, 0.81f), Offset(0.44f, 0.80f)),
    )
    val body = Path().apply {
        moveTo(0.5f * w, 0.11f * h)
        for ((end, c1, c2) in half) cubicTo(c1.x * w, c1.y * h, c2.x * w, c2.y * h, end.x * w, end.y * h)
        val rev = half.reversed()
        for (i in 0 until rev.size) {
            val (_, c1, c2) = rev[i]
            val to = if (i + 1 < rev.size) rev[i + 1].first else Offset(0.5f, 0.11f)
            cubicTo((1f - c2.x) * w, c2.y * h, (1f - c1.x) * w, c1.y * h, (1f - to.x) * w, to.y * h)
        }
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(pal.bodyTop, pal.bodyBottom)))
    drawPath(body, pal.edge, style = Stroke(1.5f))

    // Vista, Menú y Home al centro.
    iconKey(p(GP_VIEW), 0.034f * h, KeyIcon.VIEW, text)
    iconKey(p(mirror(GP_VIEW)), 0.034f * h, KeyIcon.MENU, text)
    drawCircle(pal.key, 0.045f * h, p(GP_HOME))
    drawCircle(pal.keyHi, 0.045f * h, p(GP_HOME), style = Stroke(1.5f))
    drawCircle(pal.label.copy(alpha = 0.7f), 0.026f * h, p(GP_HOME), style = Stroke(0.008f * h))

    // Sticks (las medidas del G9 escaladas), cruceta y ABXY.
    stick(p(GP_STICK_L), h * 1.3f)
    stick(p(GP_STICK_R), h * 1.3f)
    dpad(p(GP_DPAD), 0.12f * h, 0.105f * h)
    val style = TextStyle(color = pal.label, fontSize = (0.065f * h / density).sp, fontWeight = FontWeight.Bold)
    val d = 0.10f * h
    for ((label, o) in listOf("Y" to Offset(0f, -d), "X" to Offset(-d, 0f), "B" to Offset(d, 0f), "A" to Offset(0f, d))) {
        val c = p(GP_ABXY) + o
        val r = 0.055f * h
        if (!pal.flat) drawCircle(Color.Black.copy(alpha = 0.35f), r * 1.08f, c + Offset(0f, r * 0.12f))
        if (pal.flat) { drawCircle(pal.key, r, c); drawCircle(pal.keyHi, r, c, style = Stroke(1.5f)) }
        else drawCircle(Brush.radialGradient(listOf(pal.keyHi, pal.key), c - Offset(r * 0.3f, r * 0.4f), r * 1.2f), r, c)
        val m = text.measure(label, style)
        drawText(m, topLeft = c - Offset(m.size.width / 2f, m.size.height / 2f))
    }
}