package io.github.sudodiego.g9control

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * RAOfflineProxy (github.com/misantronic/RAOfflineProxy, GPL-3.0, aprobado por RetroAchievements): logros de
 * RetroAchievements sin internet. Corre un servidor en la tablet (127.0.0.1:8080) y apunta los emuladores a él
 * (RetroArch `cheevos_custom_host`, Dolphin `RetroAchievements.ini` → `HostUrl`); sin internet guarda los logros en
 * cola y los manda al volver. En Android 15 se usa con "Manual Emulator Setup" (cambia la configuración con Shizuku).
 *
 * TBF no copia lo que hace: lo facilita. Avisa al abrir un emulador sin internet con el proxy apagado, muestra su
 * estado en el panel, lo protege de Detener (cerrado a la fuerza deja a los emuladores apuntando a un servidor que no
 * existe) y prepara el encendido cerrando los emuladores (lo pide su guía: tienen que estar cerrados).
 */
object RaProxy {
    const val PACKAGE = "com.raofflineproxy"

    /**
     * Emuladores que RAOfflineProxy configura en Android y cuyo paquete se conoce (también ARMSX2 y WatermelonDS: agregar
     * su paquete cuando se instalen). AetherSX2 / NetherSX2 no están soportados.
     */
    val EMULATORS = setOf(
        "com.retroarch", "com.retroarch.aarch64", "org.dolphinemu.dolphinemu", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
        "com.flycast.emulator"
    )

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getApplicationInfo(PACKAGE, 0) }.isSuccess

    fun isEmulator(pkg: String?) = pkg != null && (pkg in EMULATORS || pkg.startsWith("com.retroarch"))

    /** Hay internet de verdad (red validada). Sin el permiso de internet: solo se mira el estado de la red. */
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Lo último que se supo (null = no se sabe: sin Shizuku). */
    @Volatile var activeNow: Boolean? = null

    /**
     * ¿Está encendido? Primero su proveedor público `content://com.raofflineproxy.config` (fila con `proxy_running`,
     * `proxy_host`, `proxy_port`; lo publica para los emuladores, se lee sin permisos ni Shizuku). Si no responde, su
     * servicio del proxy (`ProxyService`) en primer plano, con Shizuku.
     */
    suspend fun readActive(ctx: Context): Boolean? =
        (fromProvider(ctx) ?: Shell.run(Shell.RA_PROXY)?.let { it.contains("ProxyService") }).also { activeNow = it }

    private fun fromProvider(ctx: Context): Boolean? = runCatching {
        ctx.contentResolver.query(android.net.Uri.parse("content://$PACKAGE.config"), null, null, null, null)?.use { c ->
            val col = c.getColumnIndex("proxy_running")
            if (col >= 0 && c.moveToFirst()) c.getInt(col) == 1 else null
        }
    }.getOrNull()

    fun open(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Antes de encender el proxy: cierra los emuladores que estén corriendo (con Shizuku) y abre RAOfflineProxy. */
    suspend fun prepare(ctx: Context): List<String> {
        val closed = mutableListOf<String>()
        if (Shell.state == Shell.State.READY) for (pkg in EMULATORS) {
            if (!Shell.run(Shell.pidof(pkg)).isNullOrBlank() && Shell.run(Shell.stopApp(pkg)) != null) closed += pkg
        }
        open(ctx)
        return closed
    }
}
