package com.mbot.access.mobile.helper

import android.os.IBinder
import android.os.Looper
import android.os.Process

/**
 * Punto de entrada del asistente. Se lanza con identidad shell (uid 2000):
 *   app_process -Djava.class.path=<APK> /system/bin --nice-name=mbot_helper com.mbot.access.mobile.helper.Main --uid=<UID_APP> [--pkg=com.mbot.access.mobile]
 * No usa Context ni nada de la app: solo el framework. Sale con la op `quit` o matandolo (kill del pid).
 * `--selftest` comprueba que rechaza llamadas de una UID que no es la de la app y sale.
 */
object Main {
    @Volatile private var delivered = false
    @Volatile private var attempts = 0
    @Volatile private var lastError = ""

    @JvmStatic
    fun main(args: Array<String>) {
        var uid = -1
        var pkg = "com.mbot.access.mobile"
        var selftest = false
        for (a in args) {
            if (a.startsWith("--uid=")) uid = a.removePrefix("--uid=").toIntOrNull() ?: -1
            if (a.startsWith("--pkg=")) pkg = a.removePrefix("--pkg=")
            if (a == "--selftest") selftest = true
        }
        if (uid <= 0) { System.err.println("ERR falta --uid=<uid de la app>"); System.exit(2) }
        val me = Process.myUid()
        if (me != HelperProto.UID_SHELL && me != HelperProto.UID_ROOT) {
            System.err.println("ERR debe ejecutarse como shell (uid 2000); uid=$me"); System.exit(3)
        }
        Reflect.exempted = Reflect.exemptHiddenApis()
        Looper.prepareMainLooper()
        val server = HelperServer(uid, pkg)
        if (selftest) {
            val rejected = server.selfTestRejects()
            println("SELFTEST uid_propia=$me uid_app=$uid rechaza_otra_uid=$rejected")
            System.out.flush()
            System.exit(if (rejected) 0 else 1)
        }
        tryDeliver(server, pkg)
        println("READY pid=${Process.myPid()} uid=$me app_uid=$uid version=${HelperProto.VERSION} delivered=${if (delivered) 1 else 0}" +
            (if (!delivered && lastError.isNotEmpty()) " (primer intento: $lastError; reintentara)" else ""))
        System.out.flush()
        Thread({ watch(server, pkg) }, "mbot-helper-watch").also { it.isDaemon = true }.start()
        Looper.loop()
    }

    private fun tryDeliver(server: HelperServer, pkg: String): Boolean {
        attempts++
        return try {
            val ok = BinderSender.send(server.binder, pkg)
            if (ok) {
                delivered = true
                lastError = ""
                server.appDeliveries++
                try { BinderSender.appBinder?.linkToDeath(IBinder.DeathRecipient { delivered = false }, 0) } catch (_: Throwable) {}
            } else lastError = "la app no acepto el binder"
            ok
        } catch (e: Throwable) {
            val r = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            lastError = "${r.javaClass.simpleName}: ${r.message}"
            false
        }
    }

    /** Si la app muere (la matan o se reinstala), vuelve a entregar el Binder cuando su proceso reaparezca (sin relanzarla nosotros). */
    private fun watch(server: HelperServer, pkg: String) {
        var sinceCheck = 0
        while (true) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            sinceCheck++
            val ab = BinderSender.appBinder
            if (delivered && sinceCheck % 5 == 0 && ab != null && !ab.pingBinder()) delivered = false
            if (!delivered && BinderSender.appRunning(pkg)) {
                val ok = tryDeliver(server, pkg)
                println("DELIVER ${if (ok) "ok" else "fallo: $lastError"} intento=$attempts")
                System.out.flush()
            }
        }
    }
}
