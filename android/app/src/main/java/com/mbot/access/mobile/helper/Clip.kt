package com.mbot.access.mobile.helper

import android.content.ClipData
import java.lang.reflect.Method

/** Portapapeles via IClipboard (como paquete com.android.shell). Los argumentos cambian entre versiones: se rellenan por tipo. */
object Clip {
    private const val PKG = "com.android.shell"
    private val svc: Any by lazy { Reflect.iface("android.content.IClipboard", Reflect.service("clipboard")) }

    private fun args(m: Method, clip: ClipData?): Array<Any?> {
        var strings = 0
        return m.parameterTypes.map { t ->
            when {
                t == ClipData::class.java -> clip
                t == String::class.java -> if (strings++ == 0) PKG else null     // 1.o = paquete, 2.o = attributionTag
                t == Int::class.javaPrimitiveType -> 0                          // userId / deviceId
                else -> null
            }
        }.toTypedArray()
    }

    fun available(): Boolean = try { Reflect.methods(svc.javaClass, "getPrimaryClip").isNotEmpty(); true } catch (_: Throwable) { false }

    fun get(): String? {
        val m = Reflect.methods(svc.javaClass, "getPrimaryClip").firstOrNull() ?: throw NoSuchMethodException("getPrimaryClip")
        val c = m.invoke(svc, *args(m, null)) as? ClipData ?: return null
        if (c.itemCount == 0) return null
        return c.getItemAt(0).text?.toString()
    }

    fun set(text: String) {
        val m = Reflect.methods(svc.javaClass, "setPrimaryClip").firstOrNull() ?: throw NoSuchMethodException("setPrimaryClip")
        m.invoke(svc, *args(m, ClipData.newPlainText("mbot", text)))
    }
}
