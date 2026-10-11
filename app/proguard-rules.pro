# Los nombres de los enum se guardan en Prefs y viajan en intents (estilo del dibujo, modo destino):
# la reducción de código no debe cambiarlos.
-keepclassmembers enum io.github.sudodiego.g9control.** { *; }

# ScreenPower corre aparte con app_process (por nombre): no se renombra ni se quita.
-keep class io.github.sudodiego.g9control.ScreenPower { public static void main(java.lang.String[]); }

# KeyInject también (botones del control desde el shell, como Key Mapper).
-keep class io.github.sudodiego.g9control.KeyInject { public static void main(java.lang.String[]); }
