package com.mbot.access.mobile

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Cliente (PC) vinculado: solo se guarda el hash SHA-256 de su token, nunca el token. */
class Client(val id: String, val name: String, val created: Long, val hash: String)

/** Resultado de un intento de emparejar con un codigo. */
class PairAttempt(val ok: Boolean, val status: Int, val error: String?)

/**
 * Emparejamiento: codigo de 6 digitos valido 2 minutos, de un solo uso; 5 fallos lo invalidan.
 * Cada PC vinculado recibe su PROPIO token (revocable por separado). El token unico de la Fase 0 sigue valiendo
 * (se trata como una entrada mas, "legado") mientras no se revoque.
 */
object Pairing {
    const val CODE_TTL_MS = 120_000L
    const val MAX_TRIES = 5
    private const val PREFS = "mbot"
    private const val MAX_CLIENTS = 20
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    const val LEGACY_ID = "legacy"

    private val rnd = SecureRandom()
    private val lock = Any()
    private var code: String? = null
    private var expiresAt = 0L
    private var tries = 0

    // ------------------------------------------------------------------ codigo
    fun newCode(): String = synchronized(lock) {
        val c = String.format("%06d", rnd.nextInt(1_000_000))
        code = c
        expiresAt = SystemClock.elapsedRealtime() + CODE_TTL_MS
        tries = 0
        c
    }

    fun cancelCode() = synchronized(lock) { code = null }

    /** (codigo, milisegundos restantes) o null si no hay codigo valido. */
    fun active(): Pair<String, Long>? = synchronized(lock) {
        val c = code ?: return null
        val left = expiresAt - SystemClock.elapsedRealtime()
        if (left <= 0) { code = null; return null }
        Pair(c, left)
    }

    /** Comprueba el codigo recibido; si es correcto lo consume (un solo uso). */
    fun check(presented: String): PairAttempt = synchronized(lock) {
        val c = code
        if (c == null || expiresAt - SystemClock.elapsedRealtime() <= 0) {
            code = null
            return PairAttempt(false, 403, "codigo caducado o no generado: pulsa 'Vincular un PC' en la app y usa el codigo nuevo")
        }
        if (MessageDigest.isEqual(presented.trim().toByteArray(Charsets.UTF_8), c.toByteArray(Charsets.UTF_8))) {
            code = null
            return PairAttempt(true, 200, null)
        }
        tries++
        if (tries >= MAX_TRIES) {
            code = null
            return PairAttempt(false, 429, "demasiados intentos fallidos: el codigo se invalido, genera uno nuevo en la app")
        }
        PairAttempt(false, 403, "codigo incorrecto (quedan ${MAX_TRIES - tries} intentos)")
    }

    // ------------------------------------------------------------------ clientes
    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun clients(ctx: Context): List<Client> {
        val raw = prefs(ctx).getString("clients", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Client(o.getString("id"), o.optString("name", "?"), o.optLong("created"), o.getString("hash"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, list: List<Client>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("id", c.id).put("name", c.name).put("created", c.created).put("hash", c.hash))
        prefs(ctx).edit().putString("clients", a.toString()).commit()
    }

    private fun newToken(): String = buildString { repeat(24) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    /** Crea un cliente con token propio. Devuelve el token en claro (solo esta vez). */
    @Synchronized
    fun addClient(ctx: Context, rawName: String): Pair<Client, String> {
        val name = rawName.filter { !it.isISOControl() }.trim().take(40).ifBlank { "PC" }
        val token = newToken()
        val c = Client(newToken().take(8), name, System.currentTimeMillis(), sha256(token))
        val list = (clients(ctx) + c).takeLast(MAX_CLIENTS)
        save(ctx, list)
        return Pair(c, token)
    }

    @Synchronized
    fun revoke(ctx: Context, id: String) {
        if (id == LEGACY_ID) { prefs(ctx).edit().putBoolean("legacy_revoked", true).commit(); return }
        save(ctx, clients(ctx).filter { it.id != id })
    }

    fun legacyRevoked(ctx: Context) = prefs(ctx).getBoolean("legacy_revoked", false)

    /** Id del cliente al que pertenece el token presentado ("legacy" para el token unico), o null. */
    fun authorize(ctx: Context, presented: String): String? {
        val bytes = presented.toByteArray(Charsets.UTF_8)
        if (!legacyRevoked(ctx) && MessageDigest.isEqual(bytes, Token.get(ctx).toByteArray(Charsets.UTF_8))) return LEGACY_ID
        val h = sha256(presented).toByteArray(Charsets.UTF_8)
        var found: String? = null
        for (c in clients(ctx)) if (MessageDigest.isEqual(h, c.hash.toByteArray(Charsets.UTF_8))) found = c.id
        return found
    }
}
