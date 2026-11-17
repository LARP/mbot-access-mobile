package com.mbot.access.mobile

import android.service.notification.NotificationListenerService

/**
 * Escucha de notificaciones (permiso aparte: "Acceso a notificaciones"). No guarda nada: solo mantiene la conexion
 * para que [AgentApi.notifs] pueda leer getActiveNotifications() cuando el PC lo pide.
 */
class MbotNotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: MbotNotifService? = null
    }

    override fun onListenerConnected() {
        instance = this
        Advanced.ensureA11y(applicationContext)     // solo escribe si falta nuestra Accesibilidad y hay WRITE_SECURE_SETTINGS
    }
    override fun onListenerDisconnected() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }
}
