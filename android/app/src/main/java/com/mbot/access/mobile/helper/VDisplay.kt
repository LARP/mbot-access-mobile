package com.mbot.access.mobile.helper

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pantalla virtual oculta: DisplayManager.createVirtualDisplay con un Context falso del paquete com.android.shell
 * (el servicio comprueba que el paquete pertenezca a la UID que llama), una superficie de ImageReader para leer el contenido
 * y `am start --display N` para lanzar apps alli. Los toques/teclas se inyectan con displayId (Inj).
 */
object VDisplay {
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_PRESENTATION = 1 shl 1
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6          // oculta
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8   // oculta

    /** Context con identidad de paquete shell. Los metodos ocultos (getOpPackageName...) se sobrescriben a nivel de JVM. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
        override fun getAttributionTag(): String? = null
        override fun getApplicationContext(): Context = this
    }

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var held: Image? = null
    private val lock = Any()
    @Volatile var displayId = -1
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var dpi = 0
    @Volatile var frames = 0L
    val launched = LinkedHashSet<String>()
    private var ctx: Context? = null

    val active: Boolean get() = vd != null

    private fun systemContext(): Context {
        ctx?.let { return it }
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val at = Class.forName("android.app.ActivityThread")
                val t = at.getMethod("systemMain").invoke(null)
                ctx = ShellContext(at.getMethod("getSystemContext").invoke(t) as Context)
            } catch (e: Throwable) { err = e }
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("tiempo agotado creando el contexto del sistema")
        err?.let { throw it }
        return ctx!!
    }

    @Synchronized
    fun start(w: Int, h: Int, d: Int): String {
        if (vd != null) return "ya activa"
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(systemContext())
        val ht = HandlerThread("mbot-vd").also { it.start() }
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(lock) { held?.close(); held = img; frames++ }
            } catch (_: Throwable) {}
        }, Handler(ht.looper))
        val flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL
        val v = try {
            dm.createVirtualDisplay("mbot-vd", w, h, d, r.surface, flags)
        } catch (e: Throwable) {
            r.close(); ht.quitSafely()
            throw e
        } ?: run { r.close(); ht.quitSafely(); throw IllegalStateException("createVirtualDisplay devolvio null") }
        vd = v; reader = r; thread = ht
        displayId = v.display.displayId; width = w; height = h; dpi = d; frames = 0
        launched.clear()
        return "ok"
    }

    @Synchronized
    fun stop(closeApps: Boolean): List<String> {
        val closed = ArrayList<String>()
        if (closeApps) for (p in launched) {
            try { Reflect.exec(5000, "am", "force-stop", p); closed.add(p) } catch (_: Throwable) {}
        }
        launched.clear()
        try { vd?.release() } catch (_: Throwable) {}
        synchronized(lock) { try { held?.close() } catch (_: Throwable) {}; held = null }
        try { reader?.close() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        vd = null; reader = null; thread = null; displayId = -1; frames = 0
        return closed
    }

    /** `am start --display N` (por componente si se da; si no, por paquete + LAUNCHER). */
    fun launch(pkg: String, component: String?): String {
        if (!active) throw IllegalStateException("la pantalla virtual no esta activa (vd start)")
        // NEW_TASK | MULTIPLE_TASK: una tarea nueva en la pantalla virtual aunque la app ya este abierta en la pantalla del usuario
        val cmd = ArrayList<String>(listOf("am", "start", "--display", displayId.toString(), "-f", "0x18000000"))
        if (!component.isNullOrBlank()) cmd += listOf("-n", component)
        else cmd += listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", pkg)
        val r = Reflect.exec(15000, *cmd.toTypedArray()) ?: throw IllegalStateException("am start: tiempo agotado")
        val out = r.second.trim()
        if (r.first != 0 || out.contains("Error", ignoreCase = true) || out.contains("Exception")) {
            throw IllegalStateException("am start fallo: " + out.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | "))
        }
        // Si la app ya tenia una tarea en la pantalla del usuario, Android puede reutilizarla alli (visto en MIUI 13 /
        // Android 12): se comprueba que la actividad quedo de verdad en la pantalla virtual.
        if (!onDisplay(pkg)) {
            throw IllegalStateException("$pkg sigue en la pantalla del usuario (ya estaba abierta y Android reutilizo su tarea); " +
                "ciérrala desde Recientes y repite vd launch")
        }
        launched.add(pkg)
        return out.lines().lastOrNull { it.isNotBlank() } ?: "ok"
    }

    /** true si `dumpsys activity activities` lista una actividad de [pkg] bajo "Display #<displayId>". Si no se puede saber, true. */
    private fun onDisplay(pkg: String): Boolean {
        for (i in 0 until 10) {
            val r = Reflect.exec(5000, "dumpsys", "activity", "activities") ?: return true
            var cur = -1
            for (line in r.second.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("Display #")) cur = t.removePrefix("Display #").takeWhile { it.isDigit() }.toIntOrNull() ?: -1
                else if (cur == displayId && t.contains("$pkg/")) return true
            }
            Thread.sleep(150)
        }
        return false
    }

    /** Ultimo fotograma como Bitmap (espera hasta 2 s al primero). */
    fun frame(): Bitmap {
        val t0 = System.currentTimeMillis()
        while (true) {
            synchronized(lock) {
                val img = held
                if (img != null) {
                    val p = img.planes[0]
                    val rs = p.rowStride / p.pixelStride
                    val tmp = Bitmap.createBitmap(rs, img.height, Bitmap.Config.ARGB_8888)
                    p.buffer.rewind()
                    tmp.copyPixelsFromBuffer(p.buffer)
                    if (rs == img.width) return tmp
                    val out = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
                    tmp.recycle()
                    return out
                }
            }
            if (System.currentTimeMillis() - t0 > 2000) throw IllegalStateException("la pantalla virtual aun no ha dibujado nada")
            Thread.sleep(50)
        }
    }

    fun status(): Map<String, Any> = mapOf("active" to active, "id" to displayId, "width" to width, "height" to height,
        "dpi" to dpi, "frames" to frames, "launched" to launched.toList())
}
