# TBF Companion

**Una compañera para jugar en la tablet Lenovo Legion Y700 Gen 3, libre y sin cuenta, que además configura todo el Legion Gaming Controller G9.**

[English](README.md)

> **No está afiliada con Lenovo.** "Legion" y "Lenovo" son marcas de Lenovo. Es una app independiente y no oficial. Habla con el G9 por USB con un protocolo reconstruido estudiando la app oficial y probando el control; no contiene código ni recursos de la app oficial.

> **Esta app está hecha con IA.** Todo el código, la investigación y este README los escribió Claude (la IA de Anthropic) con Claude Code. Yo describí lo que quería, probé cada versión en mi tablet y mi control, y conté qué funcionaba y qué no.

## Qué es

Una capa de consola para jugar en la Y700: un panel que se abre desde cualquier juego, capturas limpias con un aviso como el de la Switch, herramientas de batería y calor, frecuencia de pantalla por juego, y una app que se maneja entera con el control. Sirve con **cualquier control**; el **G9** tiene su propia sección para configurar todo lo que el hardware permite.

Sin cuenta, sin permiso de internet, sin publicidad. Lo que necesita más permisos que una app normal se ve atenuado hasta activarlo, y la app dice por qué.

Tiene tres secciones (LT / RT cambian de sección, LB / RB de pestaña):

### Tablet (cualquier control)

| | |
|---|---|
| **Inicio** | Batería (W ahora, promedio del último minuto y autonomía; cargando, tiempo hasta el límite o a 100 %), temperatura, Hz, CPU; mosaicos de bypass, monitor y 16:9; memoria con las apps corriendo; controles conectados. |
| **Batería** | Límite de carga propio (70–90 %), bypass manual y automático, aviso de batería baja (15, 10 y 5 %, como la Switch), consumo por app y **sesiones de juego**: W, CPU y temperaturas por juego, modo de rendimiento y Hz, para comparar modos con datos de verdad. |
| **Pantalla** | 16:9 para un monitor (automático por monitor en espejo) y la pantalla de la tablet oscura, sin luz o apagada mientras está el monitor. |
| **Juego** | Aviso y sonido de las capturas, el aspecto del panel (fondo, transparencia, forma y tamaño, mini monitor), el monitor de rendimiento, la frecuencia en juegos y Game Assistant. |
| **Controles** | Qué hace el botón guía (tocar: Atrás; mantener: panel; + RB: captura, mantener RB: ráfaga; + LB: Atrás mantenido; 5 s: el G9 se apaga y la tablet se bloquea), qué hace tocarla en cada emulador (Vista + Menú sale de RetroArch), L3 + R3 para controles sin botón guía y el volumen invertido. |
| **Probar control** | Botones, gatillos y sticks de cualquier control, con los controles conectados y los sticks en números para ver si tienen deriva. |

### Panel de juego

Mantén el botón guía (o L3 + R3, o el botón de accesibilidad) en cualquier app: un panel como los ajustes rápidos de Android sobre el juego, sin quitarle el foco. Mini monitor (RAM libre, W, CPU, temperatura de la batería), brillo y volumen, captura, Game Assistant, modo de rendimiento, Hz para esta app, bloquear gestos, apps recientes para volver a ellas (como un juego en suspensión), 16:9, WiFi y Bluetooth, mouse virtual, Atrás / Inicio / Recientes y el modo del G9. Se mueve con el stick o la cruceta, A elige y B cierra. Fondo: transparente, degradado o tu propia imagen o GIF, también translúcidos.

### G9

| | |
|---|---|
| **Modos** | Android, X-Input y **DS5** (el mejor en Android: vibración, giroscopio, touchpad), que la app oficial no puede poner. Cambia solo por juego, con una guía en pantalla de Vista + Menú para volver. |
| **Luces, sticks, vibración** | Tiras de luz y el botón Home; zonas muertas y rango de gatillos con vista en vivo; gatillo rápido; intensidad de vibración. |
| **Traseros y macros** | L4, R4, L5 y R5 como cualquier botón (también Home y las direcciones de los sticks) o una macro, elegidos en una grilla con forma de control; macros grabadas con el control, con línea de tiempo. |
| **Perfiles** | Configuraciones completas con respaldo en archivo. |

Cuida la memoria del control: solo escribe lo que cambió, después de una pausa corta.

### Ajustes

Idioma, tema, fondo de la app (degradado animado, imagen o GIF, **tu fondo de pantalla animado** —también Wallpaper Engine— o liso) y los permisos en un solo lugar.

## Requisitos

- Lenovo Legion Y700 Gen 3 (probada con ZUI 17, Android 15). La sección Tablet sirve con cualquier control; la del G9 necesita el Legion Gaming Controller G9 (probado con firmware 0.52).

## Instalar

1. Descarga la APK de [Releases](../../releases) y ábrela en la tablet (permite instalar desde ese origen si lo pide).
2. Abre TBF Companion; una configuración inicial te guía.
   - Para el G9: acopla la tablet en **modo Android** (luz azul oscuro; desde X-Input o DS5 mantén **Vista + Menú** 2 s) y permite el acceso USB con **Siempre**.
   - Activa el **servicio de accesibilidad** para el panel, los atajos y todo lo automático.

### Sobre el servicio de accesibilidad

El panel de juego, los atajos, las capturas, el cambio por juego y lo automático usan un servicio de accesibilidad, que:

- ve qué app está en pantalla (cambios de ventana) y los botones del control (y, con el panel abierto, los sticks, para que no lleguen al juego);
- saca capturas cuando lo pides, solo de la ventana del juego, guardadas en la tablet;
- hace gestos solo sobre Game Assistant de la tablet (abrir su panel y cambiar su modo de rendimiento).

Para capturar solo la ventana del juego Android pide el permiso de ver el contenido de las ventanas; la app lo usa solo para la lista de ventanas, nunca su texto. La app no tiene permiso de internet: nada sale de la tablet. Si Android dice **"Ajuste restringido"**: Ajustes → Apps → TBF Companion → ⋮ → **Permitir ajustes restringidos**.

### Opcional: Shizuku y un permiso de adb

Casi todo funciona sin nada más.

- **16:9, brillo desde el panel, bloquear gestos y brillo automático en juegos:** un permiso que se da una vez desde un computador (queda):
  `adb shell pm grant io.github.sudodiego.g9control android.permission.WRITE_SECURE_SETTINGS`
- **Bypass y límite de carga, ocultar Game Assistant y su modo de rendimiento, FPS en el monitor, apps corriendo (RAM, CPU, detener), encender o apagar WiFi y Bluetooth, arrastrar con el mouse virtual, la pantalla de la tablet apagada con un monitor, atajos de emuladores y mantener los Hz elegidos en modo Ahorro:** [Shizuku](https://shizuku.rikka.app/) corriendo. La app solo le pide una lista fija de comandos. Sin él, eso se ve atenuado y la app explica qué falta.

## Límites

- **Sin actualizaciones de firmware** del G9, a propósito (una actualización fallida puede dejarlo inservible).
- El G9 solo se configura en **modo Android**; volver desde X-Input o DS5 es siempre con Vista + Menú (la app te guía).
- Android 15 marca los botones enviados por software como de un dispositivo "Virtual": los emuladores aceptan combinaciones como Vista + Menú, pero no se pueden asignar como un botón propio del G9.
- En modo Android los juegos hacen vibrar la tablet, no el G9; su jack de 3,5 mm no funciona en Android (firmware).
- Capturas: como mucho una por segundo (el límite de Android para servicios de accesibilidad).
- Transmitiendo desde un PC (Artemis, Moonlight…) el botón guía va al PC.
- Algunas apps se cierran al cambiar entre 16:9 y 16:10 (Dolphin, por ejemplo).

## Compilar

```
./gradlew assembleRelease
```

Sin un archivo `keystore.properties` la release se firma con la clave de depuración. Las notas del protocolo y todo lo probado están en [CONTEXTO.md](CONTEXTO.md).

## Licencia

[GPL-3.0](LICENSE).
