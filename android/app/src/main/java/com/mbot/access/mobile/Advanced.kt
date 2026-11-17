package com.mbot.access.mobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.mbot.access.mobile.adb.WirelessAdb

/**
 * Logica del modo avanzado que vive en la app (no en el asistente): permiso WRITE_SECURE_SETTINGS, reactivar la Accesibilidad
 * si se cae, y reconexion del asistente tras un reinicio. El asistente en si esta en com.mbot.access.mobile.helper.
 */
object Advanced {
    private const val TAG = "MbotAdv"
    private const val PREFS = "mbot"
    const val SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasSecure(ctx: Context): Boolean = ctx.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED

    /** El usuario quiere el modo avanzado (el asistente ha estado activo y no se ha desactivado a mano): se reintenta tras reiniciar. */
    fun desired(ctx: Context): Boolean = prefs(ctx).getBoolean("advanced_desired", false)
    fun setDesired(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("advanced_desired", v).apply() }

    /** Reactivar la Accesibilidad por si sola si se cae (solo con WRITE_SECURE_SETTINGS y si el usuario no lo ha desmarcado). */
    fun autoHeal(ctx: Context): Boolean = prefs(ctx).getBoolean("a11y_autoheal", true)
    fun setAutoHeal(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("a11y_autoheal", v).apply() }

    private fun me(ctx: Context) = ComponentName(ctx, MbotA11yService::class.java)

    fun a11yEnabled(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me(ctx) }
    }

    /**
     * Si nuestro servicio de Accesibilidad no esta en la lista de activados y tenemos WRITE_SECURE_SETTINGS, lo anade.
     * NO escribe nada si ya esta activado (caso normal). Devuelve true si cambio el ajuste.
     */
    fun ensureA11y(ctx: Context): Boolean {
        if (!hasSecure(ctx) || !autoHeal(ctx) || a11yEnabled(ctx)) return false
        return try {
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val add = me(ctx).flattenToString()
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) add else "$cur:$add")
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Accesibilidad reactivada con WRITE_SECURE_SETTINGS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo reactivar la Accesibilidad", e)
            false
        }
    }

    /** Arranque / actualizacion del paquete. */
    fun onBoot(ctx: Context) {
        ensureA11y(ctx)
        if (desired(ctx) && WirelessAdb.supported()) {
            try { Thread.sleep(20_000) } catch (_: InterruptedException) {}     // dejar que la red (Wi-Fi) y el servicio arranquen
            if (!HelperClient.alive()) WirelessAdb.autoStart(ctx)
        }
    }

    /** Llamado cada ~30 s por el servicio de Accesibilidad: si el asistente cayo y se desea, forma B (Android 11+). */
    fun monitorTick(ctx: Context) {
        if (HelperClient.alive()) { if (!desired(ctx)) setDesired(ctx, true); return }
        if (desired(ctx) && WirelessAdb.supported() && WirelessAdb.isPaired(ctx)) WirelessAdb.autoStart(ctx)
    }

    /** Apaga el modo avanzado: revoca WRITE_SECURE_SETTINGS, para el asistente y deja de reintentarlo. */
    fun disable(ctx: Context): String {
        setDesired(ctx, false)
        val parts = ArrayList<String>()
        if (hasSecure(ctx)) {
            val r = HelperClient.call("revoke_secure")
            parts += if (r?.ok == true) "WRITE_SECURE_SETTINGS revocado" else "no pude revocar WRITE_SECURE_SETTINGS (hazlo con: phone N advanced revoke)"
        }
        val q = HelperClient.call("quit")
        parts += if (q != null) "asistente parado" else "el asistente no estaba activo"
        HelperClient.invalidate()
        return parts.joinToString("; ")
    }
}
