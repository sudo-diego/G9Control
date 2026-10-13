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
 * Lo que el panel dibuja aparte de su tarjeta, cada uno en su ventana: brillo y volumen del sistema, la imagen congelada
 * del cambio de rendimiento (con su anillo), la alerta roja de logros, el aviso de captura y los otros avisos, y sus sonidos.
 */

/**
 * Brillo y volumen desde el panel de juego. El brillo se escribe en los ajustes del sistema: sirve el permiso de
 * pantalla de adb (WRITE_SECURE_SETTINGS) o "Modificar ajustes del sistema". Los pasos del brillo no son lineales
 * (el ojo nota mucho más los cambios abajo): 11 niveles, de 0 a 100 %.
 */
internal object SystemLevels {
    private val LEVELS = intArrayOf(2, 5, 10, 18, 29, 44, 64, 90, 125, 180, 255)

    fun canBrightness(ctx: Context) = Aspect.canWrite(ctx) || Settings.System.canWrite(ctx)

    fun autoBrightness(ctx: Context) = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    fun setAutoBrightness(ctx: Context, on: Boolean) = runCatching {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }

    /**
     * Game Assistant apaga el brillo automático al entrar a un juego si este ajuste global es 1. Con el permiso de
     * pantalla de adb se cambia (sin él, no pasa nada: el brillo automático igual cambia, solo que en el próximo juego
     * Game Assistant lo vuelve a apagar).
     */
    fun setAutoInGames(ctx: Context, on: Boolean) = runCatching {
        Settings.Global.putInt(ctx.contentResolver, "key_game_assistant_disable_auto_brightness", if (on) 0 else 1)
    }

    /** Nivel más cercano al brillo actual (0..10). */
    private fun level(ctx: Context): Int {
        val v = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return LEVELS.indices.minBy { kotlin.math.abs(LEVELS[it] - v) }
    }

    fun brightness(ctx: Context) = level(ctx) * 10

    /** Con el dedo en la barra: el nivel más cercano a esa posición (0..1). */
    fun setBrightness(ctx: Context, f: Float) = runCatching {
        setAutoBrightness(ctx, false)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, LEVELS[(f * 10).roundToInt().coerceIn(0, 10)])
    }

    /** False si Android no dejó subir (el aviso de "volumen alto" con audífonos: hay que confirmarlo). */
    fun setVolume(ctx: Context, f: Float): Boolean {
        val a = audio(ctx)
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val want = (f * max).roundToInt().coerceIn(0, max)
        // Poner un nivel no quita el silencio (al dejarlo en 0 Android lo silencia): se quita antes.
        if (want > 0 && a.isStreamMute(AudioManager.STREAM_MUSIC)) a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0)
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) >= want
    }

    /** Un paso más o menos; deja el brillo en manual (si no, el automático lo cambia enseguida). */
    fun stepBrightness(ctx: Context, dir: Int) = runCatching {
        setAutoBrightness(ctx, false)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, LEVELS[(level(ctx) + dir).coerceIn(0, 10)])
    }

    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    /** Volumen de la música y los juegos, en %. */
    fun volume(ctx: Context): Int {
        val a = audio(ctx)
        if (a.isStreamMute(AudioManager.STREAM_MUSIC)) return 0
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    /**
     * Un paso más o menos. False si al subir no cambió sin estar al máximo: lo frenó el aviso de volumen alto de Android.
     * Con setStreamVolume y no adjustStreamVolume(ADJUST_LOWER): en ZUI, bajar así hasta 0 mostraba el volumen del
     * sistema y hacía vibrar la tablet (como al pasar a modo vibración). Silenciado, subir quita el silencio.
     */
    fun stepVolume(ctx: Context, dir: Int): Boolean {
        val a = audio(ctx)
        val s = AudioManager.STREAM_MUSIC
        if (a.isStreamMute(s)) {
            if (dir > 0) {
                a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                if (a.getStreamVolume(s) == 0) a.setStreamVolume(s, 1, 0)
            }
            return true
        }
        val max = a.getStreamMaxVolume(s)
        val before = a.getStreamVolume(s)
        val want = (before + dir).coerceIn(0, max)
        if (want == before) return true
        a.setStreamVolume(s, want, 0)
        return dir < 0 || a.getStreamVolume(s) > before
    }

    /** Subir con la interfaz de Android: así muestra su aviso de volumen alto para confirmarlo. */
    fun raiseWithUi(ctx: Context) =
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)

    fun toggleMute(ctx: Context) =
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
}

/**
 * Mientras G9 Control cambia el modo de rendimiento: la pantalla congelada (una imagen del juego tomada justo
 * antes, solo en memoria: no se guarda) a pantalla completa. Primero se ve igual que el juego y enseguida se
 * oscurece, con una tarjeta que dice qué pasa; así no se ve el panel de Game Assistant. No recibe toques: los
 * gestos simulados la atraviesan y llegan a Game Assistant (las ventanas de accesibilidad quedan por encima de
 * las suyas). Sin imagen, un fondo negro.
 */
internal class FreezeCover(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: android.view.View? = null
    private var dim: android.view.View? = null
    private var card: android.view.View? = null
    private var ring: PerfRing? = null
    private var title: android.widget.TextView? = null
    private var subtitle: android.widget.TextView? = null
    private var shot: Bitmap? = null
    private var failTitle = ""
    private var failHint = ""

    /**
     * [label]: el título ("Rendimiento: Bestia", "Grabando"…); [fail]/[hint]: lo que dice si sale mal. Sirve para
     * todo lo que se hace como el dedo sobre otra app (rendimiento, grabación, Shadow Rec): así no se ve.
     */
    fun show(image: Bitmap?, label: String,
             fail: String = tr("No se pudo cambiar el rendimiento", "Couldn't change performance"),
             hint: String = tr("Prueba otra vez o usa Game Assistant", "Try again or use Game Assistant")) {
        failTitle = fail; failHint = hint
        val dp = svc.resources.displayMetrics.density
        val px = { v: Int -> (v * dp).toInt() }
        val icon = PerfRing(svc)
        val t = android.widget.TextView(svc).apply {
            // El título no cambia en todo el proceso (si cambia al final no se alcanza a leer).
            text = label
            setTextColor(if (light) 0xFF14203A.toInt() else android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val s = android.widget.TextView(svc).apply {
            text = tr("Un momento…", "One moment…")
            setTextColor(if (light) 0xFF56607A.toInt() else 0xFF9AA6BF.toInt())
            textSize = 13f
        }
        val texts = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
            addView(t)
            addView(s)
        }
        val c = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(14), px(22), px(14))
            // Con el tema del panel (claro u oscuro).
            background = android.graphics.drawable.GradientDrawable().apply { setColor(if (light) 0xF2F3F6FB.toInt() else 0xEE0E1424.toInt()); cornerRadius = 16 * dp }
            addView(icon, android.widget.LinearLayout.LayoutParams(px(40), px(40)))
            addView(texts)
            alpha = 0f
        }
        val d = android.view.View(svc).apply { setBackgroundColor(android.graphics.Color.BLACK); alpha = 0f }
        val match = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        val root = android.widget.FrameLayout(svc).apply {
            if (image != null) addView(android.widget.ImageView(svc).apply {
                setImageBitmap(image)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            })
            else setBackgroundColor(android.graphics.Color.BLACK)
            addView(d, android.widget.FrameLayout.LayoutParams(match, match))
            addView(c, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Toma el foco (sin FLAG_NOT_FOCUSABLE): así los sticks y gatillos del control le llegan a ella y no al
            // juego mientras la imagen está congelada (el servicio solo puede filtrar botones, no movimientos). El
            // juego recupera el foco al quitarla. Sigue sin recibir toques: los gestos llegan a Game Assistant.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, _, _ -> true }
        root.setOnGenericMotionListener { _, _ -> true }
        if (runCatching { wm.addView(root, lp) }.isFailure) { image?.recycle(); return }
        root.requestFocus()
        view = root; dim = d; card = c; ring = icon; title = t; subtitle = s; shot = image
        // Se oscurece y aparece la tarjeta: se nota que es una pausa y no que el juego se colgó.
        d.animate().alpha(0.55f).setDuration(250).start()
        c.animate().alpha(1f).setDuration(250).start()
    }

    /**
     * Resultado: el modo que quedó lo confirma el registro de Game Assistant (no se supone). El título no cambia:
     * el anillo se cierra y se enciende (ver [PerfRing]) y la línea de abajo dice «Listo». Se ve 1,2 s y la imagen se
     * va con un fundido.
     */
    fun done(ok: Boolean, quick: Boolean = false) {
        if (view == null) return
        // Que el cambio de texto no achique la tarjeta.
        (subtitle?.parent as? android.view.View)?.let { it.minimumWidth = it.width }
        ring?.finish(ok)
        if (ok) {
            subtitle?.text = tr("Listo", "Done")
        } else {
            title?.text = failTitle
            subtitle?.text = failHint
        }
        main.postDelayed({
            val v = view ?: return@postDelayed
            v.animate().alpha(0f).setDuration(250).withEndAction { hide() }.start()
        }, if (!ok) 2200 else if (quick) 500 else 1200)
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        // Sin recycle(): removeView es asíncrono y la ventana todavía se dibuja una vez (se caía la app). Al
        // soltar la referencia, la memoria se libera sola.
        shot = null
    }
}

/**
 * Alerta de logros, en rojo y al centro, delante del juego (como la guía de cambio de modo): título, qué pasa y dos
 * acciones con el control o el dedo: A arregla (cierra el juego y abre lo que hace falta), B sigue sin logros. Toma el
 * foco (sticks y gatillos no llegan al juego) y el servicio le pasa los botones. Sigue el tema claro u oscuro.
 */
internal class RaAlert(private val svc: AccessibilityService, private val title: String, private val text: String,
                      private val actionLabel: String, private val action: () -> Unit) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: android.view.View? = null
    var onClose: (() -> Unit)? = null

    fun show() {
        val dp = svc.resources.displayMetrics.density
        val px = { v: Int -> (v * dp).toInt() }
        val red = 0xFFE5484D.toInt()
        val fg = if (light) 0xFF14203A.toInt() else android.graphics.Color.WHITE
        val muted = if (light) 0xFF56607A.toInt() else 0xFFB8C0D0.toInt()
        fun button(key: String, label: String, filled: Boolean, onTap: () -> Unit) = android.widget.TextView(svc).apply {
            text = "$key   $label"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (filled) android.graphics.Color.WHITE else fg)
            gravity = Gravity.CENTER
            setPadding(px(22), px(14), px(22), px(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26 * dp
                if (filled) setColor(red) else { setColor(android.graphics.Color.TRANSPARENT); setStroke(px(2), muted) }
            }
            setOnClickListener { onTap() }
        }
        val icon = android.widget.ImageView(svc).apply {
            runCatching { setImageDrawable(svc.packageManager.getApplicationIcon(RaProxy.PACKAGE)) }
        }
        val card = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(32), px(28), px(32), px(26))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (light) 0xFFFFF1F1.toInt() else 0xFF2A1218.toInt()); cornerRadius = 28 * dp; setStroke(px(3), red)
            }
            addView(icon, android.widget.LinearLayout.LayoutParams(px(56), px(56)))
            addView(android.widget.TextView(svc).apply {
                text = title; textSize = 22f; setTextColor(red); gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(14), 0, px(8))
            })
            addView(android.widget.TextView(svc).apply { text = this@RaAlert.text; textSize = 15f; setTextColor(fg); gravity = Gravity.CENTER })
            addView(android.widget.LinearLayout(svc).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, px(22), 0, 0)
                addView(button("A", actionLabel, true) { pick(true) })
                addView(android.view.View(svc), android.widget.LinearLayout.LayoutParams(px(14), 1))
                addView(button("B", tr("Seguir sin logros", "Continue without"), false) { pick(false) })
            })
        }
        val root = android.widget.FrameLayout(svc).apply {
            setBackgroundColor(0x99000000.toInt())
            addView(card, android.widget.FrameLayout.LayoutParams(px(560), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            isFocusable = true; isFocusableInTouchMode = true
            setOnGenericMotionListener { _, _ -> true }   // sticks y gatillos: no al juego
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        if (runCatching { wm.addView(root, lp) }.isFailure) { onClose?.invoke(); return }
        root.requestFocus()
        root.animate().alpha(1f).setDuration(220).start()
        view = root
    }

    /** Botones del control (los pasa el servicio): A arregla, B sigue. Los demás no hacen nada (no llegan al juego). */
    fun onKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> pick(true)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> pick(false)
        }
    }

    private fun pick(fix: Boolean) {
        hide()
        if (fix) { (svc as? AutoModeService)?.raAlertsReset(); action() }
    }

    fun hide() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(180).withEndAction { runCatching { wm.removeView(v) } }.start()
        onClose?.invoke()
    }
}

/**
 * El indicador de la tarjeta de rendimiento, en el estilo del panel (acento celeste, trazos redondeados) en vez de la
 * ruedita de Android y un ✓: un arco que gira mientras cambia; al terminar se cierra en un anillo, se enciende un punto
 * al centro y un halo se expande y se desvanece (como una luz que se prende). Si falla, el anillo queda gris.
 */
internal class PerfRing(ctx: Context) : android.view.View(ctx) {
    private val accent = ThemeState.panelAccent.color(ThemeState.panelLight).let { android.graphics.Color.argb(it.alpha, it.red, it.green, it.blue) }
    private val stroke = 3f * ctx.resources.displayMetrics.density
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; strokeCap = android.graphics.Paint.Cap.ROUND; color = accent
    }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val track = android.graphics.Paint(paint).apply { alpha = 50 }
    private var angle = 0f
    private var sweep = 80f
    /** 0..1 al terminar: el punto del centro crece y el halo se va. */
    private var glow = 0f
    private var failed = false
    private val spin = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); spin.start() }
    override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }

    fun finish(ok: Boolean) {
        spin.cancel()
        failed = !ok
        if (!ok) { paint.color = 0xFF9AA6BF.toInt(); sweep = 360f; invalidate(); return }
        val from = sweep
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                sweep = from + (360f - from) * (f / 0.45f).coerceAtMost(1f)
                glow = ((f - 0.35f) / 0.65f).coerceIn(0f, 1f)
                invalidate()
            }
        }.start()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        // Margen alrededor para el halo (la vista no dibuja fuera de sus bordes).
        val r = minOf(width, height) / 2f - stroke * 3.2f
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, r, track)
        c.drawArc(cx - r, cy - r, cx + r, cy + r, angle - 90f, sweep, false, paint)
        if (glow > 0f && !failed) {
            c.drawCircle(cx, cy, r * 0.45f * glow, fill)
            // Halo: sale del anillo hacia afuera y se apaga.
            val halo = android.graphics.Paint(paint).apply { alpha = (140 * (1f - glow)).toInt(); strokeWidth = stroke * (1f - glow * 0.6f) }
            c.drawCircle(cx, cy, r + stroke * 2.2f * glow, halo)
        }
    }
}

/**
 * Aviso de captura, como el de la Switch: una tarjeta con la miniatura y «Captura guardada» que entra desde
 * el borde (por defecto arriba a la izquierda; se elige en Ajustes), se queda 2 s y vuelve a salir. No recibe
 * toques ni foco y no sale en la captura (que toma solo la ventana del juego). Con Views simples: es liviano.
 */
internal class ShotFlash(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val dp = svc.resources.displayMetrics.density
    private var frame: android.widget.FrameLayout? = null
    private val slideOut = Runnable { leave() }

    /** Miniatura de la captura (56 dp de alto). Se puede llamar desde cualquier hilo. */
    fun thumbnail(bmp: Bitmap): Bitmap {
        val h = (56 * dp).toInt()
        return Bitmap.createScaledBitmap(bmp, (bmp.width * h / bmp.height).coerceAtLeast(1), h, true)
    }

    fun show(thumb: Bitmap) = show(thumb, tr("Captura guardada", "Screenshot saved"), null, 2200)

    /** La misma tarjeta con otra imagen y texto (batería baja): [ms] lo que se queda antes de salir. */
    fun show(thumb: Bitmap, title: String, line: String?, ms: Long, at: NoticePos? = null) {
        hide()
        val px = { v: Int -> (v * dp).toInt() }
        val image = android.widget.ImageView(svc).apply {
            setImageBitmap(thumb)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, 8 * dp)
            }
            clipToOutline = true
        }
        val text = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14), 0, px(6), 0)
            addView(android.widget.TextView(svc).apply { text = title; setTextColor(if (light) 0xFF14203A.toInt() else android.graphics.Color.WHITE); textSize = 15f })
            line?.let { l -> addView(android.widget.TextView(svc).apply { text = l; setTextColor(if (light) 0xFF56607A.toInt() else 0xFF9AA6BF.toInt()); textSize = 13f }) }
        }
        val card = android.widget.LinearLayout(svc).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(16), px(8))
            // Con el tema del panel (claro u oscuro), como todo lo que se dibuja sobre el juego.
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (light) 0xF2F3F6FB.toInt() else 0xEE0E1424.toInt()); cornerRadius = 16 * dp
            }
            addView(image, android.widget.LinearLayout.LayoutParams(thumb.width, thumb.height))
            addView(text)
            visibility = android.view.View.INVISIBLE
        }
        // La ventana toca el borde de la pantalla (el margen va adentro): la tarjeta entra desde ese borde y lo
        // que queda fuera se recorta. En una esquina o a un lado entra de costado; arriba o abajo al centro, en
        // vertical; en el centro aparece sin moverse.
        val pos = at ?: AutoRules.shotPos(svc)
        val f = android.widget.FrameLayout(svc).apply { setPadding(px(16), px(16), px(16), px(16)); addView(card) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (pos.h) { -1 -> Gravity.START; 1 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL } or
                when (pos.v) { -1 -> Gravity.TOP; 1 -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
        }
        if (runCatching { wm.addView(f, lp) }.isFailure) return
        frame = f
        shown = Shown(pos, AutoRules.shotAnim(svc))
        // Al primer dibujo (ya medida) se pone en su estado oculto y entra.
        card.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                val out = outState(card, f)
                card.visibility = android.view.View.VISIBLE
                if (out != null) {
                    card.translationX = out.x; card.translationY = out.y
                    card.alpha = out.alpha; card.scaleX = out.scale; card.scaleY = out.scale
                    card.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                return true
            }
        })
        main.postDelayed(slideOut, ms)
    }

    /** Ícono de batería baja (48 dp): contorno claro, relleno rojo con el nivel y la punta a la derecha. */
    /** Batería dibujada: roja (baja) o verde con rayo ([charging]). */
    fun batteryIcon(level: Int, charging: Boolean = false): Bitmap {
        val s = (48 * dp).toInt()
        return Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { b ->
            val c = android.graphics.Canvas(b)
            val stroke = 2.5f * dp
            val body = android.graphics.RectF(4 * dp, 14 * dp, 40 * dp, 34 * dp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = stroke; p.color = if (light) 0xFF14203A.toInt() else 0xFFE6EAF2.toInt()
            c.drawRoundRect(body, 4 * dp, 4 * dp, p)
            p.style = android.graphics.Paint.Style.FILL
            c.drawRoundRect(android.graphics.RectF(41 * dp, 20 * dp, 44 * dp, 28 * dp), 1.5f * dp, 1.5f * dp, p)
            // Cargando, con el color de acento del panel; baja, en rojo.
            p.color = if (charging) ThemeState.panelAccent.color(light).let { android.graphics.Color.argb(it.alpha, it.red, it.green, it.blue) } else 0xFFFF5A5F.toInt()
            val inner = body.width() - 2 * (stroke + 1.5f * dp)
            c.drawRoundRect(android.graphics.RectF(body.left + stroke + 1.5f * dp, body.top + stroke + 1.5f * dp,
                body.left + stroke + 1.5f * dp + inner * (level.coerceIn(3, 100) / 100f), body.bottom - stroke - 1.5f * dp), 2 * dp, 2 * dp, p)
            if (charging) {
                // Rayo al centro, del color del contorno.
                p.color = if (light) 0xFF14203A.toInt() else 0xFFE6EAF2.toInt()
                val bolt = android.graphics.Path().apply {
                    moveTo(24 * dp, 16 * dp); lineTo(17 * dp, 25 * dp); lineTo(22 * dp, 25 * dp)
                    lineTo(20 * dp, 32 * dp); lineTo(27 * dp, 23 * dp); lineTo(22 * dp, 23 * dp); close()
                }
                c.drawPath(bolt, p)
            }
        }
    }

    /** Posición y animación con que se mostró (se leen al mostrar: si cambian en Ajustes, sale igual que entró). */
    private class Shown(val pos: NoticePos, val anim: NoticeAnim)
    private var shown: Shown? = null

    /** Estado de la tarjeta antes de entrar y al salir. */
    private class Out(val x: Float = 0f, val y: Float = 0f, val alpha: Float = 1f, val scale: Float = 1f)

    /** Miniatura de muestra para la vista previa: un degradado del tamaño de una captura. */
    /** Ícono de una app (56 dp) para los avisos que no son capturas: se reconoce de qué se trata. */
    fun appIcon(pkg: String?): Bitmap = pkg?.let { p ->
        runCatching {
            val d = svc.packageManager.getApplicationIcon(p)
            val s = (56 * dp).toInt()
            Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, s, s); d.draw(android.graphics.Canvas(b)) }
        }.getOrNull()
    } ?: sample()

    fun sample(): Bitmap {
        val h = (56 * dp).toInt()
        val w = h * 16 / 10
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).drawPaint(android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                    0xFF1F2A42.toInt(), ThemeState.panelAccent.color(false).let { android.graphics.Color.argb(it.alpha, it.red, it.green, it.blue) }, android.graphics.Shader.TileMode.CLAMP)
            })
        }
    }

    /**
     * Deslizar: fuera del borde más cercano (en el centro, sin borde, aparece como Suave). Suave: transparente
     * y un poco más chica, en su lugar. Sin animación: null (aparece y desaparece de golpe).
     */
    private fun outState(card: android.view.View, f: android.view.View): Out? {
        val s = shown ?: return null
        val soft = Out(alpha = 0f, scale = 0.92f)
        return when (s.anim) {
            NoticeAnim.NONE -> null
            NoticeAnim.SMOOTH -> soft
            NoticeAnim.SLIDE -> when {
                s.pos.h == -1 -> Out(x = -(card.width + f.paddingLeft).toFloat())
                s.pos.h == 1 -> Out(x = (card.width + f.paddingRight).toFloat())
                s.pos.v == -1 -> Out(y = -(card.height + f.paddingTop).toFloat())
                s.pos.v == 1 -> Out(y = (card.height + f.paddingBottom).toFloat())
                else -> soft
            }
        }
    }

    private fun leave() {
        val f = frame ?: return
        val card = f.getChildAt(0)
        val out = outState(card, f) ?: return hide()
        card.animate().translationX(out.x).translationY(out.y).alpha(out.alpha).scaleX(out.scale).scaleY(out.scale)
            .setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (frame === f) hide() }.start()
    }

    fun hide() {
        main.removeCallbacks(slideOut)
        frame?.let { it.getChildAt(0).animate().cancel(); runCatching { wm.removeView(it) } }
        frame = null
        shown = null
    }
}

/**
 * Sonido de la captura: un obturador (dos chasquidos) o un jingle corto de dos notas, archivos en `res/raw`. Van por
 * el volumen multimedia, el mismo del juego (el obturador del sistema, `MediaActionSound`, usa el volumen del sistema
 * y en esta tablet no se oye). Cada captura usa un reproductor nuevo: uno guardado (antes un `AudioTrack` fijo) queda
 * mudo sin avisar si Android reinicia el audio o cambia la salida (audífonos, Bluetooth, monitor).
 */
internal class ShotSounds(private val ctx: android.content.Context) {
    private var player: android.media.MediaPlayer? = null
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_GAME)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /**
     * Foco de audio pasajero que deja bajar a los demás: Android baja el volumen del juego mientras suena (si no,
     * los dos sonidos se mezclaban y no se distinguía) y lo devuelve al soltarlo, como con una notificación.
     */
    private val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setWillPauseWhenDucked(false).build()
    private val unfocus = Runnable { audio.abandonAudioFocusRequest(focus) }

    fun play(s: ShotSound) {
        if (s == ShotSound.NONE) return
        play(if (s == ShotSound.SHUTTER) R.raw.shot_shutter else R.raw.shot_jingle)
    }

    /** Cualquier sonido de res/raw por el volumen multimedia, bajando un momento el del juego. */
    fun play(res: Int) {
        runCatching {
            player?.release()
            val p = android.media.MediaPlayer.create(ctx, res, attrs, audio.generateAudioSessionId()) ?: error("MediaPlayer.create devolvió null")
            player = p
            main.removeCallbacks(unfocus)
            audio.requestAudioFocus(focus)
            // Al terminar se suelta (un poco después, para que el juego no vuelva de golpe).
            p.setOnCompletionListener { it.release(); if (player === it) player = null; main.postDelayed(unfocus, 250) }
            p.start()
        }.onFailure { Log.w(AutoModeService.TAG, "no se pudo reproducir el sonido de captura", it) }
    }

    fun release() {
        main.removeCallbacks(unfocus)
        audio.abandonAudioFocusRequest(focus)
        player?.release()
        player = null
    }
}
