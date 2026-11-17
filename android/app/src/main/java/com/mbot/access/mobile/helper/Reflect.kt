package com.mbot.access.mobile.helper

import android.os.IBinder
import java.lang.reflect.Method

/** Utilidades de reflexion sobre la API oculta. En el asistente (UID shell) las restricciones de API oculta no se aplican, pero se intenta una exencion por si acaso. */
object Reflect {
    @Volatile var exempted = false

    fun service(name: String): IBinder {
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
            ?: throw IllegalStateException("servicio '$name' no disponible")
    }

    /** IFoo.Stub.asInterface(binder) */
    fun iface(stub: String, binder: IBinder): Any =
        Class.forName("$stub\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

    /** Metodo publico (o declarado) por nombre y tipos de parametros; null si no existe. */
    fun method(c: Class<*>, name: String, vararg p: Class<*>): Method? = try {
        c.getMethod(name, *p)
    } catch (_: NoSuchMethodException) {
        try { c.getDeclaredMethod(name, *p).apply { isAccessible = true } } catch (_: NoSuchMethodException) { null }
    }

    /** Todos los metodos con ese nombre (para elegir por numero/tipo de parametros, que cambia entre versiones de Android). */
    fun methods(c: Class<*>, name: String): List<Method> =
        (c.methods.toList() + c.declaredMethods.toList()).filter { it.name == name }.distinct()

    /** Intenta exentar la API oculta (VMRuntime.setHiddenApiExemptions) con "meta-reflexion". Mejor esfuerzo: si falla no pasa nada. */
    fun exemptHiddenApis(): Boolean = try {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclared = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, java.lang.reflect.Array.newInstance(Class::class.java, 0).javaClass)
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclared.invoke(vmRuntime, "getRuntime", null) as Method
        val setEx = getDeclared.invoke(vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(java.lang.reflect.Array.newInstance(String::class.java, 0).javaClass)) as Method
        setEx.invoke(getRuntime.invoke(null), arrayOf("L") as Any)
        true
    } catch (_: Throwable) { false }

    /** Ejecuta un programa (sin shell) y devuelve salida combinada; null si agota el tiempo. */
    fun exec(timeoutMs: Long, vararg cmd: String): Pair<Int, String>? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread { try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) {} }
        t.isDaemon = true; t.start()
        val done = try { p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done) { p.destroyForcibly(); return null }
        t.join(500)
        return Pair(p.exitValue(), out.toString())
    }
}
