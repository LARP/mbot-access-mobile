package com.mbot.access.mobile

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.mbot.access.mobile.helper.HelperProto
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente (lado app) del asistente con identidad shell. El asistente nos entrega su Binder por el ContentProvider
 * (HelperProvider); aqui se guarda y se usa con transact. Nunca lanza excepciones: si el asistente no esta, devuelve null
 * y la app cae a la Accesibilidad.
 */
object HelperClient {
    class Reply(val json: JSONObject, val bin: ByteArray?) {
        val ok: Boolean get() = json.optBoolean("ok", false)
        val error: String get() = json.optString("error", "error del asistente")
    }

    private const val PING_TTL_MS = 1500L
    @Volatile private var binder: IBinder? = null
    @Volatile private var cached: JSONObject? = null
    @Volatile private var cachedAt = 0L

    /** Lo llama HelperProvider (tras comprobar que el que entrega es shell). */
    fun attach(b: IBinder) {
        binder = b
        android.util.Log.i(MbotA11yService.TAG, "asistente: binder recibido")
        try { b.linkToDeath({ android.util.Log.w(MbotA11yService.TAG, "asistente: binder muerto"); if (binder === b) { binder = null; invalidate() } }, 0) } catch (_: Exception) { binder = null; return }
        invalidate()
    }

    /** Una peticion; null si el asistente no esta. */
    fun call(req: JSONObject): Reply? {
        val b = binder ?: return null
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(HelperProto.DESCRIPTOR)
            d.writeString(req.toString())
            if (!b.transact(HelperProto.CODE_CALL, d, r, 0)) return null
            r.readException()
            val json = JSONObject(r.readString() ?: "{}")
            Reply(json, r.createByteArray())
        } catch (e: android.os.DeadObjectException) {
            if (binder === b) binder = null
            null
        } catch (e: SecurityException) {
            Reply(JSONObject().put("ok", false).put("error", "el asistente rechazo esta app: ${e.message}"), null)
        } catch (e: Exception) {
            android.util.Log.w(MbotA11yService.TAG, "asistente: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            d.recycle(); r.recycle()
        }
    }

    fun call(op: String, build: JSONObject.() -> Unit = {}): Reply? = call(JSONObject().put("op", op).apply(build))

    /** ping con cache de ~1,5 s; null = asistente ausente. */
    fun info(force: Boolean = false): JSONObject? {
        val now = SystemClock.uptimeMillis()
        if (!force && now - cachedAt < PING_TTL_MS) return cached
        val b = binder
        val r = if (b != null && b.isBinderAlive) call("ping") else null
        if (r == null) android.util.Log.w(MbotA11yService.TAG, "asistente: ping sin respuesta (binder=${b != null}, vivo=${b?.isBinderAlive})")
        cached = if (r != null && r.ok) r.json else null
        cachedAt = SystemClock.uptimeMillis()
        return cached
    }

    fun alive(): Boolean = info() != null

    fun has(feature: String): Boolean {
        val f = info()?.optJSONArray("features") ?: return false
        for (i in 0 until f.length()) if (f.optString(i) == feature) return true
        return false
    }

    fun features(): List<String> {
        val f: JSONArray = info()?.optJSONArray("features") ?: return emptyList()
        return (0 until f.length()).map { f.optString(it) }
    }

    /** El asistente que corre es de otra version de la app (tras `adb install -r`): sigue funcionando pero conviene reiniciarlo. */
    fun stale(): Boolean = info()?.optString("version")?.let { it != HelperProto.VERSION } ?: false

    /** Fuerza a repetir el ping en la proxima consulta. */
    fun invalidate() { cached = null; cachedAt = 0L }
}
