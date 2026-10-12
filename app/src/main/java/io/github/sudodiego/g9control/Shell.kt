package io.github.sudodiego.g9control

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Comandos de shell con Shizuku (si está corriendo y dio permiso): mostrar u ocultar Game Assistant, leer su
 * registro y, si falta WRITE_SECURE_SETTINGS, `wm size`. Solo los de [allowed]: nada más, aunque se lo pidan.
 *
 * Los corre el propio servidor de Shizuku (`newProcess`), no un servicio aparte (UserService): en ZUI el
 * servicio no arranca si la app de Shizuku no está abierta ("provider is null"; ZUI no deja al shell pedir
 * proveedores de una app sin proceso). Key Mapper usa lo mismo como respaldo. `newProcess` es privado desde
 * la API 13 de Shizuku: se llama por reflexión.
 */
object Shell {
    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    const val GA_GET = "appops get ${ZuiGame.PACKAGE} SYSTEM_ALERT_WINDOW"
    const val GA_SHOW = "appops set ${ZuiGame.PACKAGE} SYSTEM_ALERT_WINDOW allow"
    const val GA_HIDE = "appops set ${ZuiGame.PACKAGE} SYSTEM_ALERT_WINDOW ignore"
    // Sin -t: logcat aplica -t al registro completo antes de filtrar, y la línea del modo quedaba fuera.
    const val GA_LOG = "logcat -d -s ZuiGameHelper -e writeSavageMode"
    const val WM_RESET = "wm size reset"
    fun wmSize(w: Int, h: Int) = "wm size ${w}x$h"
    private val WM_SIZE = Regex("wm size (reset|\\d{3,4}x\\d{3,4})")
    // Bypass charging: el servicio de batería de ZUI (IZuiBatteryManager) solo acepta apps del sistema, y shell lo es
    // (com.android.shell). 20 = setBypassChargingStatus, 21 = getBypassChargingStatus.
    const val BYPASS_GET = "service call zuibattery 21"
    const val BYPASS_ON = "service call zuibattery 20 i32 1"
    const val BYPASS_OFF = "service call zuibattery 20 i32 0"
    // Límite de carga de ZUI ("Maximum charge limit"): 89 = getStopChargingStatus, 48 = getMaxBatteryChargingLevel.
    const val LIMIT_GET = "service call zuibattery 89"
    const val LIMIT_LEVEL = "service call zuibattery 48"
    // Atrás mantenido (Dolphin sale del juego así; un Atrás normal abre su menú). Un servicio de accesibilidad solo
    // puede tocar Atrás, no mantenerlo.
    const val BACK_LONG = "input keyevent --longpress 4"
    // FPS del juego para el monitor de rendimiento: las capas de SurfaceFlinger y los tiempos de los últimos 127
    // cuadros de una (el contador global, `service call SurfaceFlinger 1013`, no se le permite al shell).
    const val SF_LIST = "dumpsys SurfaceFlinger --list"
    // Frecuencia de la pantalla: `peak_refresh_rate` y `min_refresh_rate` son privados del sistema; una app no los
    // puede escribir ni con WRITE_SECURE_SETTINGS ("You cannot keep your settings in the secure settings"), el shell sí.
    fun refreshRate(key: String, hz: Float) = "settings put system $key $hz"
    private val REFRESH = Regex("settings put system (peak|min)_refresh_rate \\d{2,3}\\.\\d+")
    fun sfLatency(layer: String) = "dumpsys SurfaceFlinger --latency $layer"
    private val SF_LATENCY = Regex("dumpsys SurfaceFlinger --latency \\S+")
    // Pantalla de la tablet con un monitor (ScreenPower, con esta misma APK): apagar o encender el panel, o apagar
    // solo la luz (brillo -1, como Extinguish; vuelve sola cuando Android cambia el brillo).
    fun screenPower(on: Boolean) = screenCmd("power ${if (on) 2 else 0}")
    val SCREEN_LIGHT_OFF get() = screenCmd("light -1")
    private fun screenCmd(args: String) =
        "app_process -Djava.class.path=${app?.applicationInfo?.sourceDir} /system/bin ${ScreenPower::class.java.name} $args"
    private val SCREEN = Regex("power [02]|light -1")

    // WiFi y Bluetooth desde el panel: una app ya no puede encenderlos ni apagarlos (Android 10 y 13), el shell sí.
    const val WIFI_ON = "svc wifi enable"
    const val WIFI_OFF = "svc wifi disable"
    const val BT_ON = "svc bluetooth enable"
    const val BT_OFF = "svc bluetooth disable"

    // Arrastrar con el mouse virtual: el dedo inyectado por el shell no lo cancela el stick (los gestos del servicio
    // de accesibilidad sí: Android los cancela apenas llega un movimiento real del control).
    fun touch(action: String, x: Int, y: Int) = "input motionevent $action $x $y"
    private val TOUCH = Regex("input motionevent (DOWN|MOVE|UP) \\d{1,5} \\d{1,5}")

    // Apps activas (con un servicio en primer plano: video, música, descargas), como "Apps activas" de los ajustes
    // rápidos de Android, y detenerlas como su botón Detener (`am stop-app`: la app y sus servicios).
    const val PROCESSES = "dumpsys activity processes"
    // RAOfflineProxy encendido: su servicio del proxy corre (ver RaProxy).
    const val RA_PROXY = "dumpsys activity services ${RaProxy.PACKAGE}"
    // Administrador de tareas (Memoria): RAM y CPU de cada proceso, en dos pasadas de 2 s (la primera no tiene CPU; con
    // 1 s, top mide de a 1 % y casi todo salía 0).
    const val TOP = "top -b -n 2 -d 2 -m 500 -o USER,%CPU,RES,NAME"
    fun stopApp(pkg: String) = "am stop-app $pkg"
    private val STOP_APP = Regex("am stop-app [A-Za-z0-9_.]+")
    // ¿Volvió a estar corriendo una app detenida? (si no está corriendo, pidof sale con 1 y run da null).
    fun pidof(pkg: String) = "pidof -s $pkg"
    private val PIDOF = Regex("pidof -s [A-Za-z0-9_.]+")

    private fun allowed(cmd: String) =
        cmd in setOf(GA_GET, GA_SHOW, GA_HIDE, GA_LOG, BYPASS_GET, BYPASS_ON, BYPASS_OFF, LIMIT_GET, LIMIT_LEVEL, BACK_LONG, SF_LIST,
            WIFI_ON, WIFI_OFF, BT_ON, BT_OFF, PROCESSES, TOP, RA_PROXY) || TOUCH.matches(cmd) || STOP_APP.matches(cmd) || PIDOF.matches(cmd) || WM_SIZE.matches(cmd) || SF_LATENCY.matches(cmd) || REFRESH.matches(cmd) || cmd == INPUT_LIST ||
            (app != null && cmd.startsWith(screenCmd("")) && SCREEN.matches(cmd.removePrefix(screenCmd(""))))

    /** Para Ajustes (Compose lo vuelve a leer al cambiar). */
    var state by mutableStateOf(State.NOT_RUNNING)
        private set

    private const val REQUEST = 9
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    private val newProcess: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .apply { isAccessible = true }
        }.onFailure { Log.w(AutoModeService.TAG, "Shizuku sin newProcess", it) }.getOrNull()
    }

    private val onBinder = Shizuku.OnBinderReceivedListener { main.post(::refresh) }
    private val onDead = Shizuku.OnBinderDeadListener { main.post(::refresh) }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post(::refresh) }

    /** Una vez, al arrancar la app o el servicio de accesibilidad (los dos viven en el mismo proceso). */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refresh()
    }

    fun refresh() {
        state = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    }

    /** La app de Shizuku, para abrirla e iniciarlo (null si no está instalada). */
    const val PACKAGE = "moe.shizuku.privileged.api"
    fun launchIntent(ctx: Context): android.content.Intent? =
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hay algo elegido que necesita Shizuku (bypass automático, ocultar Game Assistant, tablet sin luz o apagada con el monitor): vale la pena avisar si falta. */
    fun wanted(ctx: Context) = AutoRules.smartBypass(ctx) || ZuiBattery.ownLimit(ctx) != null || AutoRules.gaHidden(ctx) == true || Monitor.screen(ctx).let { it == Monitor.Screen.OFF || it == Monitor.Screen.NO_LIGHT }

    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(REQUEST)
    }

    /** Corre un comando permitido (fuera del hilo principal) y devuelve su salida. Null si no está listo, no está permitido o falló. */
    suspend fun run(command: String, timeoutSec: Long = 4): String? = withContext(Dispatchers.IO) {
        refresh()
        if (state != State.READY || !allowed(command)) return@withContext null
        val m = newProcess ?: return@withContext null
        runCatching {
            val p = m.invoke(null, command.split(' ').toTypedArray(), null, null) as Process
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }
            reader.start()
            // waitFor(tiempo) no sirve: pregunta exitValue() mientras corre y el proceso de Shizuku responde con un
            // error ("process hasn't exited") en vez de "todavía no". Se espera con waitFor() en otro hilo.
            var code: Int? = null
            val waiter = Thread { code = runCatching { p.waitFor() }.getOrNull() }
            waiter.start()
            waiter.join(TimeUnit.SECONDS.toMillis(timeoutSec))
            if (waiter.isAlive) { p.destroy(); error("tardó demasiado") }
            reader.join(500)
            // ScreenPower termina matándose (137 = SIGKILL) para saltarse los destructores de libandroid_servers.
            if (code != 0 && !(code == 137 && command.contains(ScreenPower::class.java.name))) error("salió con $code")
            synchronized(out) { out.toString() }
        }.onFailure { Log.w(AutoModeService.TAG, "Shizuku: $command falló", it) }.getOrNull()
    }

    // Eventos crudos de un dispositivo de entrada (el touchpad del G9, para M dos veces cuando una app captura el
    // puntero): solo leerlos, no los bloquea. `getevent -pl` lista los dispositivos y sale.
    const val INPUT_LIST = "getevent -pl"
    fun inputEvents(dev: String) = "getevent -lq $dev"
    private val INPUT_EVENTS = Regex("getevent -lq /dev/input/event\\d{1,2}")

    /**
     * Deja corriendo un comando permitido y entrega cada línea de su salida (en otro hilo). Devuelve el proceso para
     * cortarlo con `destroy()`; null si no está listo o no está permitido.
     */
    fun stream(command: String, onLine: (String) -> Unit): Process? {
        refresh()
        if (state != State.READY || !(command == INPUT_LIST || INPUT_EVENTS.matches(command))) return null
        val m = newProcess ?: return null
        return runCatching {
            val p = m.invoke(null, command.split(' ').toTypedArray(), null, null) as Process
            Thread { runCatching { p.inputStream.bufferedReader().forEachLine(onLine) } }.apply { isDaemon = true }.start()
            p
        }.onFailure { Log.w(AutoModeService.TAG, "Shizuku: $command falló", it) }.getOrNull()
    }
}
