package com.mbot.access.mobile.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.mbot.access.mobile.AdbPairReceiver
import com.mbot.access.mobile.HelperClient
import java.util.concurrent.Executors

/**
 * FORMA B del modo avanzado (Android 11+, sin PC): la app es su propio cliente de la depuracion inalambrica.
 *   1. Emparejar: el usuario abre "Depuracion inalambrica > Vincular dispositivo con codigo"; la app descubre `_adb-tls-pairing._tcp`
 *      y recibe el codigo de 6 digitos por una notificacion con campo de respuesta (RemoteInput), asi no hay que salir de Ajustes.
 *   2. Conectar: descubre `_adb-tls-connect._tcp`, TLS 1.3 con la clave RSA emparejada, y ejecuta por `shell:` el mismo script que `phone advanced start`.
 * SIN PROBAR EN DISPOSITIVO: no hay Android 11+ para probarlo. Contrastado con AOSP (packages/modules/adb) y con las pruebas JVM de las partes puras.
 */
object WirelessAdb {
    private const val TAG = "MbotAdb"
    private const val CHANNEL = "mbot_advanced"
    private const val NOTIF_ID = 4107
    const val ACTION_CODE = "com.mbot.access.mobile.ADB_PAIR_CODE"
    const val KEY_CODE = "code"
    private const val PREFS = "mbot"
    private const val P_PAIRED = "adb_paired"
    private const val P_LAST_AUTO = "adb_last_auto"
    private val io = Executors.newSingleThreadExecutor()
    private var pairWatcher: NsdWatcher? = null

    /** Ultimo mensaje de progreso/resultado para mostrar en la pantalla. */
    @Volatile var status: String = ""
    @Volatile var busy: Boolean = false

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 30

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaired(ctx: Context): Boolean = supported() && prefs(ctx).getBoolean(P_PAIRED, false) && AdbKeyVault(ctx).exists()

    fun unpair(ctx: Context) {
        prefs(ctx).edit().putBoolean(P_PAIRED, false).apply()
        AdbKeyVault(ctx).delete()
        status = "Emparejamiento olvidado (quita tambien este equipo en Ajustes > Depuracion inalambrica)."
    }

    // ------------------------------------------------------------------ emparejar
    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Modo avanzado (emparejamiento)", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notify(ctx: Context, title: String, text: String, withInput: Boolean, ongoing: Boolean = false) {
        channel(ctx)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(Icon.createWithResource(ctx, android.R.drawable.stat_sys_data_bluetooth))
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
        if (withInput) {
            val intent = Intent(ACTION_CODE).setComponent(ComponentName(ctx, AdbPairReceiver::class.java))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, intent, flags)
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("Código de 6 dígitos").build()
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_menu_send), "Escribir código", pi)
                .addRemoteInput(ri).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
    }

    /** Pone la app a escuchar el servicio de emparejamiento y avisa con una notificacion para escribir el codigo. */
    fun startPairing(ctx: Context): String {
        if (!supported()) return "Requiere Android 11 o superior (la depuración inalámbrica no existe antes)."
        val app = ctx.applicationContext
        pairWatcher?.stop()
        pairWatcher = NsdWatcher(app, "_adb-tls-pairing._tcp.").also { it.start() }
        notify(app, "Emparejar con depuración inalámbrica",
            "En Ajustes > Opciones de desarrollador > Depuración inalámbrica pulsa \"Vincular dispositivo con código de sincronización\" y escribe aquí el código de 6 dígitos (pulsa Escribir código).",
            true, ongoing = true)
        status = "Esperando el código: abre \"Vincular dispositivo con código\" en Depuración inalámbrica y responde a la notificación."
        return status
    }

    fun cancelPairing(ctx: Context) {
        pairWatcher?.stop(); pairWatcher = null
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Lo llama AdbPairReceiver con el codigo escrito en la notificacion. */
    fun submitCode(ctx: Context, code: String) {
        val app = ctx.applicationContext
        val c = code.trim()
        if (!Regex("[0-9]{6}").matches(c)) {
            notify(app, "Código no válido", "Deben ser 6 dígitos. Pulsa Escribir código para repetirlo.", true, ongoing = true)
            return
        }
        notify(app, "Emparejando…", "Comprobando el código con el teléfono.", false, ongoing = true)
        io.execute {
            busy = true
            try {
                val target = pairWatcher?.current()
                    ?: throw java.io.IOException("no veo el servicio de emparejamiento: deja abierto el diálogo \"Vincular dispositivo con código\" y repite")
                val id = AdbKeyVault(app).loadOrCreate()
                val tls = AdbTls.context(id.keys.private, id.certDer)
                AdbPairing.pair(target.address.hostAddress ?: target.hostString, target.port, c, tls, id.adbPublicKey)
                prefs(app).edit().putBoolean(P_PAIRED, true).apply()
                pairWatcher?.stop(); pairWatcher = null
                status = "Emparejado. Iniciando el asistente…"
                notify(app, "Emparejado", "Iniciando el asistente…", false)
                val r = connectAndStart(app)
                notify(app, if (r.startsWith("ok")) "Modo avanzado activo" else "Emparejado, pero el asistente no arrancó", r, false)
            } catch (e: Exception) {
                Log.w(TAG, "emparejamiento fallido", e)
                status = "Emparejamiento fallido: ${e.message}"
                notify(app, "Emparejamiento fallido", "${e.message}. Pulsa Escribir código para reintentar.", true, ongoing = true)
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------------ conectar y arrancar el asistente
    /** El mismo script que `phone advanced start` (assets/mbot-helper.sh). */
    private fun script(ctx: Context): String = ctx.assets.open("mbot-helper.sh").bufferedReader().use { it.readText() }

    /**
     * Conecta por `_adb-tls-connect._tcp` (la depuracion inalambrica debe estar encendida) y lanza el asistente.
     * Devuelve "ok ..." o "ERR ...". Bloquea: llamar desde un hilo de trabajo.
     */
    fun connectAndStart(ctx: Context): String {
        if (!supported()) return "ERR requiere Android 11+"
        if (!isPaired(ctx)) return "ERR no hay emparejamiento: usa \"Emparejar con depuración inalámbrica\""
        val app = ctx.applicationContext
        val watcher = NsdWatcher(app, "_adb-tls-connect._tcp.")
        try {
            watcher.start()
            val target = watcher.await(10000)
                ?: return "ERR no encuentro la depuración inalámbrica: actívala en Ajustes > Opciones de desarrollador (requiere Wi-Fi)"
            val id = AdbKeyVault(app).loadOrCreate()
            val tls = AdbTls.context(id.keys.private, id.certDer)
            AdbConnection(target.address.hostAddress ?: target.hostString, target.port, tls).use { c ->
                c.connect()
                val out = c.shell(script(app), 30000).trim()
                if (!out.contains("ok asistente")) return "ERR el script no confirmó el arranque: ${out.take(200)}"
            }
            // la app recibe el Binder por el proveedor: esperar a verlo
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 8000) {
                if (HelperClient.info(force = true) != null) return "ok asistente en marcha (forma B)"
                Thread.sleep(300)
            }
            return "ERR el asistente arrancó pero la app no lo ve (¿Accesibilidad desactivada?)"
        } catch (e: Exception) {
            Log.w(TAG, "connectAndStart", e)
            return "ERR ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            watcher.stop()
        }
    }

    /** Arranque desde un hilo de fondo (boton de la pantalla). */
    fun connectAndStartAsync(ctx: Context) {
        if (busy) return
        io.execute {
            busy = true
            status = "Conectando por depuración inalámbrica…"
            status = connectAndStart(ctx)
            busy = false
        }
    }

    // ------------------------------------------------------------------ reconexion automatica
    /**
     * Si el asistente murio (reinicio) y hay emparejamiento: intenta la forma B. Con WRITE_SECURE_SETTINGS enciende antes
     * `adb_wifi_enabled`. Como mucho una vez cada 5 minutos. Devuelve el resultado o null si no hizo nada.
     */
    fun autoStart(ctx: Context): String? {
        if (!supported() || !isPaired(ctx) || busy) return null
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong(P_LAST_AUTO, 0) < 5 * 60_000) return null
        p.edit().putLong(P_LAST_AUTO, now).apply()
        try {
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 0) {
                Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)    // solo en este caso: ya emparejado, modo avanzado deseado y asistente caido
                Thread.sleep(2500)
            }
        } catch (e: Exception) { Log.w(TAG, "no se pudo encender adb_wifi_enabled", e) }
        busy = true
        return try { connectAndStart(ctx).also { status = it } } finally { busy = false }
    }

    fun openDeveloperSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
