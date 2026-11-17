package com.mbot.access.mobile.helper

import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Servidor del asistente: socket abstracto, solo atiende a la UID de la app, una peticion JSON por mensaje. */
class HelperServer(private val appUid: Int, private val appPkg: String) {
    private val started = System.currentTimeMillis()
    private val inputLock = Any()
    val features: List<String> by lazy { detect() }
    @Volatile private var lastPeerRejected = 0
    @Volatile private var rejected = 0
    @Volatile var appDeliveries = 0

    private fun detect(): List<String> {
        val f = ArrayList<String>()
        if (Inj.available()) f += "input"
        if (Inj.displaySupport()) f += "display_input"
        f += "shot"
        if (Clip.available()) f += "clip"
        f += "secure"
        f += "vdisplay"
        return f
    }

    /** El objeto que recibe las llamadas de la app. onTransact comprueba la UID que pone el kernel. */
    val binder: Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(HelperProto.DESCRIPTOR); return true }
            if (code != HelperProto.CODE_CALL || reply == null) return super.onTransact(code, data, reply, flags)
            val uid = getCallingUid()
            if (!HelperProto.clientAllowed(uid, appUid)) {
                // otra UID (incluida la shell): se rechaza sin ejecutar nada
                lastPeerRejected = uid
                rejected++
                System.err.println("RECHAZADO uid=$uid")
                reply.writeException(SecurityException("uid $uid no autorizada"))
                return true
            }
            data.enforceInterface(HelperProto.DESCRIPTOR)
            val holder = Holder()
            val res = try {
                handle(JSONObject(data.readString() ?: "{}"), holder)
            } catch (e: Throwable) {
                val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                JSONObject().put("ok", false).put("error", "${root.javaClass.simpleName}: ${root.message}")
            }
            reply.writeNoException()
            reply.writeString(res.toString())
            reply.writeByteArray(holder.bin)
            return true
        }
    }

    private class Holder { var bin: ByteArray? = null }

    private fun ok() = JSONObject().put("ok", true)
    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun handle(q: JSONObject, h: Holder): JSONObject {
        val display = q.optInt("display", 0)
        return when (val op = q.optString("op")) {
            "ping" -> ok().put("helper", "mbot-helper").put("version", HelperProto.VERSION).put("proto", HelperProto.PROTO)
                .put("uid", Process.myUid()).put("pid", Process.myPid()).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("uptime_ms", System.currentTimeMillis() - started).put("features", JSONArray(features))
                .put("rejected", rejected).put("deliveries", appDeliveries).put("vd", JSONObject(VDisplay.status()))
            "tap" -> synchronized(inputLock) {
                val t0 = SystemClock.uptimeMillis()
                val r = Inj.tap(q.getDouble("x").toFloat(), q.getDouble("y").toFloat(), display, q.optLong("hold", 20))
                ok().put("injected", r).put("ms", SystemClock.uptimeMillis() - t0)
            }
            "swipe" -> synchronized(inputLock) {
                val r = Inj.swipe(q.getDouble("x1").toFloat(), q.getDouble("y1").toFloat(), q.getDouble("x2").toFloat(),
                    q.getDouble("y2").toFloat(), q.optLong("ms", 300), display)
                ok().put("injected", r)
            }
            "key" -> synchronized(inputLock) {
                val code = HelperProto.keyCode(q.optString("key"))
                if (code < 0) fail("tecla desconocida: ${q.optString("key")}")
                else ok().put("injected", Inj.key(code, display, q.optBoolean("long", false))).put("code", code)
            }
            "shot" -> {
                val o = if (q.optString("target") == "vd") {
                    val b = VDisplay.frame()
                    try { Shot.toJpeg(b, q.optInt("w", 360), q.optInt("q", 45), "vd") } finally { b.recycle() }
                } else Shot.capture(q.optInt("w", 360), q.optInt("q", 45))
                h.bin = o.jpeg
                ok().put("scale", o.scale).put("method", o.method).put("src_w", o.srcW).put("src_h", o.srcH)
            }
            "clip_get" -> { val t = Clip.get(); ok().put("text", t ?: JSONObject.NULL).put("empty", t == null) }
            "clip_set" -> { Clip.set(q.optString("text", "")); ok() }
            "grant_secure", "revoke_secure" -> {
                val verb = if (op == "grant_secure") "grant" else "revoke"
                val r = Reflect.exec(15000, "pm", verb, appPkg, "android.permission.WRITE_SECURE_SETTINGS")
                if (r == null) fail("pm $verb: tiempo agotado")
                else if (r.first != 0) fail("pm $verb fallo: " + r.second.trim().take(200))
                else ok()
            }
            "vd_start" -> ok().put("result", VDisplay.start(q.optInt("w", 720), q.optInt("h", 1280), q.optInt("dpi", 320)))
                .put("vd", JSONObject(VDisplay.status()))
            "vd_stop" -> ok().put("closed", JSONArray(VDisplay.stop(q.optBoolean("close_apps", true)))).put("vd", JSONObject(VDisplay.status()))
            "vd_status" -> ok().put("vd", JSONObject(VDisplay.status()))
            "vd_launch" -> ok().put("result", VDisplay.launch(q.optString("package"), q.optString("component").ifBlank { null }))
            "vd_tap", "vd_swipe", "vd_key" -> {
                if (!VDisplay.active) fail("la pantalla virtual no esta activa (vd start)")
                else handle(JSONObject(q.toString()).put("display", VDisplay.displayId).put("op", op.removePrefix("vd_")), h)
            }
            "probe" -> probe(q.optBoolean("bench", false))
            "quit" -> { shutdown(); ok().put("bye", true) }
            "" -> fail("falta 'op'")
            else -> fail("op desconocida: $op")
        }
    }

    /** Diagnostico: que piezas de la API oculta existen en este Android. */
    private fun probe(bench: Boolean): JSONObject {
        fun t(f: () -> Any?): Any = try { f() ?: "null" } catch (e: Throwable) { "ERR " + (e.cause ?: e).javaClass.simpleName + ": " + (e.cause ?: e).message }
        val sc = try { Class.forName("android.view.SurfaceControl") } catch (_: Throwable) { null }
        return ok().put("input", t { Inj.available() }).put("display_input", t { Inj.displaySupport() })
            .put("clip", t { Clip.available() })
            .put("surfacecontrol_screenshot", t { JSONArray(sc?.methods?.filter { it.name == "screenshot" }?.map { m -> m.parameterTypes.joinToString(",") { p -> p.simpleName } } ?: emptyList<String>()) })
            .put("display_size", t { JSONArray(Shot.displaySize().toList()) })
            .put("hidden_exempt", Reflect.exempted)
            .put("last_rejected_uid", lastPeerRejected)
            .put("display_methods", t { displayMembers() })
            .apply {
                if (bench) {
                    fun time(f: () -> Any?): Any = try { val t0 = SystemClock.uptimeMillis(); val b = f(); val ms = SystemClock.uptimeMillis() - t0
                        if (b is android.graphics.Bitmap) { val r = "${b.width}x${b.height} ${ms}ms"; b.recycle(); r } else "${ms}ms" } catch (e: Throwable) { "ERR " + (e.cause ?: e).message }
                    put("bench_surface_1", time { Shot.surfaceControlBitmap() }).put("bench_surface_2", time { Shot.surfaceControlBitmap() })
                    put("bench_screencap_1", time { Shot.screencapBitmap() }).put("bench_screencap_2", time { Shot.screencapBitmap() })
                    put("bench_full_jpeg", time { Shot.capture(360, 45).let { null } })
                }
            }
    }

    private fun displayMembers(): JSONArray {
        val out = JSONArray()
        for (c in listOf(android.view.InputEvent::class.java, android.view.MotionEvent::class.java, android.view.KeyEvent::class.java)) {
            for (m in (c.declaredMethods.toList() + c.methods.toList()).filter { it.name.contains("isplay") }.map { it.name }.distinct()) out.put(c.simpleName + "." + m)
            for (f in c.declaredFields.filter { it.name.contains("isplay") }) out.put(c.simpleName + "#" + f.name)
        }
        return out
    }

    fun shutdown() {
        try { VDisplay.stop(true) } catch (_: Throwable) {}
        Thread { Thread.sleep(150); Runtime.getRuntime().halt(0) }.start()
    }

    /** Autoprueba: una llamada desde este mismo proceso lleva la UID shell, que NO es la de la app: debe rechazarse. */
    fun selfTestRejects(): Boolean {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(HelperProto.DESCRIPTOR); d.writeString("{\"op\":\"ping\"}")
            binder.transact(HelperProto.CODE_CALL, d, r, 0)
            try { r.readException(); false } catch (_: SecurityException) { true }
        } finally { d.recycle(); r.recycle() }
    }
}
