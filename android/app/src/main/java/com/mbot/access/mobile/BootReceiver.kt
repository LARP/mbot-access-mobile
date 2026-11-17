package com.mbot.access.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tras reiniciar o actualizar la app: reactiva la Accesibilidad (si hay WRITE_SECURE_SETTINGS) y reintenta el asistente por la forma B. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pr = goAsync()
        Thread {
            try { Advanced.onBoot(context.applicationContext) } finally { pr.finish() }
        }.start()
    }
}
