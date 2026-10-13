package io.github.sudodiego.g9control

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Sonidos de navegación (moverse, elegir, volver, cambiar de pestaña): WAV de res/raw (nav_*.wav), hechos una vez en
 * el PC como los de captura (no se generan en la app). Van con SoundPool y **sin pedir el foco de audio**: se
 * mezclan encima de lo que suene, así Cocoon (que pausa su música cuando otra app pide el audio) no se corta. En la
 * app si están encendidos; en el panel de juego solo si además se pidió ([AutoRules]-like prefs en [Prefs]).
 */
object UiSounds {
    /** MOVE: se movió la selección; SELECT: A; BACK: se cerró o volvió algo; TAB: otra pestaña o sección; OPEN: se abrió el panel; EXIT: se salió de la app. */
    enum class Kind { MOVE, SELECT, BACK, TAB, OPEN, EXIT }

    private var pool: SoundPool? = null
    private val ids = mutableMapOf<Kind, Int>()
    private var lastMove = 0L

    private fun ensure(ctx: Context): SoundPool {
        pool?.let { return it }
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            // Por el volumen de juegos/multimedia: el de "sonidos del sistema" suele estar en 0 en la tablet (no se oía).
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        val app = ctx.applicationContext
        ids[Kind.MOVE] = p.load(app, R.raw.nav_move, 1)
        ids[Kind.SELECT] = p.load(app, R.raw.nav_select, 1)
        ids[Kind.BACK] = p.load(app, R.raw.nav_back, 1)
        ids[Kind.TAB] = p.load(app, R.raw.nav_tab, 1)
        ids[Kind.OPEN] = p.load(app, R.raw.nav_open, 1)
        ids[Kind.EXIT] = p.load(app, R.raw.nav_exit, 1)
        pool = p
        return p
    }

    /** Cargar los sonidos de antemano (SoundPool carga en segundo plano: el primero se perdía). */
    fun preload(ctx: Context) { if (Prefs.navSounds(ctx)) runCatching { ensure(ctx) } }

    /** [panel]: viene del panel de juego (suena solo con "También en el panel"). */
    fun play(ctx: Context, kind: Kind, panel: Boolean = false) {
        if (!Prefs.navSounds(ctx) || (panel && !Prefs.navSoundsPanel(ctx))) return
        // Moverse rápido (mantener la cruceta) no hace un zumbido: como mucho uno cada 45 ms.
        if (kind == Kind.MOVE) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMove < 45) return
            lastMove = now
        }
        val v = Prefs.navVolume(ctx) / 100f
        runCatching { ensure(ctx).let { p -> ids[kind]?.let { p.play(it, v, v, 1, 0, 1f) } } }
    }
}

/**
 * Música ambiental de la app: un archivo de audio que se elige (no se incluye música: derechos de autor), en bucle,
 * solo mientras la app está adelante. Pide el foco de audio al empezar (Cocoon, que queda detrás, pausa la suya) y
 * lo suelta al salir, para que la de Cocoon vuelva. Si otra app pide el audio, se pausa.
 */
object AmbientMusic {
    /** Para Ajustes: encendida y con archivo (cambia al elegir uno, sin volver a abrir la página). */
    var on by androidx.compose.runtime.mutableStateOf(false)
    var hasFile by androidx.compose.runtime.mutableStateOf(false)
    fun load(ctx: Context) { on = Prefs.music(ctx); hasFile = Prefs.musicUri(ctx) != null }

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    fun start(ctx: Context) {
        val uri = Prefs.musicUri(ctx)
        if (!Prefs.music(ctx) || uri == null || player != null) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        runCatching {
            val p = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(ctx.applicationContext, Uri.parse(uri))
                isLooping = true
                val v = Prefs.musicVolume(ctx) / 100f
                setVolume(v, v)
                prepare()
            }
            val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                        AudioManager.AUDIOFOCUS_GAIN -> player?.start()
                    }
                }.build()
            if (audio.requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) p.start()
            player = p; focus = f
        }.onFailure { android.util.Log.w("TBF", "música ambiental", it) }
    }

    fun stop(ctx: Context) {
        player?.runCatching { stop(); release() }
        player = null
        focus?.let { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Volumen cambiado en Ajustes: se aplica al instante. */
    fun setVolume(ctx: Context) { val v = Prefs.musicVolume(ctx) / 100f; player?.setVolume(v, v) }

    /** Encendida, apagada o con otro archivo: se reinicia. */
    fun restart(ctx: Context) { stop(ctx); start(ctx) }
}
