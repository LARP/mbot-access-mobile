package com.mbot.access.mobile.helper

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process

/**
 * Entrega el Binder del asistente a la app llamando a su ContentProvider (el truco de Shizuku):
 *   IActivityManager.getContentProviderExternal(authority) -> IContentProvider.call("binder", extras{binder})
 * Las firmas de estas llamadas ocultas cambian segun la version de Android: se eligen por numero y tipo de parametros.
 * Probado en Android 9 (API 28); las ramas de API 29, 30 y 31+ (AttributionSource) estan escritas contra AOSP y SIN PROBAR.
 */
object BinderSender {
    private const val CALLER = "com.android.shell"

    @Volatile var appBinder: IBinder? = null

    /** true si la app recibio el Binder. */
    fun send(helper: IBinder, pkg: String): Boolean {
        val am = Reflect.iface("android.app.IActivityManager", Reflect.service("activity"))
        val token = Binder()
        val get = Reflect.methods(am.javaClass, "getContentProviderExternal").first()
        var strings = 0
        val getArgs = get.parameterTypes.map { t ->
            when (t) {
                String::class.java -> if (strings++ == 0) HelperProto.AUTHORITY else "mbot-helper"   // nombre y, en Android 10+, tag
                Int::class.javaPrimitiveType -> 0
                IBinder::class.java -> token
                else -> null
            }
        }.toTypedArray()
        val holder = get.invoke(am, *getArgs) ?: throw IllegalStateException("el proveedor ${HelperProto.AUTHORITY} no existe (app no instalada?)")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: throw IllegalStateException("el proveedor no esta disponible")
            val call = Reflect.methods(provider.javaClass, "call").maxByOrNull { it.parameterTypes.size }
                ?: throw NoSuchMethodException("IContentProvider.call")
            val extras = Bundle().apply { putBinder(HelperProto.EXTRA_BINDER, helper) }
            val pt = call.parameterTypes
            val args: Array<Any?> = when {
                pt[0].name == "android.content.AttributionSource" -> arrayOf(attribution(pkg), HelperProto.AUTHORITY, HelperProto.METHOD_BINDER, null, extras)   // API 31+
                pt.size == 6 -> arrayOf(CALLER, null, HelperProto.AUTHORITY, HelperProto.METHOD_BINDER, null, extras)                                     // API 30
                pt.size == 5 -> arrayOf(CALLER, HelperProto.AUTHORITY, HelperProto.METHOD_BINDER, null, extras)                                          // API 29
                else -> arrayOf(CALLER, HelperProto.METHOD_BINDER, null, extras)                                                                           // API 26-28
            }
            val res = call.invoke(provider, *args) as? Bundle
            appBinder = res?.getBinder(HelperProto.EXTRA_APP_BINDER)
            return res != null && res.getBoolean("ok", false)
        } finally {
            try {
                Reflect.methods(am.javaClass, "removeContentProviderExternal").firstOrNull()?.let { m ->
                    var n = 0
                    m.invoke(am, *m.parameterTypes.map { t ->
                        when (t) { String::class.java -> if (n++ == 0) HelperProto.AUTHORITY else null; IBinder::class.java -> token; else -> null }
                    }.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    }

    private fun attribution(pkg: String): Any {
        val c = Class.forName("android.content.AttributionSource")
        return c.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java).newInstance(Process.myUid(), CALLER, null)
    }

    /** ¿Sigue viva la app? (su proceso con el nombre del paquete). */
    fun appRunning(pkg: String): Boolean = try {
        Reflect.exec(2000, "pidof", pkg)?.let { it.first == 0 && it.second.isNotBlank() } ?: false
    } catch (_: Throwable) { false }
}
