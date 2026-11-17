package com.mbot.access.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Servicio de Accesibilidad: es la unica "llave" que necesita el modo basico (sin depuracion USB). */
class MbotA11yService : AccessibilityService() {
    companion object {
        const val TAG = "MbotAgent"
        @Volatile var instance: MbotA11yService? = null

        /** Eventos que no indican que la pantalla cambio (accesibilidad, hover, anuncios...). */
        private val IGNORED = setOf(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START, AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START, AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
        )
    }

    private var server: AgentServer? = null
    private var nsd: NsdAdvertiser? = null
    @Volatile var indicator: Indicator? = null
    @Volatile private var monitoring = false
    private var scrollWindow = 0L
    private var scrollCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        indicator = Indicator(this).also { ind -> AgentState.onChange = { ind.refresh() } }
        startServer()
        nsd = NsdAdvertiser(this).also { it.start(AgentServer.PORT) }
        startHelperMonitor()
    }

    /** Cada ~30 s: si el asistente cayo y el usuario lo quiere (y hay emparejamiento inalambrico en Android 11+), intenta arrancarlo de nuevo. */
    private fun startHelperMonitor() {
        if (monitoring) return
        monitoring = true
        Thread({
            while (monitoring && instance != null) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                try { Advanced.monitorTick(applicationContext) } catch (e: Exception) { Log.w(TAG, "monitor del asistente", e) }
            }
        }, "mbot-helper-monitor").apply { isDaemon = true }.start()
    }

    private fun startServer() {
        stopServer()
        try {
            server = AgentServer(this, AgentApi(this)).also { it.start() }
            Log.i(TAG, "servidor HTTP en el puerto ${AgentServer.PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo iniciar el servidor", e)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun stopAll() {
        monitoring = false
        stopServer()
        nsd?.stop(); nsd = null
        AgentState.onChange = null
        indicator?.destroy(); indicator = null
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return                      // nuestra pastilla / nuestra app: nunca cuenta
        val type = event.eventType
        val now = AgentState.now()
        if (type !in IGNORED && !(pkg == "com.android.systemui" && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            AgentState.lastEvent = now                      // lo usa ChangeWait (espera de cambio tras cada accion)
        }
        detectUserTouch(type, now)
    }

    /**
     * Pausa automatica si el USUARIO toca el telefono mientras el agente trabaja: solo clics, pulsaciones largas y
     * desplazamientos largos (no cambios de contenido, que dan falsos positivos con animaciones), fuera de un comando en curso
     * y de los 700 ms posteriores, y solo si el agente actuo en los ultimos 2 minutos.
     */
    private fun detectUserTouch(type: Int, now: Long) {
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        if (AgentState.paused || AgentState.busy() || !AgentState.sessionActive() || !AgentState.autoPause(this)) return
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // un deslizamiento con el dedo emite muchos eventos seguidos (uno por fotograma); una lista que se recarga o un
            // carrusel emiten 1-3. Hacen falta 5 en menos de 1 s.
            if (now - scrollWindow > 1000) { scrollWindow = now; scrollCount = 0 }
            if (++scrollCount < 5) return
            scrollCount = 0
        }
        Log.i(TAG, "toque del usuario detectado (tipo $type): pausa")
        AgentState.pause(AgentState.PAUSE_TOUCH)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
