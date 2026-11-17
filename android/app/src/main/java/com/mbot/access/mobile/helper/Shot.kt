package com.mbot.access.mobile.helper

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Captura de pantalla desde el asistente: SurfaceControl.screenshot (oculta) y, si falla, `screencap` (siempre disponible para shell). */
object Shot {
    class Out(val jpeg: ByteArray, val scale: Double, val method: String, val srcW: Int, val srcH: Int)

    @Volatile var preferred: String? = null   // "surface" | "screencap" tras la primera captura buena

    private fun realDisplay(): Any? = try {
        val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val inst = dmg.getMethod("getInstance").invoke(null)
        dmg.getMethod("getRealDisplay", Int::class.javaPrimitiveType).invoke(inst, 0)
    } catch (_: Throwable) { null }

    fun displaySize(): IntArray {
        val d = realDisplay() as? android.view.Display
        if (d != null) {
            val p = Point()
            @Suppress("DEPRECATION") d.getRealSize(p)
            if (p.x > 0 && p.y > 0) return intArrayOf(p.x, p.y, d.rotation)
        }
        val r = Reflect.exec(3000, "wm", "size")?.second ?: ""
        val m = Regex("([0-9]+)x([0-9]+)").findAll(r).lastOrNull()
        return if (m != null) intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 0) else intArrayOf(0, 0, 0)
    }

    fun surfaceControlBitmap(): Bitmap? {
        val sz = displaySize()
        if (sz[0] <= 0) return null
        val sc = Class.forName("android.view.SurfaceControl")
        val m = Reflect.method(sc, "screenshot", Rect::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        if (m != null) {
            val b = m.invoke(null, Rect(), sz[0], sz[1], sz[2]) as? Bitmap
            if (b != null) return soft(b)
        }
        val m2 = Reflect.method(sc, "screenshot", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        return (m2?.invoke(null, sz[0], sz[1]) as? Bitmap)?.let { soft(it) }
    }

    private fun soft(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, false)
        if (c != null && c !== b) b.recycle()
        return c ?: b
    }

    /** `screencap` crudo (RGBA + cabecera de 12 o 16 bytes). */
    fun screencapBitmap(display: Int? = null): Bitmap? {
        val cmd = if (display != null) arrayOf("screencap", "-d", display.toString()) else arrayOf("screencap")
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null"))).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        if (bytes.size < 16) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0); val h = bb.getInt(4)
        if (w <= 0 || h <= 0) return null
        val hdr = bytes.size - w * h * 4
        if (hdr != 12 && hdr != 16) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // buffer DIRECTO: con un ByteBuffer.wrap(...) con desplazamiento, copyPixelsFromBuffer corrompe el heap (SIGSEGV en Android 9)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        buf.put(bytes, hdr, w * h * 4)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    fun toJpeg(src: Bitmap, width: Int, quality: Int, method: String): Out {
        val w = if (width in 16 until src.width) width else src.width
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        val scaled = if (w != src.width) Bitmap.createScaledBitmap(src, w, h, true) else src
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(5, 95), bos)
        val out = Out(bos.toByteArray(), src.width.toDouble() / w, method, src.width, src.height)
        if (scaled !== src) scaled.recycle()
        return out
    }

    @Synchronized
    fun capture(width: Int, quality: Int): Out {
        val order = if (preferred == "screencap") listOf("screencap", "surface") else listOf("surface", "screencap")
        var last: Throwable? = null
        for (how in order) {
            try {
                val b = (if (how == "surface") surfaceControlBitmap() else screencapBitmap()) ?: continue
                val o = toJpeg(b, width, quality, how)
                b.recycle()
                preferred = how
                return o
            } catch (e: Throwable) { last = e }
        }
        throw IllegalStateException("no se pudo capturar la pantalla" + (last?.let { ": ${it.javaClass.simpleName} ${it.cause?.message ?: it.message}" } ?: ""))
    }
}
