package io.github.sudodiego.g9control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sign

/**
 * Mouse virtual para las apps que no usan el control: un puntero que se mueve con el stick izquierdo (o la cruceta)
 * y toca con A, con toques de verdad (gestos del servicio de accesibilidad). Mantener A quieto es un toque largo y
 * moverse con A apretado arrastra; el stick derecho desplaza; B es Atrás. Mientras está encendido los sticks no llegan a las apps.
 */
class VirtualMouse(private val svc: AccessibilityService, private val onChange: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dp = svc.resources.displayMetrics.density

    var isOn = false
        private set
    private var view: View? = null
    private var x = 0f
    private var y = 0f

    // Ejes de ahora: stick izquierdo (o cruceta) mueve, derecho desplaza.
    private var lx = 0f; private var ly = 0f
    private var rx = 0f; private var ry = 0f
    private var lastTick = 0L
    private var lastScroll = 0L

    fun toggle() = if (isOn) stop() else start()

    fun start() {
        if (isOn) return
        val b = wm.currentWindowMetrics.bounds
        x = b.width() / 2f; y = b.height() / 2f
        val v = Pointer(svc)
        val size = (POINTER_DP * dp).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) return
        view = v
        isOn = true
        place()
        lastTick = SystemClock.uptimeMillis()
        main.post(tick)
        Log.d(AutoModeService.TAG, "mouse virtual: encendido")
        onChange(true)
    }

    fun stop() {
        if (!isOn) return
        isOn = false
        main.removeCallbacks(tick)
        endDrag()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        lx = 0f; ly = 0f; rx = 0f; ry = 0f
        Log.d(AutoModeService.TAG, "mouse virtual: apagado")
        onChange(false)
    }

    /** Sticks y cruceta (como ejes, del control que sea). */
    fun onMotion(e: MotionEvent) {
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        // La cruceta, si está apretada, mueve más lento y preciso que el stick a fondo.
        lx = if (hx != 0f) hx * 0.35f else e.getAxisValue(MotionEvent.AXIS_X)
        ly = if (hy != 0f) hy * 0.35f else e.getAxisValue(MotionEvent.AXIS_Y)
        rx = e.getAxisValue(MotionEvent.AXIS_Z)
        ry = e.getAxisValue(MotionEvent.AXIS_RZ)
    }

    /** A toca o arrastra, B es Atrás. Devuelve si la tecla es del mouse (no llega a la app). */
    fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (e.repeatCount == 0) when (e.action) {
                    KeyEvent.ACTION_DOWN -> pressA()
                    KeyEvent.ACTION_UP -> releaseA()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return true
            }
            // La cruceta como teclas (modo Android del G9 y otros controles): mueve el puntero.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val v = if (e.action == KeyEvent.ACTION_DOWN) 0.35f else 0f
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> lx = -v
                    KeyEvent.KEYCODE_DPAD_RIGHT -> lx = v
                    KeyEvent.KEYCODE_DPAD_UP -> ly = -v
                    else -> ly = v
                }
                return true
            }
        }
        return false
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isOn) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTick).coerceIn(1, 50)) / 1000f
            lastTick = now
            val vx = curve(lx); val vy = curve(ly)
            if (vx != 0f || vy != 0f) {
                val b = wm.currentWindowMetrics.bounds
                val speed = SPEED_DP * dp
                x = (x + vx * speed * dt).coerceIn(0f, b.width() - 1f)
                y = (y + vy * speed * dt).coerceIn(0f, b.height() - 1f)
                place()
                // Con A apretado, moverse es arrastrar (ya no es toque ni toque largo).
                if (aDown && !dragging && Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > DRAG_DP * dp) {
                    main.removeCallbacks(startHold)
                    startHold.run()
                }
            }
            // Desplazar: un deslizamiento corto desde el puntero, al revés del stick (como arrastrar la página).
            val sx = curve(rx); val sy = curve(ry)
            if (!aDown && (sx != 0f || sy != 0f) && now - lastScroll > SCROLL_EVERY) {
                lastScroll = now
                swipe(-sx * SCROLL_DP * dp, -sy * SCROLL_DP * dp)
            }
            main.postDelayed(this, 16)
        }
    }

    /** Zona muerta y curva: suave cerca del centro, rápido a fondo. */
    private fun curve(v: Float): Float {
        val a = abs(v)
        if (a < DEAD) return 0f
        val n = (a - DEAD) / (1 - DEAD)
        return sign(v) * n * n
    }

    private fun place() {
        val v = view ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = x.toInt(); lp.y = y.toInt()
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun swipe(dx: Float, dy: Float) {
        val b = wm.currentWindowMetrics.bounds
        val p = Path().apply {
            moveTo(x, y)
            lineTo((x + dx).coerceIn(0f, b.width() - 1f), (y + dy).coerceIn(0f, b.height() - 1f))
        }
        runCatching { svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, SCROLL_EVERY - 20)).build(), null, null) }
    }

    // A: soltar rápido = toque (un gesto corto y completo, lo más confiable). Mantenerlo, o moverse con A apretado,
    // empieza un toque que sigue mientras A esté apretado y acompaña al puntero (toque largo o arrastre): son tramos
    // cortos encadenados, cada uno empieza donde terminó el anterior.
    private var aDown = false
    private var dragging = false
    private var downX = 0f; private var downY = 0f
    private var stroke: GestureDescription.StrokeDescription? = null
    private var gx = 0f; private var gy = 0f

    private fun pressA() {
        aDown = true; dragging = false
        downX = x; downY = y
        main.removeCallbacks(startHold)
        main.postDelayed(startHold, LONG_MS)
    }

    private fun releaseA() {
        if (!aDown) return
        aDown = false
        main.removeCallbacks(startHold)
        // Si el toque mantenido está en curso, el próximo tramo lo termina (ver next()).
        if (!dragging) gesture(Path().apply { moveTo(x, y) }, TAP_MS)
    }

    /** Empieza el toque mantenido: desde donde se apretó A hasta donde está el puntero ahora. */
    private val startHold = Runnable {
        if (!aDown || dragging) return@Runnable
        dragging = true
        val p = Path().apply { moveTo(downX, downY); if (x != downX || y != downY) lineTo(x, y) }
        gx = x; gy = y
        send(GestureDescription.StrokeDescription(p, 0, STEP, true))
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        stroke = s
        val ok = runCatching {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) = next()
                override fun onCancelled(g: GestureDescription) { dragging = false; stroke = null }
            }, main)
        }.getOrDefault(false)
        if (!ok) { dragging = false; stroke = null }
    }

    /** Tramo siguiente: hasta donde está el puntero; el último (A suelto) levanta el dedo. */
    private fun next() {
        val prev = stroke ?: return
        if (!dragging) return
        val p = Path().apply { moveTo(gx, gy); if (x != gx || y != gy) lineTo(x, y) }
        gx = x; gy = y
        val more = aDown
        if (!more) { dragging = false; stroke = null }
        val s = prev.continueStroke(p, 0, STEP, more)
        if (more) send(s)
        else runCatching { svc.dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null) }
    }

    private fun endDrag() { aDown = false; dragging = false; stroke = null; main.removeCallbacks(startHold) }

    private fun gesture(p: Path, ms: Long) {
        runCatching { svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null) }
            .onFailure { Log.w(AutoModeService.TAG, "mouse virtual: no se pudo tocar", it) }
    }

    /**
     * El puntero, como el de Android con mouse: flecha negra con borde blanco y una sombra suave. La punta, arriba a
     * la izquierda, es donde toca.
     */
    private class Pointer(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 16.5f); lineTo(4.2f, 12.6f); lineTo(6.9f, 18.8f); lineTo(9.6f, 17.7f)
            lineTo(6.9f, 11.6f); lineTo(12.4f, 11.6f); close()
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000; style = Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(1.6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.3f; strokeJoin = Paint.Join.ROUND
        }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // la sombra borrosa
        override fun onDraw(c: Canvas) {
            c.save(); c.scale(d * 1.25f, d * 1.25f); c.translate(1.2f, 1.2f)
            c.save(); c.translate(0.8f, 1.2f); c.drawPath(arrow, shadow); c.restore()
            c.drawPath(arrow, edge); c.drawPath(arrow, fill)
            c.restore()
        }
    }

    private companion object {
        const val POINTER_DP = 30
        const val SPEED_DP = 900f      // a fondo, dp por segundo
        const val SCROLL_DP = 220f     // cuánto desplaza cada deslizamiento a fondo
        const val SCROLL_EVERY = 140L
        const val TAP_MS = 40L
        const val LONG_MS = 250L       // mantener A esto empieza el toque mantenido (toque largo si no se mueve)
        const val STEP = 50L           // duración de cada tramo del toque mantenido
        const val DRAG_DP = 10f        // moverse más que esto con A apretado = arrastrar
        const val DEAD = 0.15f
    }
}
