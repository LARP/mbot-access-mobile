package com.mbot.access.mobile

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Estado compartido entre servidor HTTP, servicio de Accesibilidad, indicador y pantalla: pausa, comandos en curso y ultimo evento. */
object AgentState {
    const val GRACE_MS = 700L          // margen tras cada comando en el que los eventos NO se consideran del usuario
    const val SESSION_MS = 120_000L    // la pausa automatica solo aplica si el agente actuo hace menos de esto
    const val PAUSE_USER = "pausado por el usuario"
    const val PAUSE_TOUCH = "pausado: el usuario esta usando el telefono"

    @Volatile var paused = false
    @Volatile var pauseReason = ""
    @Volatile var onChange: (() -> Unit)? = null
    val inFlight = AtomicInteger(0)
    @Volatile var lastEnd = 0L        // uptime del fin del ultimo comando que actua
    @Volatile var lastActing = 0L     // uptime del inicio del ultimo comando que actua
    @Volatile var lastEvent = 0L      // uptime del ultimo evento de accesibilidad "de contenido" (no nuestro)

    fun now() = SystemClock.uptimeMillis()

    fun pause(reason: String) {
        if (paused) return
        pauseReason = reason
        paused = true
        onChange?.invoke()
    }

    fun resume() {
        paused = false
        pauseReason = ""
        // Tras reanudar, el usuario sigue con el telefono en la mano (cerrar la notificacion, salir de la app...): esos
        // toques no deben volver a pausar. La pausa automatica se rearma cuando el agente vuelva a actuar.
        lastActing = 0L
        onChange?.invoke()
    }

    fun begin() { inFlight.incrementAndGet(); lastActing = now() }
    fun end() { lastEnd = now(); inFlight.decrementAndGet() }

    /** Hay un comando en curso o acaba de terminar: los eventos de ahora son nuestros, no del usuario. */
    fun busy(): Boolean = inFlight.get() > 0 || now() - lastEnd < GRACE_MS

    fun sessionActive(): Boolean = lastActing != 0L && now() - lastActing < SESSION_MS

    fun autoPause(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences("mbot", Context.MODE_PRIVATE).getBoolean("autopause", true)

    fun setAutoPause(ctx: Context, v: Boolean) {
        ctx.applicationContext.getSharedPreferences("mbot", Context.MODE_PRIVATE).edit().putBoolean("autopause", v).apply()
    }

    fun pausedError(): JSONObject = JSONObject().put("ok", false).put("error", pauseReason.ifBlank { PAUSE_USER }).put("paused", true)
}
