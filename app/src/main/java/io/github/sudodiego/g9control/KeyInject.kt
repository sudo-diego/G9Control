package io.github.sudodiego.g9control

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent

/**
 * Apretar botones del control como si fuera el control: el evento sale con el id del G9 (o del control que sea) y
 * como gamepad, así que los emuladores lo toman como un botón de verdad (RetroArch ignora las teclas de `input
 * keyevent`, que llegan de un teclado virtual). Es lo que hace Key Mapper con Shizuku. Corre aparte con permisos de
 * shell (Shizuku), como [ScreenPower]: `app_process` con esta APK.
 *
 * Argumentos: `<id del dispositivo> <ms apretado> <tecla> [tecla…]`. Las teclas se aprietan en orden (una
 * combinación: la primera queda apretada mientras se aprietan las demás) y se sueltan al revés.
 */
@SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi") // corre fuera de la app, como shell
object KeyInject {
    @JvmStatic
    fun main(args: Array<String>) {
        val device = args[0].toInt()
        val hold = args[1].toLong()
        val keys = args.drop(2).map { it.toInt() }
        val im = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        val inject = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        // Id negativo: como una tecla de teclado (fuente solo teclado), para los atajos de teclado de un emulador.
        val source = if (device < 0) InputDevice.SOURCE_KEYBOARD else InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, key: Int) {
            val now = SystemClock.uptimeMillis()
            inject.invoke(im, KeyEvent(down, now, action, key, 0, 0, device, 0, 0, source), 0)
        }
        // Primero un toque a un botón que nadie usa (BUTTON_16): la primera vez que RetroArch recibe algo del dispositivo
        // "Virtual" lo usa para registrarlo ("Virtual (0/0)") y no lo cuenta; sin esto había que tocar la guía dos veces.
        send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_16); Thread.sleep(30); send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_16)
        Thread.sleep(300)
        // 200 ms entre botones: RetroArch activa sus atajos unos cuadros después de apretar el primero
        // (input_hotkey_block_delay, 5 cuadros ≈ 83 ms); con 40 ms el segundo a veces llegaba antes y no hacía nada.
        keys.forEachIndexed { i, k -> send(KeyEvent.ACTION_DOWN, k); if (i < keys.size - 1) Thread.sleep(200) }
        Thread.sleep(hold)
        keys.reversed().forEach { send(KeyEvent.ACTION_UP, it); Thread.sleep(20) }
        System.out.flush()
    }
}
